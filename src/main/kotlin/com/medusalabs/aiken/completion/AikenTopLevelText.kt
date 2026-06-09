package com.medusalabs.aiken.completion

import com.intellij.openapi.util.TextRange

internal object AikenTopLevelText {
    private val defaultExpressionBoundaryChars = setOf('=', ':', ',', '\n', '\r')

    fun splitRanges(
        text: CharSequence,
        delimiter: Char,
        start: Int = 0,
        endExclusive: Int = text.length,
        trackAngles: Boolean = false
    ): List<TextRange> {
        val safeRange = sanitizeRange(text, start, endExclusive)
        val ranges = ArrayList<TextRange>()
        val nesting = NestingState()
        var segmentStart = safeRange.startOffset

        for (index in safeRange.startOffset until safeRange.endOffset) {
            val ch = text[index]
            if (nesting.isTopLevel() && ch == delimiter) {
                ranges += TextRange(segmentStart, index)
                segmentStart = index + 1
                continue
            }
            nesting.consume(ch, trackAngles)
        }

        ranges += TextRange(segmentStart, safeRange.endOffset)
        return ranges
    }

    fun indexOf(
        text: CharSequence,
        target: Char,
        start: Int = 0,
        endExclusive: Int = text.length,
        trackAngles: Boolean = false
    ): Int {
        val safeRange = sanitizeRange(text, start, endExclusive)
        val nesting = NestingState()

        for (index in safeRange.startOffset until safeRange.endOffset) {
            val ch = text[index]
            if (nesting.isTopLevel() && ch == target) return index
            nesting.consume(ch, trackAngles)
        }

        return -1
    }

    fun currentSegmentRange(
        text: CharSequence,
        delimiter: Char,
        start: Int = 0,
        endExclusive: Int = text.length,
        closingDelimiter: Char? = null,
        trackAngles: Boolean = false
    ): TextRange {
        val safeRange = sanitizeRange(text, start, endExclusive)
        val nesting = NestingState()
        var segmentStart = safeRange.startOffset

        for (index in safeRange.startOffset until safeRange.endOffset) {
            val ch = text[index]
            if (nesting.isTopLevel()) {
                if (closingDelimiter != null && ch == closingDelimiter) {
                    return TextRange(segmentStart, index)
                }
                if (ch == delimiter) {
                    segmentStart = index + 1
                    continue
                }
            }
            nesting.consume(ch, trackAngles)
        }

        return TextRange(segmentStart, safeRange.endOffset)
    }

    fun segmentIndexAt(
        text: CharSequence,
        delimiter: Char,
        start: Int = 0,
        endExclusive: Int = text.length,
        closingDelimiter: Char? = null,
        trackAngles: Boolean = false
    ): Int? {
        val safeRange = sanitizeRange(text, start, endExclusive)
        val nesting = NestingState()
        var segmentIndex = 0

        for (index in safeRange.startOffset until safeRange.endOffset) {
            val ch = text[index]
            if (nesting.isTopLevel()) {
                if (closingDelimiter != null && ch == closingDelimiter) return null
                if (ch == delimiter) {
                    segmentIndex++
                    continue
                }
            }
            nesting.consume(ch, trackAngles)
        }

        return segmentIndex
    }

    fun findEnclosingOpening(
        text: CharSequence,
        opening: Char,
        offsetExclusive: Int,
        trackAngles: Boolean = false
    ): Int? {
        val safeOffset = offsetExclusive.coerceIn(0, text.length)
        val nesting = BackwardNestingState()

        for (index in (safeOffset - 1) downTo 0) {
            val ch = text[index]
            if (ch == opening && nesting.isTopLevel()) return index
            nesting.consume(ch, trackAngles)
        }

        return null
    }

    fun findExpressionStartBefore(
        text: CharSequence,
        endExclusive: Int,
        boundaryChars: Set<Char> = defaultExpressionBoundaryChars,
        stopAtPipeOperator: Boolean = false,
        trackAngles: Boolean = false
    ): Int? {
        var index = endExclusive.coerceIn(0, text.length) - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return null

        val nesting = BackwardNestingState()
        while (index >= 0) {
            val ch = text[index]
            if (nesting.isTopLevel()) {
                if (ch in boundaryChars) return index + 1
                if (stopAtPipeOperator && ch == '>' && index > 0 && text[index - 1] == '|') return index + 1
                if (ch == '(' || ch == '[' || ch == '{') return index + 1
            }
            nesting.consume(ch, trackAngles)
            index--
        }

        return 0
    }

    private fun sanitizeRange(text: CharSequence, start: Int, endExclusive: Int): TextRange {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = endExclusive.coerceIn(safeStart, text.length)
        return TextRange(safeStart, safeEnd)
    }

    private class NestingState(
        private var parenDepth: Int = 0,
        private var bracketDepth: Int = 0,
        private var braceDepth: Int = 0,
        private var angleDepth: Int = 0
    ) {
        fun isTopLevel(): Boolean =
            parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0

        fun consume(ch: Char, trackAngles: Boolean) {
            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '<' -> if (trackAngles) angleDepth++
                '>' -> if (trackAngles) angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }
        }
    }

    private class BackwardNestingState(
        private var parenDepth: Int = 0,
        private var bracketDepth: Int = 0,
        private var braceDepth: Int = 0,
        private var angleDepth: Int = 0
    ) {
        fun isTopLevel(): Boolean =
            parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0

        fun consume(ch: Char, trackAngles: Boolean) {
            when (ch) {
                ')' -> parenDepth++
                '(' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                ']' -> bracketDepth++
                '[' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '}' -> braceDepth++
                '{' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '>' -> if (trackAngles) angleDepth++
                '<' -> if (trackAngles) angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }
        }
    }
}
