package com.medusalabs.aiken.completion

object AikenCompletionContexts {
    fun isLikelyValueExpressionContext(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return false

        return when (text[index]) {
            '=', '(', '[', ',' -> true
            else -> index > 0 && text[index] == '>' && text[index - 1] == '-'
        }
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
