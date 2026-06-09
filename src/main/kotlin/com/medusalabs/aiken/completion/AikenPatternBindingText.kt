package com.medusalabs.aiken.completion

internal object AikenPatternBindingText {
    fun extractBindings(
        patternText: String,
        absoluteStartOffset: Int = 0
    ): List<Pair<String, Int>> {
        val bindings = ArrayList<Pair<String, Int>>()
        collectBindings(patternText, absoluteStartOffset, bindings, depth = 0)
        return bindings
    }

    fun topLevelConstructorName(patternText: String): String? =
        parseTopLevelConstructorHead(stripTopLevelTypeAnnotation(patternText).trim())
            ?.let { head ->
                patternText
                    .let(::stripTopLevelTypeAnnotation)
                    .trim()
                    .substring(0, head.openIndex)
                    .trim()
                    .takeIf { it.isNotBlank() }
            }

    private fun collectBindings(
        patternText: String,
        absoluteStartOffset: Int,
        bindings: MutableList<Pair<String, Int>>,
        depth: Int
    ) {
        if (depth > 20) return
        val (trimmed, trimmedOffset) = trimWithOffset(patternText, absoluteStartOffset)
        if (trimmed.isBlank() || trimmed == "_") return

        val stripped = stripTopLevelTypeAnnotation(trimmed).trim()
        if (stripped.isBlank() || stripped == "_") return

        if (stripped.startsWith("..")) {
            collectBindings(stripped.removePrefix(".."), trimmedOffset + stripped.indexOf("..") + 2, bindings, depth + 1)
            return
        }

        splitTopLevelAsPattern(stripped)?.let { (innerPattern, capturedName, capturedOffset) ->
            collectBindings(innerPattern, trimmedOffset, bindings, depth + 1)
            bindings += capturedName to (trimmedOffset + capturedOffset)
            return
        }

        parseTopLevelConstructorHead(stripped)?.let { head ->
            val innerText = stripped.substring(head.openIndex + 1, head.closeIndex)
            for (segment in topLevelSegments(innerText)) {
                val (trimmedSegment, segmentOffset) = trimWithOffset(segment.text, trimmedOffset + head.openIndex + 1 + segment.relativeStart)
                if (trimmedSegment.isBlank()) continue
                if (trimmedSegment.startsWith("..")) {
                    collectBindings(trimmedSegment.removePrefix(".."), segmentOffset + 2, bindings, depth + 1)
                    continue
                }
                if (head.openChar == '{') {
                    val colonIndex = AikenTopLevelText.indexOf(trimmedSegment, ':')
                    if (colonIndex >= 0) {
                        collectBindings(trimmedSegment.substring(colonIndex + 1), segmentOffset + colonIndex + 1, bindings, depth + 1)
                    } else if (looksLikeBindingIdentifier(trimmedSegment)) {
                        bindings += trimmedSegment to segmentOffset
                    } else {
                        collectBindings(trimmedSegment, segmentOffset, bindings, depth + 1)
                    }
                    continue
                }
                collectBindings(trimmedSegment, segmentOffset, bindings, depth + 1)
            }
            return
        }

        if (isEnclosedBy(stripped, '(', ')') || isEnclosedBy(stripped, '[', ']')) {
            val innerStart = trimmedOffset + stripped.indexOfAny(charArrayOf('(', '[')) + 1
            val innerText = stripped.substring(1, stripped.length - 1)
            for (segment in topLevelSegments(innerText)) {
                collectBindings(segment.text, innerStart + segment.relativeStart, bindings, depth + 1)
            }
            return
        }

        val bareSegments = topLevelSegments(stripped)
        if (bareSegments.size > 1) {
            for (segment in bareSegments) {
                collectBindings(segment.text, trimmedOffset + segment.relativeStart, bindings, depth + 1)
            }
            return
        }

        if (looksLikeBindingIdentifier(stripped)) {
            bindings += stripped to trimmedOffset
        }
    }

    private data class ConstructorHead(
        val openChar: Char,
        val openIndex: Int,
        val closeIndex: Int
    )

    private data class Segment(
        val text: String,
        val relativeStart: Int
    )

    private data class AsPattern(
        val innerPattern: String,
        val capturedName: String,
        val capturedOffset: Int
    )

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

    private fun stripTopLevelTypeAnnotation(patternText: String): String {
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var index = 0

        while (index < patternText.length) {
            when (val ch = patternText[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return patternText.substring(0, index)
                }
            }
            index++
        }

        return patternText
    }

    private fun splitTopLevelAsPattern(patternText: String): AsPattern? {
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var index = 0
        var asIndex = -1

        while (index < patternText.length) {
            when (val ch = patternText[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                'a' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 &&
                    patternText.startsWith("as", index) &&
                    isWordBoundary(patternText, index - 1) &&
                    isWordBoundary(patternText, index + 2)
                ) {
                    asIndex = index
                }
            }
            index++
        }

        if (asIndex < 0) return null
        val left = patternText.substring(0, asIndex).trim()
        val right = patternText.substring(asIndex + 2).trim()
        if (left.isBlank() || !looksLikeBindingIdentifier(right)) return null
        val rightOffset = patternText.indexOf(right, asIndex + 2)
        if (rightOffset < 0) return null
        return AsPattern(left, right, rightOffset)
    }

    private fun parseTopLevelConstructorHead(patternText: String): ConstructorHead? {
        val identifierRange = AikenSyntaxText.leadingQualifiedIdentifierRange(patternText) ?: return null
        val head = patternText.substring(identifierRange)
        if (head.isBlank() || head.firstOrNull()?.isUpperCase() != true) return null
        var index = skipWhitespace(patternText, identifierRange.last + 1)
        if (index >= patternText.length) return null
        val openChar = patternText[index]
        val closeChar =
            when (openChar) {
                '(' -> ')'
                '{' -> '}'
                else -> return null
            }
        val closeIndex = AikenSyntaxText.findMatchingDelimiter(patternText, index, openChar, closeChar) ?: return null
        if (closeIndex != patternText.lastIndex) return null
        return ConstructorHead(openChar = openChar, openIndex = index, closeIndex = closeIndex)
    }

    private fun topLevelSegments(text: String): List<Segment> =
        AikenTopLevelText.splitRanges(text, ',')
            .map { range ->
                Segment(
                    text = text.substring(range.startOffset, range.endOffset),
                    relativeStart = range.startOffset
                )
            }
            .filter { it.text.isNotBlank() }

    private fun isEnclosedBy(
        text: String,
        open: Char,
        close: Char
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2 || trimmed.first() != open || trimmed.last() != close) return false
        val closeIndex = AikenSyntaxText.findMatchingDelimiter(trimmed, 0, open, close) ?: return false
        return closeIndex == trimmed.lastIndex
    }

    private fun looksLikeBindingIdentifier(text: String): Boolean =
        text.isNotBlank() &&
            (text.first().isLowerCase() || text.first() == '_') &&
            text.all(AikenSyntaxText::isIdentifierChar) &&
            text != "_"

    private fun skipWhitespace(text: String, startIndex: Int): Int {
        var index = startIndex.coerceIn(0, text.length)
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun isWordBoundary(
        text: String,
        index: Int
    ): Boolean =
        index !in text.indices || !AikenSyntaxText.isIdentifierChar(text[index])
}
