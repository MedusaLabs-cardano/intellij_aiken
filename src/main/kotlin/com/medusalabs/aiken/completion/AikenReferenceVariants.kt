package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenImportedNameKind
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_EXPORT_INDEX_NAME
import com.medusalabs.aiken.index.AikenPublicExportExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolEntry
import com.medusalabs.aiken.index.AikenTopLevelSymbolExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.decodeAikenExportIndexValue
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.navigation.AikenTopLevelSymbolLookup
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenProjectRoots
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

object AikenReferenceVariants {
    private const val UNIMPORTED_EXPORTED_SYMBOL_PRIORITY = 4100.0
    private val lookupKinds =
        setOf(
            AikenTopLevelSymbolKind.FUNCTION,
            AikenTopLevelSymbolKind.TYPE,
            AikenTopLevelSymbolKind.CONST,
            AikenTopLevelSymbolKind.CONSTRUCTOR
        )

    fun forElement(element: PsiElement): Array<Any> {
        val file = element.containingFile ?: return emptyArray()
        if (file.fileType != AikenFileType) return emptyArray()

        val text = file.text
        val offset = element.textRange.startOffset
        val useModel = AikenUseStatementParser.parseModel(text)
        val qualifier = findQualifier(text, offset)
        val seen = LinkedHashSet<String>()
        val variants = ArrayList<Any>()

        if (qualifier != null) {
            for (moduleTarget in useModel.resolveModuleTargets(qualifier)) {
                for (symbol in exportedSymbols(element, moduleTarget.modulePath)) {
                    addVariant(
                        variants,
                        seen,
                        symbol,
                        inferTopLevelKind(element, moduleTarget.modulePath, symbol),
                        2600.0
                    )
                }
            }
            return variants.toTypedArray()
        }

        for (binding in AikenLocalScopeAnalyzer.collectVisibleBindings(element)) {
            addVariant(variants, seen, binding.name, CompletionSymbolKind.IDENTIFIER, 2800.0)
        }

        for (importedName in useModel.importedNames()) {
            val kind =
                when (importedName.kind) {
                    AikenImportedNameKind.MODULE_ALIAS -> CompletionSymbolKind.IDENTIFIER
                    AikenImportedNameKind.ITEM,
                    AikenImportedNameKind.ITEM_ALIAS ->
                        inferTopLevelKind(element, importedName.statement.modulePath, importedName.sourceName)
                }
            addVariant(variants, seen, importedName.exposedName, kind, 2600.0)
        }

        for (entry in AikenTopLevelSymbolExtractor.extract(text)) {
            addVariant(variants, seen, entry.name, mapTopLevelKind(entry), 2400.0)
        }

        if (qualifier == null) {
            val prefix = currentIdentifierPrefix(element)
            if (prefix.isNotEmpty()) {
                for (lookup in unimportedExportsForPrefix(element, prefix, excludedNames = seen)) {
                    if (seen.add(lookup.lookupString)) {
                        variants += lookup
                    }
                }
            }
        }

        return variants.toTypedArray()
    }

    fun unimportedExportsForPrefix(
        element: PsiElement,
        prefix: String,
        excludedNames: Set<String> = emptySet()
    ): List<LookupElement> {
        if (prefix.isEmpty()) return emptyList()
        val file = element.containingFile ?: return emptyList()
        if (file.fileType != AikenFileType) return emptyList()

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val importedNames = useModel.importedNames().mapTo(LinkedHashSet()) { it.exposedName }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<LookupElement>()
        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath.isBlank() || modulePath == currentModulePath) continue
            val text = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            val exportedNames = AikenPublicExportExtractor.extract(text).toSet()
            for (entry in AikenTopLevelSymbolExtractor.extract(text)) {
                if (entry.name !in exportedNames) continue
                if (!entry.name.startsWith(prefix, ignoreCase = true)) continue
                if (entry.name in excludedNames || entry.name in importedNames || !seen.add(entry.name)) continue
                result += createAutoImportedExportLookup(modulePath, entry.name, mapTopLevelKind(entry))
            }
        }

        return result
    }

    private fun addVariant(
        variants: MutableList<Any>,
        seen: MutableSet<String>,
        name: String,
        kind: CompletionSymbolKind,
        priority: Double
    ) {
        if (name.isBlank() || name.length < 2 || !seen.add(name)) return
        variants += CompletionItemFactory.create(name, kind, priority)
    }

    private fun mapTopLevelKind(entry: AikenTopLevelSymbolEntry): CompletionSymbolKind =
        when (entry.kind) {
            AikenTopLevelSymbolKind.FUNCTION -> CompletionSymbolKind.FUNCTION
            AikenTopLevelSymbolKind.TYPE,
            AikenTopLevelSymbolKind.CONSTRUCTOR -> CompletionSymbolKind.TYPE
            AikenTopLevelSymbolKind.CONST -> CompletionSymbolKind.IDENTIFIER
        }

    private fun inferTopLevelKind(
        anchor: PsiElement,
        modulePath: String,
        symbolName: String
    ): CompletionSymbolKind {
        val target =
            AikenTopLevelSymbolLookup.findTargets(anchor, symbolName, lookupKinds, setOf(modulePath)).firstOrNull()
                ?: return heuristicKind(symbolName)

        return when (target.node?.elementType) {
            AikenTokenTypes.FUNCTION -> CompletionSymbolKind.FUNCTION
            AikenTokenTypes.TYPE -> CompletionSymbolKind.TYPE
            AikenTokenTypes.FIELD -> CompletionSymbolKind.FIELD
            else -> heuristicKind(symbolName)
        }
    }

    private fun heuristicKind(symbolName: String): CompletionSymbolKind =
        if (symbolName.firstOrNull()?.isUpperCase() == true) CompletionSymbolKind.TYPE else CompletionSymbolKind.FUNCTION

    private fun exportedSymbols(anchor: PsiElement, modulePath: String): List<String> {
        val project = anchor.project
        if (DumbService.isDumb(project)) return emptyList()

        return try {
            val scope = AikenSearchScopes.forElement(anchor)
            val names = LinkedHashSet<String>()
            val index = FileBasedIndex.getInstance()
            for (value in index.getValues(AIKEN_EXPORT_INDEX_NAME, modulePath, scope)) {
                names += decodeAikenExportIndexValue(value)
            }
            names.toList()
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun findQualifier(text: CharSequence, symbolOffset: Int): String? {
        var index = symbolOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0 || text[index] != '.') return null

        index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return null

        val end = index + 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) index--
        val start = index + 1

        return if (start < end) text.subSequence(start, end).toString() else null
    }

    private fun currentIdentifierPrefix(element: PsiElement): String =
        element.text.takeWhile { it.isLetterOrDigit() || it == '_' }

    private fun collectModuleFiles(root: VirtualFile): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()

        fun walk(directory: VirtualFile?) {
            if (directory == null || !directory.isValid || !directory.isDirectory) return
            for (child in directory.children) {
                when {
                    child.isDirectory -> walk(child)
                    child.fileType == AikenFileType -> result += child
                }
            }
        }

        walk(root.findChild("lib"))
        walk(root.findChild("validators"))
        root.findFileByRelativePath("build/packages")
            ?.children
            ?.filter { it.isDirectory }
            ?.forEach { packageDir ->
                walk(packageDir.findChild("lib"))
                walk(packageDir.findChild("validators"))
            }

        return result
    }

    private fun createAutoImportedExportLookup(
        modulePath: String,
        symbolName: String,
        kind: CompletionSymbolKind
    ): LookupElement {
        val builder =
            com.intellij.codeInsight.lookup.LookupElementBuilder
                .create(symbolName)
                .withIcon(
                    when (kind) {
                        CompletionSymbolKind.TYPE -> com.intellij.icons.AllIcons.Nodes.Class
                        CompletionSymbolKind.FUNCTION -> com.intellij.icons.AllIcons.Nodes.Method
                        CompletionSymbolKind.FIELD -> com.intellij.icons.AllIcons.Nodes.Field
                        CompletionSymbolKind.IDENTIFIER -> com.intellij.icons.AllIcons.Nodes.Variable
                        CompletionSymbolKind.KEYWORD -> com.intellij.icons.AllIcons.Nodes.Static
                    }
                )
                .withTypeText(
                    when (kind) {
                        CompletionSymbolKind.TYPE -> "type"
                        CompletionSymbolKind.FUNCTION -> "fn"
                        CompletionSymbolKind.FIELD -> "field"
                        CompletionSymbolKind.IDENTIFIER -> "var"
                        CompletionSymbolKind.KEYWORD -> "keyword"
                    },
                    true
                )
                .withTailText(" from $modulePath", true)
                .withInsertHandler { insertionContext, _ ->
                    replaceCurrentIdentifierPrefix(insertionContext, symbolName)
                    insertionContext.commitDocument()
                    val previousLaterRunnable = insertionContext.laterRunnable
                    insertionContext.setLaterRunnable {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneUseImport(insertionContext.document, modulePath, symbolName)
                            insertionContext.commitDocument()
                        }
                    }
                }
        return com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(builder, UNIMPORTED_EXPORTED_SYMBOL_PRIORITY)
    }

    private fun replaceCurrentIdentifierPrefix(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        replacementText: String
    ) {
        val document = insertionContext.document
        val chars = document.charsSequence
        var replaceStart = insertionContext.startOffset.coerceIn(0, chars.length)
        while (replaceStart > 0 && (chars[replaceStart - 1].isLetterOrDigit() || chars[replaceStart - 1] == '_')) {
            replaceStart--
        }
        document.replaceString(replaceStart, insertionContext.tailOffset, replacementText)
    }

    private fun insertStandaloneUseImport(
        document: com.intellij.openapi.editor.Document,
        modulePath: String,
        symbolName: String
    ) {
        document.insertString(0, "use $modulePath.{$symbolName}\n")
    }
}
