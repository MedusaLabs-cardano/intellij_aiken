package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext

class KeywordCompletionProvider(
    keywords: Collection<String>,
    private val stopTokenTypes: Set<IElementType>,
    private val priority: Double? = 4000.0,
    private val visibilityResolver: ((CompletionParameters) -> AikenKeywordVisibility)? = null
) : CompletionProvider<CompletionParameters>() {
    private val distinctKeywords: List<String> = keywords.toSortedSet().toList()
    private val expressionKeywords: Set<String> = setOf("if", "when", "fn", "todo", "fail", "True", "False")

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val elementType = parameters.position.node.elementType
        if (stopTokenTypes.contains(elementType)) return

        val text = parameters.originalFile.text
        val offset = parameters.offset.coerceIn(0, text.length)
        val keywordVisibility =
            visibilityResolver?.invoke(parameters)
                ?: defaultKeywordVisibility(text, offset)
        if (keywordVisibility == AikenKeywordVisibility.NONE) return

        val keywordsToSuggest =
            when (keywordVisibility) {
                AikenKeywordVisibility.ALL -> distinctKeywords
                AikenKeywordVisibility.EXPRESSION_ONLY -> distinctKeywords.filter { it in expressionKeywords }
                AikenKeywordVisibility.NONE -> emptyList()
            }
        val rankedResult = AikenCompletionSorting.withOrdinarySorter(parameters, result)

        for (keyword in keywordsToSuggest) {
            rankedResult.addElement(
                if (priority != null) {
                    CompletionItemFactory.create(keyword, CompletionSymbolKind.KEYWORD, priority)
                } else {
                    CompletionItemFactory.create(keyword, CompletionSymbolKind.KEYWORD)
                }
            )
        }
    }

    private fun defaultKeywordVisibility(
        text: String,
        offset: Int
    ): AikenKeywordVisibility {
        if (AikenCompletionContexts.insideListLiteralContext(text, offset)) return AikenKeywordVisibility.NONE
        if (AikenArgumentCompletionSupport.hasPipeContext(text, offset)) return AikenKeywordVisibility.NONE
        return if (AikenCompletionContexts.isLikelyValueExpressionContext(text, offset)) {
            AikenKeywordVisibility.EXPRESSION_ONLY
        } else {
            AikenKeywordVisibility.ALL
        }
    }
}
