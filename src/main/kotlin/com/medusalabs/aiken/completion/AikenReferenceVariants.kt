package com.medusalabs.aiken.completion

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenImportedNameKind
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_EXPORT_INDEX_NAME
import com.medusalabs.aiken.index.AikenExportIndex
import com.medusalabs.aiken.index.AikenTopLevelSymbolEntry
import com.medusalabs.aiken.index.AikenTopLevelSymbolExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.decodeAikenExportIndexValue
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.navigation.AikenTopLevelSymbolLookup
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

object AikenReferenceVariants {
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

        return variants.toTypedArray()
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
}
