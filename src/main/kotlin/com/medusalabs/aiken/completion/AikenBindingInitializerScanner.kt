package com.medusalabs.aiken.completion

import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

internal object AikenBindingInitializerScanner {
    data class BindingInitializer(
        val operatorText: String,
        val operatorStart: Int,
        val expressionStart: Int
    )

    fun startsBindingPattern(
        text: String,
        keyword: String,
        keywordEnd: Int
    ): Boolean =
        when (keyword) {
            "let" -> true
            "expect" -> hasTopLevelAssignmentBeforeStatementBoundary(text, keywordEnd)
            else -> false
        }

    fun bindingOperatorAt(
        text: CharSequence,
        offset: Int
    ): String? =
        when {
            offset in 0 until text.length && text[offset] == '=' -> "="
            offset >= 0 && offset + 1 < text.length && text[offset] == '<' && text[offset + 1] == '-' -> "<-"
            else -> null
        }

    fun findInitializerExpressionStart(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): Int? = findInitializer(text, declarationOffset, bindingName)?.expressionStart

    fun findInitializer(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): BindingInitializer? {
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
        val operatorText =
            when {
                index < text.length && text[index] == '=' -> "="
                index + 1 < text.length && text[index] == '<' && text[index + 1] == '-' -> "<-"
                else -> return null
            }
        val operatorEnd = index + operatorText.length
        return BindingInitializer(
            operatorText = operatorText,
            operatorStart = index,
            expressionStart = skipWhitespaceForward(text, operatorEnd)
        )
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
        val safeCaretOffset = caretOffset.coerceIn(0, text.length)

        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var collectingPattern = false
        var patternStart = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (!collectingPattern) {
                if (tokenType == AikenTokenTypes.KEYWORD && startsBindingPattern(text, tokenText, lexer.tokenEnd)) {
                    collectingPattern = true
                    patternStart = lexer.tokenEnd
                }
                lexer.advance()
                continue
            }

            if (safeCaretOffset in patternStart..lexer.tokenStart &&
                !hasTopLevelStatementBoundaryBetween(text, patternStart, safeCaretOffset)
            ) {
                return true
            }

            if (tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.WHITESPACE || tokenType == AikenTokenTypes.COMMENT) {
                lexer.advance()
                continue
            }

            val bindingOperator = if (tokenType == AikenTokenTypes.OPERATOR) bindingOperatorAt(text, lexer.tokenStart) else null
            if (bindingOperator != null) {
                val patternEnd = lexer.tokenStart
                val declarationInsidePattern = declarationOffset in patternStart until patternEnd
                val caretInsidePattern = caretOffset in declarationOffset..patternEnd
                if (declarationInsidePattern && caretInsidePattern) return true

                collectingPattern = false
                patternStart = -1
                if (bindingOperator == "<-") {
                    lexer.start(text, (lexer.tokenStart + 2).coerceAtMost(text.length), text.length, 0)
                } else {
                    lexer.advance()
                }
                continue
            }

            lexer.advance()
        }

        return false
    }

    fun isInsideBindingPatternDeclaration(
        text: String,
        caretOffset: Int
    ): Boolean {
        val safeOffset = caretOffset.coerceIn(0, text.length)
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var collectingPattern = false
        var patternStart = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (!collectingPattern) {
                if (tokenType == AikenTokenTypes.KEYWORD && startsBindingPattern(text, tokenText, lexer.tokenEnd)) {
                    collectingPattern = true
                    patternStart = lexer.tokenEnd
                }
                lexer.advance()
                continue
            }

            if (safeOffset in patternStart..lexer.tokenStart &&
                !hasTopLevelStatementBoundaryBetween(text, patternStart, safeOffset)
            ) {
                return true
            }

            if (tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.WHITESPACE || tokenType == AikenTokenTypes.COMMENT) {
                lexer.advance()
                continue
            }

            val bindingOperator = if (tokenType == AikenTokenTypes.OPERATOR) bindingOperatorAt(text, lexer.tokenStart) else null
            if (bindingOperator != null) {
                val patternEnd = lexer.tokenStart
                if (safeOffset in patternStart..patternEnd) return true

                collectingPattern = false
                patternStart = -1
                if (bindingOperator == "<-") {
                    lexer.start(text, (lexer.tokenStart + 2).coerceAtMost(text.length), text.length, 0)
                } else {
                    lexer.advance()
                }
                continue
            }

            lexer.advance()
        }

        return false
    }

    private fun hasTopLevelStatementBoundaryBetween(
        text: String,
        start: Int,
        endExclusive: Int
    ): Boolean {
        var index = start.coerceIn(0, text.length)
        val end = endExclusive.coerceIn(index, text.length)
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false

        while (index < end) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return true
                }
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < end) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < end && text[index + 1] == '/') {
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
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r', ';' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return true
                }
            }

            index++
        }

        return false
    }

    private fun skipWhitespaceForward(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun hasTopLevelAssignmentBeforeStatementBoundary(
        text: String,
        start: Int
    ): Boolean {
        var index = start.coerceIn(0, text.length)
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return false
                }
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
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r', ';' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return false
                }
                '=' -> if (
                    angleDepth == 0 &&
                    parenDepth == 0 &&
                    bracketDepth == 0 &&
                    braceDepth == 0 &&
                    !isComparisonOperatorChar(text.getOrNull(index - 1)) &&
                    text.getOrNull(index + 1) != '='
                ) {
                    return true
                }
            }

            index++
        }

        return false
    }

    private fun isComparisonOperatorChar(ch: Char?): Boolean = ch == '=' || ch == '!' || ch == '<' || ch == '>'
}
