package com.medusalabs.aiken.completion

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

internal object AikenFunctionBodyScanner {
    private val callableDeclarationKeywords = setOf("fn", "test", "bench")

    data class CallableBody(
        val declarationOffset: Int,
        val bodyRange: TextRange
    )

    fun findNamedCallableBody(text: CharSequence, callableName: String): CallableBody? {
        if (callableName.isBlank()) return null

        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var expectingCallableName = false
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            if (tokenType == AikenTokenTypes.KEYWORD) {
                val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                expectingCallableName = word in callableDeclarationKeywords
                lexer.advance()
                continue
            }

            if (expectingCallableName) {
                when {
                    tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.COMMENT -> {}
                    tokenType == AikenTokenTypes.IDENTIFIER || tokenType == AikenTokenTypes.FUNCTION -> {
                        val declarationOffset = lexer.tokenStart
                        val nameEnd = lexer.tokenEnd
                        val name = text.subSequence(declarationOffset, nameEnd).toString()
                        expectingCallableName = false
                        if (name == callableName) {
                            val bodyRange = findCallableBodyRange(text, nameEnd) ?: return null
                            return CallableBody(declarationOffset = declarationOffset, bodyRange = bodyRange)
                        }
                    }
                    else -> expectingCallableName = false
                }
                lexer.advance()
                continue
            }

            lexer.advance()
        }

        return null
    }

    private fun findCallableBodyRange(text: CharSequence, nameEnd: Int): TextRange? {
        var index = skipWhitespace(text, nameEnd)

        if (index < text.length && text[index] == '<') {
            index = skipAngleBrackets(text, index) ?: return null
            index = skipWhitespace(text, index)
        }

        if (index >= text.length || text[index] != '(') return null
        val closeParen = AikenSyntaxText.findMatchingDelimiter(text, index, '(', ')') ?: return null

        val bodyStart = findBodyStart(text, closeParen + 1) ?: return null
        val bodyEnd = AikenSyntaxText.findMatchingDelimiter(text, bodyStart, '{', '}') ?: return null
        return TextRange(bodyStart, bodyEnd + 1)
    }

    private fun findBodyStart(text: CharSequence, start: Int): Int? {
        var index = start.coerceIn(0, text.length)
        var parenDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var inString = false
        var inLineComment = false

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '=' -> if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) return null
                '{' -> if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) return index
            }
            index++
        }

        return null
    }

    private fun skipWhitespace(text: CharSequence, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun skipAngleBrackets(text: CharSequence, start: Int): Int? {
        if (start !in 0 until text.length || text[start] != '<') return null

        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }

        return null
    }

}
