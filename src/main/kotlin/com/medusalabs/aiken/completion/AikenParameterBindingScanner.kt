package com.medusalabs.aiken.completion

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

internal object AikenParameterBindingScanner {
    data class ParameterRegion(
        val paramsOpen: Int,
        val paramsClose: Int,
        val scope: TextRange
    )

    data class BindingContext(
        val name: String,
        val declarationOffset: Int,
        val scope: TextRange,
        val patternText: String,
        val explicitTypeText: String?,
        val viaExpressionText: String?,
        val viaExpressionStart: Int?
    )

    fun collectParameterRegions(text: String): List<ParameterRegion> {
        val result = ArrayList<ParameterRegion>()
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

            if (tokenText == "validator") {
                val parsedValidator = collectValidatorParameterRegions(text, lexer.tokenStart)
                if (parsedValidator != null) {
                    result += parsedValidator.regions
                    lexer.start(text, parsedValidator.resumeOffset.coerceAtMost(text.length), text.length, 0)
                    continue
                }
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

            result += ParameterRegion(paramsOpen = paramsOpen, paramsClose = paramsClose, scope = TextRange(bodyOpen, bodyClose))
            collectLambdaParameterRegions(text, bodyOpen + 1, bodyClose, result)
            lexer.start(text, (bodyClose + 1).coerceAtMost(text.length), text.length, 0)
        }

        return result
    }

    fun collectBindings(text: String): List<BindingContext> {
        val result = ArrayList<BindingContext>()
        for (region in collectParameterRegions(text)) {
            for (range in AikenTopLevelText.splitRanges(text, ',', region.paramsOpen + 1, region.paramsClose, trackAngles = true)) {
                val segment = parseParameterSegment(text, range.startOffset, range.endOffset) ?: continue
                for ((name, declarationOffset) in AikenPatternBindingText.extractBindings(segment.patternText, segment.patternStart)) {
                    result +=
                        BindingContext(
                            name = name,
                            declarationOffset = declarationOffset,
                            scope = region.scope,
                            patternText = segment.patternText,
                            explicitTypeText = segment.explicitTypeText,
                            viaExpressionText = segment.viaExpressionText,
                            viaExpressionStart = segment.viaExpressionStart
                        )
                }
            }
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

    fun isInsideParameterPattern(
        text: String,
        offset: Int
    ): Boolean {
        val safeOffset = offset.coerceIn(0, text.length)
        for (region in collectParameterRegions(text)) {
            if (safeOffset in (region.paramsOpen + 1) until region.paramsClose) {
                for (range in AikenTopLevelText.splitRanges(text, ',', region.paramsOpen + 1, region.paramsClose, trackAngles = true)) {
                    val segment = parseParameterSegment(text, range.startOffset, range.endOffset) ?: continue
                    val patternEnd = segment.patternStart + segment.patternText.length
                    if (safeOffset in segment.patternStart..patternEnd) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private data class ParsedValidatorParameterRegions(
        val regions: List<ParameterRegion>,
        val resumeOffset: Int
    )

    private fun collectValidatorParameterRegions(
        text: String,
        validatorKeywordStart: Int
    ): ParsedValidatorParameterRegions? {
        var index = skipWhitespace(text, validatorKeywordStart + "validator".length)
        if (index >= text.length || !AikenSyntaxText.isIdentifierChar(text[index])) return null

        while (index < text.length && AikenSyntaxText.isIdentifierChar(text[index])) index++
        index = skipWhitespace(text, index)

        val regions = ArrayList<ParameterRegion>()
        if (index < text.length && text[index] == '(') {
            val validatorParamsOpen = index
            val validatorParamsClose = AikenSyntaxText.findMatchingDelimiter(text, validatorParamsOpen, '(', ')') ?: return null
            index = validatorParamsClose + 1
            index = skipWhitespace(text, index)
            if (index >= text.length || text[index] != '{') return null
            val validatorBodyOpen = index
            val validatorBodyClose = AikenSyntaxText.findMatchingDelimiter(text, validatorBodyOpen, '{', '}') ?: return null
            regions +=
                ParameterRegion(
                    paramsOpen = validatorParamsOpen,
                    paramsClose = validatorParamsClose,
                    scope = TextRange(validatorBodyOpen, validatorBodyClose)
                )
            collectValidatorHandlerRegions(text, validatorBodyOpen, validatorBodyClose, regions)
            collectLambdaParameterRegions(text, validatorBodyOpen + 1, validatorBodyClose, regions)
            return ParsedValidatorParameterRegions(regions, validatorBodyClose + 1)
        }

        if (index >= text.length || text[index] != '{') return null
        val validatorBodyOpen = index
        val validatorBodyClose = AikenSyntaxText.findMatchingDelimiter(text, validatorBodyOpen, '{', '}') ?: return null
        collectValidatorHandlerRegions(text, validatorBodyOpen, validatorBodyClose, regions)
        collectLambdaParameterRegions(text, validatorBodyOpen + 1, validatorBodyClose, regions)
        return ParsedValidatorParameterRegions(regions, validatorBodyClose + 1)
    }

    private fun collectValidatorHandlerRegions(
        text: String,
        bodyOpen: Int,
        bodyClose: Int,
        sink: MutableList<ParameterRegion>
    ) {
        val bodyLexer = AikenLexing.createLexer()
        bodyLexer.start(text, bodyOpen + 1, bodyClose, 0)
        var relativeBraceDepth = 0

        while (bodyLexer.tokenType != null) {
            val tokenType = bodyLexer.tokenType
            val tokenStart = bodyLexer.tokenStart
            val tokenEnd = bodyLexer.tokenEnd
            val tokenText = text.subSequence(tokenStart, tokenEnd).toString()

            val isHandlerName =
                tokenType == AikenTokenTypes.IDENTIFIER ||
                    tokenType == AikenTokenTypes.FUNCTION ||
                    (tokenType == AikenTokenTypes.KEYWORD && tokenText == "else")

            if (relativeBraceDepth == 0 && isHandlerName && isAtLogicalLineStart(text, tokenStart)) {
                var handlerIndex = skipWhitespace(text, tokenEnd)
                if (handlerIndex < bodyClose && text[handlerIndex] == '(') {
                    val handlerParamsOpen = handlerIndex
                    val handlerParamsClose = AikenSyntaxText.findMatchingDelimiter(text, handlerParamsOpen, '(', ')')
                    if (handlerParamsClose != null && handlerParamsClose < bodyClose) {
                        val handlerBodyOpen = findHeadBodyOpenBrace(text, handlerParamsClose + 1)
                        val handlerBodyClose =
                            handlerBodyOpen
                                ?.takeIf { it < bodyClose }
                                ?.let { open -> AikenSyntaxText.findMatchingDelimiter(text, open, '{', '}') }
                                ?.takeIf { it <= bodyClose }
                        if (handlerBodyOpen != null && handlerBodyClose != null) {
                            sink +=
                                ParameterRegion(
                                    paramsOpen = handlerParamsOpen,
                                    paramsClose = handlerParamsClose,
                                    scope = TextRange(handlerBodyOpen, handlerBodyClose)
                                )
                            collectLambdaParameterRegions(text, handlerBodyOpen + 1, handlerBodyClose, sink)
                        }
                    }
                }
            }

            when (tokenType) {
                AikenTokenTypes.LBRACE -> relativeBraceDepth += 1
                AikenTokenTypes.RBRACE -> relativeBraceDepth = (relativeBraceDepth - 1).coerceAtLeast(0)
            }
            bodyLexer.advance()
        }
    }

    private fun isAtLogicalLineStart(text: CharSequence, offset: Int): Boolean {
        var index = offset - 1
        while (index >= 0) {
            val ch = text[index]
            if (ch == '\n' || ch == '\r') return true
            if (ch != ' ' && ch != '\t') return false
            index--
        }
        return true
    }

    private fun collectLambdaParameterRegions(
        text: String,
        searchStart: Int,
        searchEnd: Int,
        sink: MutableList<ParameterRegion>
    ) {
        if (searchStart >= searchEnd) return
        val lexer = AikenLexing.createLexer()
        lexer.start(text, searchStart.coerceIn(0, text.length), searchEnd.coerceIn(0, text.length), 0)

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (tokenType != AikenTokenTypes.KEYWORD || tokenText != "fn") {
                lexer.advance()
                continue
            }

            val paramsOpen = findHeadParamsOpenParen(text, lexer.tokenEnd)
            if (paramsOpen == null || paramsOpen >= searchEnd) {
                lexer.advance()
                continue
            }
            val paramsClose = AikenSyntaxText.findMatchingDelimiter(text, paramsOpen, '(', ')')
            if (paramsClose == null || paramsClose >= searchEnd) {
                lexer.advance()
                continue
            }
            val bodyOpen = findHeadBodyOpenBrace(text, paramsClose + 1)
            if (bodyOpen == null || bodyOpen >= searchEnd) {
                lexer.advance()
                continue
            }
            val bodyClose = AikenSyntaxText.findMatchingDelimiter(text, bodyOpen, '{', '}')
            if (bodyClose == null || bodyClose > searchEnd) {
                lexer.advance()
                continue
            }

            sink += ParameterRegion(paramsOpen = paramsOpen, paramsClose = paramsClose, scope = TextRange(bodyOpen, bodyClose))
            collectLambdaParameterRegions(text, bodyOpen + 1, bodyClose, sink)
            lexer.start(text, (bodyClose + 1).coerceAtMost(searchEnd), searchEnd, 0)
        }
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

    private fun skipWhitespace(text: CharSequence, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
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
