package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext

class KeywordCompletionProvider(
    keywords: Collection<String>,
    private val stopTokenTypes: Set<IElementType>,
    private val priority: Double = 500.0
) : CompletionProvider<CompletionParameters>() {
    private val distinctKeywords: List<String> = keywords.toSortedSet().toList()

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val elementType = parameters.position.node.elementType
        if (stopTokenTypes.contains(elementType)) return

        for (keyword in distinctKeywords) {
            result.addElement(CompletionItemFactory.create(keyword, CompletionSymbolKind.KEYWORD, priority))
        }
    }
}
