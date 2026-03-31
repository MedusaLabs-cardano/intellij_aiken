package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext

class KeywordCompletionProvider(
    keywords: Collection<String>,
    private val stopTokenTypes: Set<IElementType>,
    private val priority: Double = 4000.0
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
        if (AikenCompletionContexts.insideListLiteralContext(text, offset)) return
        if (AikenArgumentCompletionSupport.hasPipeContext(text, offset)) return

        val keywordsToSuggest =
            if (AikenCompletionContexts.isLikelyValueExpressionContext(text, offset)) {
                distinctKeywords.filter { it in expressionKeywords }
            } else {
                distinctKeywords
            }

        for (keyword in keywordsToSuggest) {
            result.addElement(CompletionItemFactory.create(keyword, CompletionSymbolKind.KEYWORD, priority))
        }
    }
}
