package com.medusalabs.aiken.completion

internal object AikenSyntaxText {
    fun identifierPrefix(text: CharSequence, offsetExclusive: Int): String {
        if (text.isEmpty()) return ""

        val safeOffset = offsetExclusive.coerceIn(0, text.length)
        var index = safeOffset - 1
        while (index >= 0 && isIdentifierChar(text[index])) index--
        return text.subSequence(index + 1, safeOffset).toString()
    }

    fun qualifierBeforeOffset(text: CharSequence, offsetExclusive: Int): String? {
        var index = offsetExclusive.coerceIn(0, text.length) - 1
        while (index >= 0 && isIdentifierChar(text[index])) index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0 || text[index] != '.') return null

        index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return null

        val end = index + 1
        while (index >= 0 && isIdentifierChar(text[index])) index--
        val start = index + 1
        return if (start < end) text.subSequence(start, end).toString() else null
    }

    fun qualifiedChainBeforeOffset(text: CharSequence, offsetExclusive: Int): String? {
        var index = offsetExclusive.coerceIn(0, text.length) - 1
        while (index >= 0 && isIdentifierChar(text[index])) index--

        val segments = ArrayList<String>()
        while (true) {
            while (index >= 0 && text[index].isWhitespace()) index--
            if (index < 0 || text[index] != '.') break

            index--
            while (index >= 0 && text[index].isWhitespace()) index--
            if (index < 0) break

            val end = index + 1
            while (index >= 0 && isIdentifierChar(text[index])) index--
            val start = index + 1
            if (start >= end) break
            segments += text.subSequence(start, end).toString()
        }

        if (segments.isEmpty()) return null
        segments.reverse()
        return segments.joinToString(".")
    }

    fun leadingQualifiedIdentifierRange(
        text: CharSequence,
        start: Int = 0
    ): IntRange? {
        var index = start.coerceAtLeast(0)
        if (index >= text.length || !isIdentifierChar(text[index])) return null
        index++
        while (index < text.length && (isIdentifierChar(text[index]) || text[index] == '.')) {
            index++
        }
        return start until index
    }

    fun qualifierOfLeadingIdentifier(text: CharSequence): String? {
        val trimmed = text.trimStart()
        val range = leadingQualifiedIdentifierRange(trimmed) ?: return null
        val identifier = trimmed.substring(range)
        return identifier.substringBeforeLast('.', "").trim().takeIf { it.isNotEmpty() }
    }

    fun findLastTopLevelPipeOffset(
        text: CharSequence,
        endExclusive: Int = text.length,
        trackNesting: Boolean = true
    ): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var lastPipeOffset: Int? = null
        var index = 0
        val limit = endExclusive.coerceIn(0, text.length)

        while (index + 1 < limit) {
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
                '(' -> if (trackNesting) parenDepth++
                ')' -> if (trackNesting) parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> if (trackNesting) bracketDepth++
                ']' -> if (trackNesting) bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> if (trackNesting) braceDepth++
                '}' -> if (trackNesting) braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '|' -> if (text[index + 1] == '>' && (!trackNesting || (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0))) {
                    lastPipeOffset = index
                }
            }
            index++
        }

        return lastPipeOffset
    }

    fun findMatchingDelimiter(
        text: CharSequence,
        openIndex: Int,
        openChar: Char,
        closeChar: Char
    ): Int? {
        if (openIndex !in 0 until text.length || text[openIndex] != openChar) return null

        var depth = 0
        var inString = false
        var inLineComment = false
        var index = openIndex

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
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }

        return null
    }

    fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
}
