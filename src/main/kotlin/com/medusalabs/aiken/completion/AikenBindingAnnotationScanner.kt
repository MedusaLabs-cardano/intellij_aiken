package com.medusalabs.aiken.completion

internal object AikenBindingAnnotationScanner {
    data class BindingSite(
        val nameStart: Int,
        val name: String
    )

    fun findBindingNameBeforeInitializer(
        text: String,
        initializerOffset: Int
    ): BindingSite? {
        var index = skipWhitespaceBackward(text, initializerOffset - 1)
        if (index < 0 || text[index] != '=') return null

        index = skipWhitespaceBackward(text, index - 1)
        if (index < 0) return null

        val colonIndex = findTopLevelColonBackward(text, index) ?: return null
        index = skipWhitespaceBackward(text, colonIndex - 1)
        if (index < 0 || !isIdentifierChar(text[index])) return null

        val nameEnd = index + 1
        while (index >= 0 && isIdentifierChar(text[index])) index--
        val nameStart = index + 1
        val name = text.substring(nameStart, nameEnd)
        return name.takeIf { it.isNotBlank() }?.let { BindingSite(nameStart, it) }
    }

    fun declaredTypeAt(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): String? {
        val nameEnd = declarationOffset + bindingName.length
        if (nameEnd > text.length) return null

        var index = skipWhitespaceForward(text, nameEnd)
        if (index >= text.length || text[index] != ':') return null

        index++
        index = skipWhitespaceForward(text, index)
        val typeStart = index

        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        while (index < text.length) {
            when (text[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                }
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                    braceDepth++
                }
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ',', '=', '\n', '\r' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                }
            }
            index++
        }

        return text.substring(typeStart, index).trim().takeIf { it.isNotEmpty() }
    }

    private fun findTopLevelColonBackward(text: String, startIndex: Int): Int? {
        var index = startIndex
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        while (index >= 0) {
            when (text[index]) {
                '>' -> angleDepth++
                '<' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                ')' -> parenDepth++
                '(' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                ']' -> bracketDepth++
                '[' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '}' -> braceDepth++
                '{' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
            }
            index--
        }

        return null
    }

    private fun skipWhitespaceForward(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun skipWhitespaceBackward(text: String, start: Int): Int {
        var index = start
        while (index >= 0 && text[index].isWhitespace()) index--
        return index
    }

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
}
