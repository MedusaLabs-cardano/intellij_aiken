package com.medusalabs.aiken.completion

internal object AikenValueWrapperScanner {
    sealed interface Wrapper {
        data object OptionSome : Wrapper

        data class ListLiteral(
            val currentSegmentIsSpread: Boolean
        ) : Wrapper
    }

    fun scan(text: String): List<Wrapper> {
        val frames = ArrayDeque<Frame>()
        var inString = false
        var inLineComment = false
        var index = 0

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

            if (text.startsWith("Some(", index) && isStandaloneSomeCall(text, index)) {
                frames.addLast(Frame.OptionSome)
                index += "Some(".length
                continue
            }

            when (ch) {
                '(' -> frames.addLast(Frame.OtherParen)
                ')' -> {
                    when (frames.lastOrNull()) {
                        Frame.OptionSome -> frames.removeLast()
                        Frame.OtherParen -> frames.removeLast()
                        else -> Unit
                    }
                }
                '[' -> frames.addLast(Frame.ListLiteral(index + 1))
                ']' -> {
                    when (frames.lastOrNull()) {
                        is Frame.ListLiteral -> frames.removeLast()
                        else -> Unit
                    }
                }
                '{' -> frames.addLast(Frame.OtherBrace)
                '}' -> if (frames.lastOrNull() == Frame.OtherBrace) frames.removeLast()
                ',' -> {
                    val top = frames.lastOrNull()
                    if (top is Frame.ListLiteral) {
                        top.currentSegmentStart = index + 1
                    }
                }
            }
            index++
        }

        val wrappers = ArrayDeque<Wrapper>()
        for (frame in frames) {
            when (frame) {
                Frame.OptionSome -> wrappers.addLast(Wrapper.OptionSome)
                is Frame.ListLiteral -> wrappers.addLast(
                    Wrapper.ListLiteral(
                        currentSegmentIsSpread =
                            AikenCurrentExpressionSegment.fromRange(text, frame.currentSegmentStart, text.length).isSpread
                    )
                )
                else -> Unit
            }
        }

        return wrappers.toList()
    }

    private fun isStandaloneSomeCall(text: String, startIndex: Int): Boolean {
        if (startIndex > 0 && (text[startIndex - 1].isLetterOrDigit() || text[startIndex - 1] == '_')) {
            return false
        }
        val endIndex = startIndex + "Some".length
        return endIndex < text.length && text[endIndex] == '('
    }

    private sealed interface Frame {
        data object OptionSome : Frame

        data object OtherParen : Frame

        data object OtherBrace : Frame

        data class ListLiteral(var currentSegmentStart: Int) : Frame
    }
}
