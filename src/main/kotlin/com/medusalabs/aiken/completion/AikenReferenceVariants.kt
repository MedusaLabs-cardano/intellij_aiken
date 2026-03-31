package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
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
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenProjectRoots
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

object AikenReferenceVariants {
    // Ordinary unimported exports should stay discoverable, but they should not outrank
    // closer matches from local scope, imports, or same-file declarations.
    private const val UNIMPORTED_EXPORTED_SYMBOL_PRIORITY = 2200.0
    private const val UNIMPORTED_MODULE_PRIORITY = 2300.0
    private val lookupKinds =
        setOf(
            AikenTopLevelSymbolKind.FUNCTION,
            AikenTopLevelSymbolKind.TYPE,
            AikenTopLevelSymbolKind.CONST,
            AikenTopLevelSymbolKind.CONSTRUCTOR
        )

    fun forElement(element: PsiElement): Array<Any> = forElement(element, null)

    fun forElement(
        element: PsiElement,
        caretOffsetOverride: Int? = null
    ): Array<Any> {
        val file = element.containingFile ?: return emptyArray()
        if (file.fileType != AikenFileType) return emptyArray()

        val text = file.text
        val offset = caretOffsetOverride?.coerceIn(0, text.length) ?: element.textRange.startOffset
        val candidateOffsets =
            linkedSetOf(
                offset.coerceIn(0, text.length),
                element.textRange.endOffset.coerceIn(0, text.length),
                (element.textRange.endOffset + 1).coerceIn(0, text.length)
            )
        if (candidateOffsets.any { AikenRecordCompletionSupport.isRecordFieldNameContext(text, it) }) return emptyArray()
        val allowBareTypes = !AikenCompletionContexts.isLikelyValueExpressionContext(text, offset)
        val useModel = AikenUseStatementParser.parseModel(text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val qualifier = findQualifier(text, offset)
        val seen = LinkedHashSet<String>()
        val variants = ArrayList<Any>()

        if (qualifier != null) {
            for (moduleTarget in useModel.resolveModuleTargets(qualifier)) {
                for (symbol in exportedSymbols(element, moduleTarget.modulePath)) {
                    addVariant(
                        element,
                        variants,
                        seen,
                        symbol,
                        symbol,
                        inferTopLevelKind(element, moduleTarget.modulePath, symbol),
                        2600.0,
                        moduleTarget.modulePath,
                        allowBareTypes
                    )
                }
            }
            return variants.toTypedArray()
        }

        val document = PsiDocumentManager.getInstance(element.project).getDocument(file)
        val caretOffset = offset
        for (binding in AikenLocalScopeAnalyzer.collectVisibleBindings(element)) {
            if (document != null && isInsideOwnBindingInitializer(text, binding.declarationOffset, binding.name, caretOffset)) {
                continue
            }
            addVariant(element, variants, seen, binding.name, binding.name, CompletionSymbolKind.IDENTIFIER, 2800.0, null, allowBareTypes)
        }

        for (importedName in useModel.importedNames()) {
            val kind =
                when (importedName.kind) {
                    AikenImportedNameKind.MODULE_ALIAS -> CompletionSymbolKind.IDENTIFIER
                    AikenImportedNameKind.ITEM,
                    AikenImportedNameKind.ITEM_ALIAS ->
                        inferTopLevelKind(element, importedName.statement.modulePath, importedName.sourceName)
                }
            addVariant(
                element,
                variants,
                seen,
                importedName.exposedName,
                importedName.sourceName,
                kind,
                2600.0,
                importedName.statement.modulePath,
                allowBareTypes
            )
        }

        for (statement in useModel.statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isBlank() || statement.items.isNotEmpty() || !statement.moduleAlias.isNullOrBlank()) continue
            val exposedModuleName = modulePath.substringAfterLast('/').trim()
            if (exposedModuleName.length < 2 || !seen.add(exposedModuleName)) continue
            variants +=
                com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(
                    com.intellij.codeInsight.lookup.LookupElementBuilder
                        .create(exposedModuleName)
                        .withIcon(com.intellij.icons.AllIcons.Nodes.Package)
                        .withTypeText("module", true),
                    2600.0
                )
        }

        for (entry in AikenTopLevelSymbolExtractor.extract(text)) {
            addVariant(element, variants, seen, entry.name, entry.name, mapTopLevelKind(entry), 2400.0, currentModulePath, allowBareTypes)
        }

        val prefix = currentIdentifierPrefix(element)
        if (prefix.isNotEmpty()) {
            for (lookup in unimportedExportsForPrefix(element, prefix, excludedNames = seen, allowBareTypes = allowBareTypes)) {
                if (seen.add(lookup.lookupString)) {
                    variants += lookup
                }
            }
        }

        return variants.toTypedArray()
    }

    fun unimportedExportsForPrefix(
        element: PsiElement,
        prefix: String,
        excludedNames: Set<String> = emptySet(),
        allowBareTypes: Boolean = !AikenCompletionContexts.isLikelyValueExpressionContext(element.containingFile?.text.orEmpty(), element.textRange.startOffset)
    ): List<LookupElement> =
        unimportedExportsMatching(
            element = element,
            nameMatches = { it.startsWith(prefix, ignoreCase = true) },
            excludedNames = excludedNames,
            allowBareTypes = allowBareTypes
        )

    fun unimportedModulesMatching(
        element: PsiElement,
        nameMatches: (String) -> Boolean
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        if (file.fileType != AikenFileType) return emptyList()

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val importedModuleNames =
            useModel.statements
                .mapNotNull { statement ->
                    val modulePath = statement.modulePath.trim()
                    if (modulePath.isBlank()) return@mapNotNull null
                    statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: modulePath.substringAfterLast('/')
                }
                .filter { it.isNotBlank() }
                .toSet()
        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()
        val result = ArrayList<LookupElement>()
        val seenModulePaths = LinkedHashSet<String>()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath.isBlank() || modulePath == currentModulePath || !seenModulePaths.add(modulePath)) continue
            val exposedModuleName = modulePath.substringAfterLast('/').trim()
            if (exposedModuleName.isBlank() || exposedModuleName in importedModuleNames) continue
            if (!nameMatches(exposedModuleName) && !nameMatches(modulePath)) continue
            result += createAutoImportedModuleLookup(modulePath, exposedModuleName)
        }

        return result
    }

    fun qualifiedVariants(
        element: PsiElement,
        qualifier: String,
        allowBareTypes: Boolean = !AikenCompletionContexts.isLikelyValueExpressionContext(
            element.containingFile?.text.orEmpty(),
            element.textRange.startOffset
        )
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        if (file.fileType != AikenFileType) return emptyList()

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val variants = ArrayList<Any>()

        for (moduleTarget in useModel.resolveModuleTargets(qualifier)) {
            for (symbol in exportedSymbols(element, moduleTarget.modulePath)) {
                addVariant(
                    element,
                    variants,
                    seen,
                    symbol,
                    symbol,
                    inferTopLevelKind(element, moduleTarget.modulePath, symbol),
                    2600.0,
                    moduleTarget.modulePath,
                    allowBareTypes
                )
            }
        }

        return variants.mapNotNull { it as? LookupElement }
    }

    fun unimportedExportsMatching(
        element: PsiElement,
        nameMatches: (String) -> Boolean,
        excludedNames: Set<String> = emptySet(),
        allowBareTypes: Boolean = !AikenCompletionContexts.isLikelyValueExpressionContext(element.containingFile?.text.orEmpty(), element.textRange.startOffset)
    ): List<LookupElement> {
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
                if (!nameMatches(entry.name)) continue
                if (entry.name in excludedNames || entry.name in importedNames || !seen.add(entry.name)) continue
                createAutoImportedExportLookup(modulePath, text, entry.name, mapTopLevelKind(entry), allowBareTypes)?.let(result::add)
            }
        }

        return result
    }

    private fun addVariant(
        anchor: PsiElement,
        variants: MutableList<Any>,
        seen: MutableSet<String>,
        lookupName: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        priority: Double,
        modulePath: String?,
        allowBareTypes: Boolean
    ) {
        if (lookupName.isBlank() || lookupName.length < 2 || !seen.add(lookupName)) return
        createVariantLookup(anchor, lookupName, symbolName, kind, priority, modulePath, allowBareTypes)?.let(variants::add)
    }

    private fun createVariantLookup(
        anchor: PsiElement,
        lookupName: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        priority: Double,
        modulePath: String?,
        allowBareTypes: Boolean
    ): LookupElement? {
        if (kind != CompletionSymbolKind.TYPE) {
            return CompletionItemFactory.create(lookupName, kind, priority)
        }

        val constructible =
            AikenConstructibleCompletionSupport.findVisibleConstructible(anchor, symbolName, modulePath)
        if (constructible == null) {
            return if (allowBareTypes) CompletionItemFactory.create(lookupName, kind, priority) else null
        }

        return AikenConstructibleCompletionSupport.createVisibleLookup(
            constructible = constructible,
            priority = priority,
            typeText = "type",
            lookupName = lookupName
        )
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

    private fun isInsideOwnBindingInitializer(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        caretOffset: Int
    ): Boolean {
        if (caretOffset <= declarationOffset || declarationOffset < 0 || declarationOffset >= text.length) return false

        val nameEnd = declarationOffset + bindingName.length
        if (nameEnd > text.length) return false

        var index = skipWhitespace(text, nameEnd)
        if (index >= text.length) return false

        if (text[index] == ':') {
            index++
            var angleDepth = 0
            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            while (index < text.length) {
                when (text[index]) {
                    '<' -> angleDepth++
                    '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                    '(' -> parenDepth++
                    ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    '[' -> bracketDepth++
                    ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    '{' -> braceDepth++
                    '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    '=' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                }
                index++
            }
        }

        index = skipWhitespace(text, index)
        if (index >= text.length || text[index] != '=') return false
        index++
        index = skipWhitespace(text, index)
        if (caretOffset <= index) return false

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var scan = index

        while (scan < caretOffset && scan < text.length) {
            val ch = text[scan]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                scan++
                continue
            }

            if (inString) {
                if (ch == '\\' && scan + 1 < text.length) {
                    scan += 2
                    continue
                }
                if (ch == '"') inString = false
                scan++
                continue
            }

            if (ch == '/' && scan + 1 < caretOffset && text[scan + 1] == '/') {
                inLineComment = true
                scan += 2
                continue
            }

            if (ch == '"') {
                inString = true
                scan++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return false
            }
            scan++
        }

        return true
    }

    private fun skipWhitespace(text: String, startIndex: Int): Int {
        var index = startIndex
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun exportedSymbols(anchor: PsiElement, modulePath: String): List<String> {
        val project = anchor.project
        val names = LinkedHashSet<String>()

        for (moduleFile in AikenModuleFiles.findFilesForModulePath(anchor.containingFile?.virtualFile, modulePath)) {
            val text = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            names += AikenPublicExportExtractor.extract(text)
        }

        if (names.isNotEmpty()) return names.toList()
        if (DumbService.isDumb(project)) return emptyList()

        return try {
            val scope = AikenSearchScopes.forElement(anchor)
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
        moduleText: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        allowBareTypes: Boolean
    ): LookupElement? {
        if (kind == CompletionSymbolKind.TYPE) {
            val constructible =
                AikenConstructibleCompletionSupport.findConstructibleInModuleText(modulePath, moduleText, symbolName)
            if (constructible != null) {
                return AikenConstructibleCompletionSupport.createAutoImportedLookup(
                    constructible = constructible,
                    priority = UNIMPORTED_EXPORTED_SYMBOL_PRIORITY,
                    typeText = "type"
                )
            }
            if (!allowBareTypes) return null
        }

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

    private fun createAutoImportedModuleLookup(
        modulePath: String,
        exposedModuleName: String
    ): LookupElement {
        val builder =
            com.intellij.codeInsight.lookup.LookupElementBuilder
                .create(exposedModuleName)
                .withIcon(com.intellij.icons.AllIcons.Nodes.Package)
                .withTypeText("module", true)
                .withTailText(" from $modulePath", true)
                .withInsertHandler { insertionContext, _ ->
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, "$exposedModuleName.")
                    val insertedRangeMarker =
                        insertionContext.document.createRangeMarker(
                            insertedOffset,
                            insertedOffset + exposedModuleName.length + 1
                        ).apply {
                            isGreedyToLeft = false
                            isGreedyToRight = true
                        }
                    insertionContext.commitDocument()
                    val previousLaterRunnable = insertionContext.laterRunnable
                    insertionContext.setLaterRunnable {
                        try {
                            previousLaterRunnable?.run()
                            WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                                insertStandaloneModuleUseImport(
                                    insertionContext.document.charsSequence.toString(),
                                    insertionContext.document,
                                    modulePath
                                )
                                insertionContext.commitDocument()
                            }
                            val caretOffset =
                                if (insertedRangeMarker.isValid) {
                                    insertedRangeMarker.endOffset
                                } else {
                                    insertionContext.editor.caretModel.offset
                                }
                            insertionContext.editor.caretModel.moveToOffset(caretOffset)
                            AutoPopupController.getInstance(insertionContext.project)
                                .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
                        } finally {
                            insertedRangeMarker.dispose()
                        }
                    }
                }
        return com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(builder, UNIMPORTED_MODULE_PRIORITY)
    }

    private fun replaceCurrentIdentifierPrefix(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        replacementText: String
    ): Int {
        val document = insertionContext.document
        val chars = document.charsSequence
        var replaceStart = insertionContext.startOffset.coerceIn(0, chars.length)
        while (replaceStart > 0 && (chars[replaceStart - 1].isLetterOrDigit() || chars[replaceStart - 1] == '_')) {
            replaceStart--
        }
        document.replaceString(replaceStart, insertionContext.tailOffset, replacementText)
        return replaceStart
    }

    private fun insertStandaloneUseImport(
        document: com.intellij.openapi.editor.Document,
        modulePath: String,
        symbolName: String
    ) {
        document.insertString(0, "use $modulePath.{$symbolName}\n")
    }

    private fun insertStandaloneModuleUseImport(
        currentText: String,
        document: com.intellij.openapi.editor.Document,
        modulePath: String
    ) {
        val alreadyImported =
            AikenUseStatementParser.parseModel(currentText)
                .statements
                .any { statement ->
                    statement.modulePath.trim() == modulePath &&
                        statement.items.isEmpty() &&
                        statement.moduleAlias.isNullOrBlank()
                }
        if (alreadyImported) return
        document.insertString(0, "use $modulePath\n")
    }
}
