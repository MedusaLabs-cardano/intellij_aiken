package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID

class IndexedIdentifierCompletionProvider(
    private val indexId: ID<String, Int>,
    private val stopTokenTypes: Set<IElementType>,
    private val minPrefixLength: Int = 2,
    private val maxResults: Int = 500
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
            FileBasedIndex.getInstance().processAllKeys(
                indexId,
                Processor { key ->
                    if (!prefixMatcher.prefixMatches(key)) return@Processor true
                    if (seen.add(key)) {
                        result.addElement(LookupElementBuilder.create(key))
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
}
