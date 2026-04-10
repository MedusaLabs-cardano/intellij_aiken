package com.medusalabs.aiken.completion

import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

internal object AikenBindingInitializerScanner {
    fun findInitializerExpressionStart(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): Int? {
        val nameEnd = declarationOffset + bindingName.length
        if (declarationOffset < 0 || nameEnd > text.length) return null

        var index = skipWhitespaceForward(text, nameEnd)
        if (index >= text.length) return null

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

        index = skipWhitespaceForward(text, index)
        if (index >= text.length || text[index] != '=') return null
        index++
        return skipWhitespaceForward(text, index)
    }

    fun isInsideOwnInitializer(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        caretOffset: Int
    ): Boolean {
        if (caretOffset <= declarationOffset) return false
        val expressionStart = findInitializerExpressionStart(text, declarationOffset, bindingName) ?: return false
        if (caretOffset <= expressionStart) return false

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var scan = expressionStart

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

    fun isInsideOwnPatternDeclaration(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        caretOffset: Int
    ): Boolean {
        if (caretOffset < declarationOffset) return false
        if (declarationOffset < 0 || declarationOffset + bindingName.length > text.length) return false
        if (text.substring(declarationOffset, declarationOffset + bindingName.length) != bindingName) return false

        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var collectingPattern = false
        var patternStart = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (!collectingPattern) {
                if (tokenType == AikenTokenTypes.KEYWORD && (tokenText == "let" || tokenText == "expect")) {
                    collectingPattern = true
                    patternStart = lexer.tokenEnd
                }
                lexer.advance()
                continue
            }

            if (tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.WHITESPACE || tokenType == AikenTokenTypes.COMMENT) {
                lexer.advance()
                continue
            }

            if (tokenType == AikenTokenTypes.OPERATOR && tokenText == "=") {
                val patternEnd = lexer.tokenStart
                val declarationInsidePattern = declarationOffset in patternStart until patternEnd
                val caretInsidePattern = caretOffset in declarationOffset..patternEnd
                if (declarationInsidePattern && caretInsidePattern) return true

                collectingPattern = false
                patternStart = -1
                lexer.advance()
                continue
            }

            lexer.advance()
        }

        return false
    }

    private fun skipWhitespaceForward(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }
}
