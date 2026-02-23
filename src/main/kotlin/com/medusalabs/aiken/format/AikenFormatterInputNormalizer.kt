package com.medusalabs.aiken.format

object AikenFormatterInputNormalizer {
    /**
     * Aiken lexer treats "empty line" strictly as two consecutive newline tokens.
     * If an otherwise empty line contains spaces/tabs, it may be formatted differently.
     * Normalize whitespace-only lines to truly empty before invoking `aiken fmt`.
     */
    fun normalizeWhitespaceOnlyLines(text: String): String {
        if (text.isEmpty()) return text

        val out = StringBuilder(text.length)
        var lineStart = 0
        var index = 0
        var hasNonWhitespace = false
        var changed = false

        while (index < text.length) {
            val ch = text[index]
            when (ch) {
                '\n' -> {
                    if (hasNonWhitespace) {
                        out.append(text, lineStart, index)
                    } else if (index > lineStart) {
                        changed = true
                    }
                    out.append('\n')
                    index += 1
                    lineStart = index
                    hasNonWhitespace = false
                }

                '\r' -> {
                    val isCrLf = index + 1 < text.length && text[index + 1] == '\n'
                    if (hasNonWhitespace) {
                        out.append(text, lineStart, index)
                    } else if (index > lineStart) {
                        changed = true
                    }
                    if (isCrLf) {
                        out.append("\r\n")
                        index += 2
                    } else {
                        out.append('\r')
                        index += 1
                    }
                    lineStart = index
                    hasNonWhitespace = false
                }

                else -> {
                    if (!hasNonWhitespace && ch != ' ' && ch != '\t') {
                        hasNonWhitespace = true
                    }
                    index += 1
                }
            }
        }

        if (lineStart < text.length) {
            if (hasNonWhitespace) {
                out.append(text, lineStart, text.length)
            } else if (text.length > lineStart) {
                changed = true
            }
        }

        return if (changed) out.toString() else text
    }
}
