package com.medusalabs.aiken.completion

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

internal object AikenParameterBindingScanner {
    data class BindingContext(
        val name: String,
        val declarationOffset: Int,
        val scope: TextRange,
        val patternText: String,
        val explicitTypeText: String?,
        val viaExpressionText: String?,
        val viaExpressionStart: Int?
    )

    fun collectBindings(text: String): List<BindingContext> {
        val result = ArrayList<BindingContext>()
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (tokenType != AikenTokenTypes.KEYWORD ||
                (tokenText != "fn" && tokenText != "test" && tokenText != "bench" && tokenText != "validator")
            ) {
                lexer.advance()
                continue
            }

            val paramsOpen = findHeadParamsOpenParen(text, lexer.tokenEnd)
            if (paramsOpen == null) {
                lexer.advance()
                continue
            }
            val paramsClose = AikenSyntaxText.findMatchingDelimiter(text, paramsOpen, '(', ')')
            if (paramsClose == null) {
                lexer.advance()
                continue
            }
            val bodyOpen = findHeadBodyOpenBrace(text, paramsClose + 1)
            if (bodyOpen == null) {
                lexer.advance()
                continue
            }
            val bodyClose = AikenSyntaxText.findMatchingDelimiter(text, bodyOpen, '{', '}')
            if (bodyClose == null) {
                lexer.advance()
                continue
            }
            val scope = TextRange(bodyOpen, bodyClose)

            for (range in AikenTopLevelText.splitRanges(text, ',', paramsOpen + 1, paramsClose, trackAngles = true)) {
                val segment = parseParameterSegment(text, range.startOffset, range.endOffset) ?: continue
                for ((name, declarationOffset) in AikenPatternBindingText.extractBindings(segment.patternText, segment.patternStart)) {
                    result +=
                        BindingContext(
                            name = name,
                            declarationOffset = declarationOffset,
                            scope = scope,
                            patternText = segment.patternText,
                            explicitTypeText = segment.explicitTypeText,
                            viaExpressionText = segment.viaExpressionText,
                            viaExpressionStart = segment.viaExpressionStart
                        )
                }
            }

            lexer.advance()
        }

        return result
    }

    fun findBindingContext(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): BindingContext? =
        collectBindings(text).firstOrNull { binding ->
            binding.name == bindingName && binding.declarationOffset == declarationOffset
        }

    private data class ParameterSegment(
        val patternText: String,
        val patternStart: Int,
        val explicitTypeText: String?,
        val viaExpressionText: String?,
        val viaExpressionStart: Int?
    )

    private fun parseParameterSegment(
        text: String,
        startOffset: Int,
        endOffset: Int
    ): ParameterSegment? {
        if (startOffset >= endOffset) return null
        val rawSegment = text.substring(startOffset, endOffset)
        val (trimmedSegment, trimmedStart) = trimWithOffset(rawSegment, startOffset)
        if (trimmedSegment.isBlank()) return null

        val viaRange = findTopLevelKeyword(trimmedSegment, "via")
        val beforeVia = viaRange?.let { trimmedSegment.substring(0, it.first).trimEnd() } ?: trimmedSegment
        val beforeViaStart = trimmedStart
        val viaExpressionText = viaRange?.let { trimmedSegment.substring(it.last + 1).trim() }?.takeIf { it.isNotBlank() }
        val viaExpressionStart =
            viaRange?.let {
                val rawStart = trimmedSegment.indexOfFirstAfter(it.last + 1) { ch -> !ch.isWhitespace() }
                if (rawStart >= 0) trimmedStart + rawStart else null
            }

        val colonIndex = AikenTopLevelText.indexOf(beforeVia, ':', trackAngles = true)
        val patternText =
            if (colonIndex >= 0) {
                beforeVia.substring(0, colonIndex).trim()
            } else {
                beforeVia.trim()
            }
        if (patternText.isBlank()) return null

        val patternStart = beforeViaStart + beforeVia.indexOf(patternText)
        val explicitTypeText =
            if (colonIndex >= 0) {
                beforeVia.substring(colonIndex + 1).trim().takeIf { it.isNotBlank() }
            } else {
                null
            }

        return ParameterSegment(
            patternText = patternText,
            patternStart = patternStart,
            explicitTypeText = explicitTypeText,
            viaExpressionText = viaExpressionText,
            viaExpressionStart = viaExpressionStart
        )
    }

    private fun findHeadParamsOpenParen(
        text: String,
        afterKeywordOffset: Int
    ): Int? {
        val lexer = AikenLexing.createLexer()
        lexer.start(text, afterKeywordOffset.coerceIn(0, text.length), text.length, 0)

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            when {
                tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.WHITESPACE || tokenType == AikenTokenTypes.COMMENT -> {
                    lexer.advance()
                    continue
                }
                tokenType == AikenTokenTypes.LPAREN -> return lexer.tokenStart
                tokenType == AikenTokenTypes.LBRACE -> return null
                tokenType == AikenTokenTypes.OPERATOR && tokenText == "->" -> return null
                else -> lexer.advance()
            }
        }

        return null
    }

    private fun findHeadBodyOpenBrace(
        text: String,
        startOffset: Int
    ): Int? {
        var index = startOffset.coerceIn(0, text.length)
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
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
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        return index
                    }
                    braceDepth++
                }
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            index++
        }

        return null
    }

    private fun findTopLevelKeyword(
        text: String,
        keyword: String
    ): IntRange? {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.substring(lexer.tokenStart, lexer.tokenEnd)
            if (tokenType == AikenTokenTypes.KEYWORD &&
                tokenText == keyword &&
                angleDepth == 0 &&
                parenDepth == 0 &&
                bracketDepth == 0 &&
                braceDepth == 0
            ) {
                return lexer.tokenStart..(lexer.tokenEnd - 1)
            }

            when (tokenText) {
                "<" -> angleDepth++
                ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                "(" -> parenDepth++
                ")" -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                "[" -> bracketDepth++
                "]" -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                "{" -> braceDepth++
                "}" -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            lexer.advance()
        }

        return null
    }

    private fun trimWithOffset(
        text: String,
        absoluteStartOffset: Int
    ): Pair<String, Int> {
        var start = 0
        var end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return text.substring(start, end) to (absoluteStartOffset + start)
    }

    private inline fun String.indexOfFirstAfter(
        startIndex: Int,
        predicate: (Char) -> Boolean
    ): Int {
        var index = startIndex.coerceAtLeast(0)
        while (index < length) {
            if (predicate(this[index])) return index
            index++
        }
        return -1
    }
}
