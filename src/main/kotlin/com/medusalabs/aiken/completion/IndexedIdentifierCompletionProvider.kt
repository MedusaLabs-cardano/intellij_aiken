package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.tree.IElementType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import com.medusalabs.aiken.index.IdentifierKind

class IndexedIdentifierCompletionProvider(
    private val indexId: ID<String, Int>,
    private val stopTokenTypes: Set<IElementType>,
    private val minPrefixLength: Int = 2,
    private val maxResults: Int = 500,
    private val basePriority: Double = 1000.0
) : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val elementType = parameters.position.node.elementType
        if (stopTokenTypes.contains(elementType)) return

        val project = parameters.editor.project ?: return
        if (DumbService.isDumb(project)) return

        val prefixMatcher = result.prefixMatcher
        val prefix = prefixMatcher.prefix
        if (prefix.length < minPrefixLength) return

        val seen = HashSet<String>()
        var added = 0
        try {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.allScope(project)
            index.processAllKeys(
                indexId,
                Processor { key ->
                    if (!prefixMatcher.prefixMatches(key)) return@Processor true
                    if (seen.add(key)) {
                        val kindMask = resolveKindMask(index, key, scope)
                        val kind = dominantKind(kindMask) ?: return@Processor true
                        val priority = basePriority + kindPriority(kind)
                        result.addElement(CompletionItemFactory.create(key, kind, priority))
                        added++
                    }
                    added < maxResults
                },
                project
            )
        } catch (_: IndexNotReadyException) {
            // Fallback providers (e.g. current-file scan) will still run.
        }
    }

    private fun resolveKindMask(
        index: FileBasedIndex,
        key: String,
        scope: GlobalSearchScope
    ): Int =
        try {
            val values = index.getValues(indexId, key, scope)
            var mask = 0
            for (value in values) mask = mask or value
            mask
        } catch (_: IndexNotReadyException) {
            0
        }

    private fun dominantKind(kindMask: Int): CompletionSymbolKind? {
        val callableMask =
            kindMask and (
                IdentifierKind.TYPE or
                    IdentifierKind.FUNCTION or
                    IdentifierKind.FIELD or
                    IdentifierKind.IDENTIFIER
                )
        if (callableMask == 0) return null

        return when {
            callableMask and IdentifierKind.TYPE != 0 -> CompletionSymbolKind.TYPE
            callableMask and IdentifierKind.FUNCTION != 0 -> CompletionSymbolKind.FUNCTION
            callableMask and IdentifierKind.FIELD != 0 -> CompletionSymbolKind.FIELD
            else -> CompletionSymbolKind.IDENTIFIER
        }
    }

    private fun kindPriority(kind: CompletionSymbolKind): Double =
        when (kind) {
            CompletionSymbolKind.TYPE -> 80.0
            CompletionSymbolKind.FUNCTION -> 60.0
            CompletionSymbolKind.FIELD -> 40.0
            CompletionSymbolKind.IDENTIFIER -> 0.0
            CompletionSymbolKind.KEYWORD -> 0.0
        }
}
