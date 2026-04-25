package com.medusalabs.aiken.completion

import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.highlight.lexer.AikenLexing

object AikenCompletionContexts {
    private val declarationNamePattern =
        Regex("""^(?:pub\s+)?(?:opaque\s+)?(?:const|fn|type|validator|test|bench)\s+[A-Za-z_][A-Za-z0-9_]*$""")

    // Keep this as a low-level fallback heuristic only. Scenario policy should decide first.
    fun isLikelyValueExpressionContext(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        if (isCallableParameterDeclarationContext(text, offset)) return false
        if (AikenBindingInitializerScanner.isInsideBindingPatternDeclaration(text, offset)) return false
        if (isInsideGenericTypeArgumentContext(text, offset)) return false
        if (isCallableReturnTypeContext(text, offset)) return false
        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return false

        return when (text[index]) {
            '=', '(', '[', ',' -> true
            else -> index > 0 && text[index] == '>' && text[index - 1] == '-'
        }
    }

    fun isLikelyTypeReferenceContext(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        if (isCallableParameterDeclarationContext(text, offset)) return true
        if (AikenBindingInitializerScanner.isInsideBindingPatternDeclaration(text, offset)) return true
        if (isInsideGenericTypeArgumentContext(text, offset)) return true
        if (isCallableReturnTypeContext(text, offset)) return true
        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && AikenSyntaxText.isIdentifierChar(text[index])) index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return false

        if (text[index] == ':') return true
        return isKeywordIsAt(text, index)
    }

    private fun isKeywordIsAt(text: String, index: Int): Boolean {
        if (index < 1) return false
        if (text[index - 1] != 'i' || text[index] != 's') return false
        val beforeBoundary = index - 2 < 0 || !AikenSyntaxText.isIdentifierChar(text[index - 2])
        val afterBoundary = index + 1 >= text.length || !AikenSyntaxText.isIdentifierChar(text[index + 1])
        return beforeBoundary && afterBoundary
    }

    fun isInsideGenericTypeArgumentContext(text: String, offset: Int): Boolean =
        findEnclosingTypeArgumentOpen(text, offset) != null

    fun isCallableParameterDeclarationContext(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        val safeOffset = offset.coerceIn(0, text.length)
        for (region in AikenParameterBindingScanner.collectParameterRegions(text)) {
            if (safeOffset in (region.paramsOpen + 1)..region.paramsClose) {
                return currentParameterSegmentHasNoTopLevelColon(
                    text = text,
                    paramsOpen = region.paramsOpen,
                    paramsClose = region.paramsClose,
                    offset = safeOffset
                )
            }
        }
        return false
    }

    fun isDeclarationNameContext(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val beforeCaret = text.substring(lineStart, safeOffset).trimStart()
        if (!declarationNamePattern.matches(beforeCaret)) return false

        var index = safeOffset
        while (index < text.length && AikenSyntaxText.isIdentifierChar(text[index])) index++
        while (index < text.length && text[index].isWhitespace() && text[index] != '\n' && text[index] != '\r') index++

        return index >= text.length || text[index] in charArrayOf('\n', '\r', '(', '{', '<', ':', '=')
    }


    fun isCallableReturnTypeContext(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        val safeOffset = offset.coerceIn(0, text.length)
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var braceDepth = 0

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (tokenType == AikenTokenTypes.KEYWORD &&
                (tokenText == "fn" || (braceDepth == 0 && (tokenText == "test" || tokenText == "bench")))
            ) {
                val callable =
                    parseCallableReturnTypeRegion(
                        text = text,
                        declarationKeywordStart = lexer.tokenStart,
                        declarationKeyword = tokenText
                    )
                if (callable != null) {
                    val returnTypeStart = callable.returnTypeStart ?: return false
                    val returnTypeEndExclusive = callable.returnTypeEndExclusive ?: return false
                    if (safeOffset in returnTypeStart..returnTypeEndExclusive) {
                        return true
                    }
                    if (safeOffset !in lexer.tokenStart..callable.resumeOffset) {
                        lexer.start(text, callable.resumeOffset.coerceAtMost(text.length), text.length, 0)
                        braceDepth = 0
                        continue
                    }
                }
            }

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            lexer.advance()
        }

        return false
    }

    fun isInsideMalformedCallableReturnConstructibleContext(
        text: String,
        offset: Int
    ): Boolean {
        if (text.isEmpty()) return false
        val safeOffset = offset.coerceIn(0, text.length)
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var braceDepth = 0

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (tokenType == AikenTokenTypes.KEYWORD &&
                (tokenText == "fn" || (braceDepth == 0 && (tokenText == "test" || tokenText == "bench")))
            ) {
                val callable =
                    parseCallableReturnTypeRegion(
                        text = text,
                        declarationKeywordStart = lexer.tokenStart,
                        declarationKeyword = tokenText
                    )
                if (callable != null) {
                    val malformedOpen = callable.returnTypeEndExclusive
                    if (malformedOpen != null &&
                        malformedOpen < text.length &&
                        text[malformedOpen] == '{'
                    ) {
                        val malformedClose = AikenSyntaxText.findMatchingDelimiter(text, malformedOpen, '{', '}')
                        if (malformedClose != null) {
                            val nextTokenOffset = skipWhitespaceForward(text, malformedClose + 1)
                            if (nextTokenOffset < text.length &&
                                text[nextTokenOffset] == '{' &&
                                safeOffset in (malformedOpen + 1)..malformedClose
                            ) {
                                return true
                            }
                        }
                    }
                    if (safeOffset !in lexer.tokenStart..callable.resumeOffset) {
                        lexer.start(text, callable.resumeOffset.coerceAtMost(text.length), text.length, 0)
                        braceDepth = 0
                        continue
                    }
                }
            }

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            lexer.advance()
        }

        return false
    }

    private data class ParsedCallableHeaderRegions(
        val paramsOpen: Int,
        val paramsClose: Int,
        val returnTypeStart: Int?,
        val returnTypeEndExclusive: Int?,
        val resumeOffset: Int
    )

    private fun parseCallableReturnTypeRegion(
        text: String,
        declarationKeywordStart: Int,
        declarationKeyword: String
    ): ParsedCallableHeaderRegions? =
        parseCallableHeaderRegions(text, declarationKeywordStart, declarationKeyword)
            ?.takeIf { it.returnTypeStart != null && it.returnTypeEndExclusive != null }

    private fun parseCallableHeaderRegions(
        text: String,
        declarationKeywordStart: Int,
        declarationKeyword: String
    ): ParsedCallableHeaderRegions? {
        var index = skipWhitespaceForward(text, declarationKeywordStart + declarationKeyword.length)
        if (index >= text.length) return null

        if (declarationKeyword == "fn" && text[index] == '(') {
            // Anonymous function: fn(...) -> T { ... }
        } else {
            if (!AikenSyntaxText.isIdentifierChar(text[index])) return null
            while (index < text.length && AikenSyntaxText.isIdentifierChar(text[index])) index++
            index = skipWhitespaceForward(text, index)
        }

        if (index < text.length && text[index] == '<') {
            index = skipTopLevelAngles(text, index) ?: return null
            index = skipWhitespaceForward(text, index)
        }

        if (index >= text.length || text[index] != '(') return null
        val paramsOpen = index
        val paramsClose = AikenSyntaxText.findMatchingDelimiter(text, paramsOpen, '(', ')') ?: return null
        index = skipWhitespaceForward(text, paramsClose + 1)

        var returnTypeStart: Int? = null
        var returnTypeEndExclusive: Int? = null
        if (index + 1 < text.length && text[index] == '-' && text[index + 1] == '>') {
            returnTypeStart = index + 2
            returnTypeEndExclusive = skipWhitespaceForward(text, returnTypeStart)
            while (returnTypeEndExclusive < text.length &&
                text[returnTypeEndExclusive] != '{' &&
                text[returnTypeEndExclusive] != '\n' &&
                text[returnTypeEndExclusive] != '\r'
            ) {
                returnTypeEndExclusive++
            }
            index = returnTypeEndExclusive
        }

        val bodyOpen = skipWhitespaceForward(text, index)
        if (bodyOpen >= text.length || text[bodyOpen] != '{') return null
        val bodyClose = AikenSyntaxText.findMatchingDelimiter(text, bodyOpen, '{', '}') ?: return null

        return ParsedCallableHeaderRegions(
            paramsOpen = paramsOpen,
            paramsClose = paramsClose,
            returnTypeStart = returnTypeStart,
            returnTypeEndExclusive = returnTypeEndExclusive,
            resumeOffset = bodyClose + 1
        )
    }

    private fun currentParameterSegmentHasNoTopLevelColon(
        text: String,
        paramsOpen: Int,
        paramsClose: Int,
        offset: Int
    ): Boolean {
        val boundedOffset = offset.coerceIn(paramsOpen + 1, paramsClose)
        var segmentStart = paramsOpen + 1
        var index = paramsOpen + 1
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        while (index < boundedOffset) {
            when (text[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ',' ->
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        segmentStart = index + 1
                    }
            }
            index++
        }

        angleDepth = 0
        parenDepth = 0
        bracketDepth = 0
        braceDepth = 0
        index = segmentStart
        while (index < boundedOffset) {
            when (text[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' ->
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        return false
                    }
            }
            index++
        }
        return true
    }

    private fun skipWhitespaceForward(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun skipTopLevelAngles(text: String, start: Int): Int? {
        var index = start
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
                '\n', '\r', '{', '}' -> return null
            }
            index++
        }
        return null
    }

    private fun findEnclosingTypeArgumentOpen(
        text: String,
        offset: Int
    ): Int? {
        if (text.isEmpty() || offset <= 0) return null

        val frames = ArrayDeque<Int>()
        var inString = false
        var inLineComment = false
        var index = 0
        val limit = offset.coerceAtMost(text.length)

        while (index < limit) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < limit) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < limit && text[index + 1] == '/') {
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
                '<' -> if (looksLikeTypeArgumentListStart(text, index)) frames.addLast(index)
                '>' -> if (frames.isNotEmpty()) frames.removeLast()
            }
            index++
        }

        return frames.lastOrNull()
    }

    private fun looksLikeTypeArgumentListStart(
        text: String,
        openIndex: Int
    ): Boolean {
        val next = nextNonWhitespaceIndex(text, openIndex + 1) ?: return false
        if (text[next] == '-') return false

        val prev = previousNonWhitespaceIndex(text, openIndex - 1) ?: return false
        val prevChar = text[prev]
        if (!AikenSyntaxText.isIdentifierChar(prevChar) && prevChar != '.' && prevChar != '>') return false

        val rootStart = findQualifiedIdentifierStart(text, prev)
        return isTypeExpressionBoundary(text, rootStart)
    }

    private fun findQualifiedIdentifierStart(
        text: String,
        index: Int
    ): Int {
        var cursor = index.coerceIn(0, text.lastIndex)
        while (cursor > 0) {
            val previous = text[cursor - 1]
            if (AikenSyntaxText.isIdentifierChar(previous) || previous == '.') {
                cursor--
                continue
            }
            break
        }
        return cursor
    }

    private fun isTypeExpressionBoundary(
        text: String,
        rootStart: Int
    ): Boolean {
        val boundary = previousNonWhitespaceIndex(text, rootStart - 1) ?: return false
        return when {
            text[boundary] == ':' -> true
            boundary > 0 && text[boundary] == '>' && text[boundary - 1] == '-' -> true
            text[boundary] == '<' -> true
            text[boundary] == ',' -> findEnclosingTypeArgumentOpen(text, boundary) != null
            isKeywordIsAt(text, boundary) -> true
            else -> false
        }
    }

    private fun previousNonWhitespaceIndex(
        text: String,
        start: Int
    ): Int? {
        var index = start
        while (index >= 0) {
            if (!text[index].isWhitespace()) return index
            index--
        }
        return null
    }

    private fun nextNonWhitespaceIndex(
        text: String,
        start: Int
    ): Int? {
        var index = start
        while (index < text.length) {
            if (!text[index].isWhitespace()) return index
            index++
        }
        return null
    }

    fun insideListLiteralContext(text: String, offset: Int): Boolean {
        if (text.isEmpty() || offset <= 0) return false

        val frames = ArrayDeque<Char>()
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < offset.coerceAtMost(text.length)) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < offset) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < offset && text[index + 1] == '/') {
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
                '[' -> frames.addLast('[')
                ']' -> if (frames.lastOrNull() == '[') frames.removeLast()
                '(' -> frames.addLast('(')
                ')' -> if (frames.lastOrNull() == '(') frames.removeLast()
                '{' -> frames.addLast('{')
                '}' -> if (frames.lastOrNull() == '{') frames.removeLast()
            }
            index++
        }

        return '[' in frames
    }
}
