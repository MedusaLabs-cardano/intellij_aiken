package com.medusalabs.aiken.completion

internal object AikenTypeText {
    fun normalizeWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    builder.append(' ')
                    lastWasSpace = true
                }
            } else {
                builder.append(ch)
                lastWasSpace = false
            }
        }
        return builder.toString().trim()
    }

    fun unwrapSingleGenericType(
        typeText: String,
        containerName: String,
        normalize: (String) -> String = ::normalizeWhitespace
    ): String? {
        val normalized = normalize(typeText)
        if (!normalized.startsWith("$containerName<")) return null

        val openIndex = normalized.indexOf('<')
        if (openIndex <= 0) return null

        var depth = 0
        var innerStart = -1
        for (index in openIndex until normalized.length) {
            when (normalized[index]) {
                '<' -> {
                    if (depth == 0) innerStart = index + 1
                    depth++
                }
                '>' -> {
                    depth--
                    if (depth == 0) {
                        if (index != normalized.lastIndex || innerStart <= 0) return null
                        return normalized.substring(innerStart, index).trim().takeIf { it.isNotEmpty() }
                    }
                }
            }
        }

        return null
    }

    fun splitTopLevelTypeArguments(text: String): List<String>? {
        val arguments = ArrayList<String>()
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var segmentStart = 0

        for (index in text.indices) {
            when (text[index]) {
                '<' -> angleDepth++
                '>' -> {
                    if (angleDepth == 0) return null
                    angleDepth--
                }
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ',' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        arguments += text.substring(segmentStart, index).trim()
                        segmentStart = index + 1
                    }
                }
            }
        }

        if (angleDepth != 0 || parenDepth != 0 || bracketDepth != 0 || braceDepth != 0) return null
        arguments += text.substring(segmentStart).trim()
        return arguments.filter { it.isNotEmpty() }
    }

    fun normalizeGenericVariablesForDisplay(text: String): String {
        if (text.isBlank()) return text

        val normalized = normalizeWhitespace(text)
        val genericMapping = LinkedHashMap<String, String>()
        val builder = StringBuilder(normalized.length)
        var index = 0

        while (index < normalized.length) {
            val ch = normalized[index]
            if (!isIdentifierStart(ch)) {
                builder.append(ch)
                index++
                continue
            }

            val start = index
            index++
            while (index < normalized.length && isIdentifierPart(normalized[index])) {
                index++
            }

            val token = normalized.substring(start, index)
            builder.append(
                if (shouldRenderAsGenericVariable(normalized, start, index, token)) {
                    genericMapping.getOrPut(token) { genericPlaceholder(genericMapping.size) }
                } else {
                    token
                }
            )
        }

        return builder.toString()
    }

    private fun shouldRenderAsGenericVariable(
        text: String,
        start: Int,
        endExclusive: Int,
        token: String
    ): Boolean {
        if (token == "fn") return false
        val firstChar = token.firstOrNull() ?: return false
        if (!firstChar.isLowerCase()) return false

        val nextIndex = skipWhitespaceForward(text, endExclusive)
        if (nextIndex < text.length && text[nextIndex] == '.') return false

        return true
    }

    private fun genericPlaceholder(position: Int): String =
        when (position) {
            0 -> "T"
            1 -> "U"
            2 -> "V"
            3 -> "W"
            4 -> "X"
            5 -> "Y"
            6 -> "Z"
            else -> "T${position + 1}"
        }

    private fun skipWhitespaceForward(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun isIdentifierStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

    private fun isIdentifierPart(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()
}
