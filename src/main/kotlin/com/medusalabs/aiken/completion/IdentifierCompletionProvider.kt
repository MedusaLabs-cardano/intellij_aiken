package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lexer.Lexer
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext

class IdentifierCompletionProvider(
    private val lexerFactory: () -> Lexer,
    private val identifierTokenTypes: Set<IElementType>,
    private val stopTokenTypes: Set<IElementType>
) : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val elementType = parameters.position.node.elementType
        if (stopTokenTypes.contains(elementType)) return

        val text = parameters.originalFile.text
        val lexer = lexerFactory()
        lexer.start(text)

        val prefixMatcher = result.prefixMatcher
        val seen = HashSet<String>()
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            if (tokenType != null && identifierTokenTypes.contains(tokenType)) {
                val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                if (word.length >= 2 && seen.add(word) && prefixMatcher.prefixMatches(word)) {
                    result.addElement(LookupElementBuilder.create(word))
                }
            }
            lexer.advance()
        }
    }
}

