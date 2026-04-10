package com.medusalabs.aiken.completion

internal object AikenListLiteralScanner {
    data class FrameState(
        val currentSegmentStart: Int,
        val inferredElementType: String? = null,
        val expectedListType: String? = null
    )

    data class Context(
        val expectedListType: String,
        val currentSegment: AikenCurrentExpressionSegment
    )

    fun inferContext(
        text: String,
        offset: Int,
        expectedListTypeAtListStart: (FrameState?, Int) -> String?,
        inferSegmentType: (Int, Int) -> String?,
        fallbackElementType: (FrameState) -> String?
    ): Context? {
        if (text.isEmpty()) return null

        val safeOffset = offset.coerceIn(0, text.length)
        val frames = ArrayDeque<MutableFrame>()
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

            if (index == safeOffset) {
                return currentContext(frames.lastOrNull(), safeOffset, text, inferSegmentType, fallbackElementType)
            }

            when (ch) {
                '[' -> {
                    val parent = frames.lastOrNull()?.asState()
                    frames.addLast(
                        MutableFrame(
                            currentSegmentStart = index + 1,
                            expectedListType = expectedListTypeAtListStart(parent, index)
                        )
                    )
                }
                ',' -> {
                    frames.lastOrNull()?.let { frame ->
                        inferSegmentType(frame.currentSegmentStart, index)?.let { inferredType ->
                            frame.inferredElementType = frame.inferredElementType ?: inferredType
                        }
                        frame.currentSegmentStart = index + 1
                    }
                }
                ']' -> {
                    frames.removeLastOrNull()?.let { frame ->
                        inferSegmentType(frame.currentSegmentStart, index)?.let { inferredType ->
                            frame.inferredElementType = frame.inferredElementType ?: inferredType
                        }
                    }
                }
            }
            index++
        }

        return if (safeOffset == text.length) {
            currentContext(frames.lastOrNull(), safeOffset, text, inferSegmentType, fallbackElementType)
        } else {
            null
        }
    }

    private fun currentContext(
        frame: MutableFrame?,
        offset: Int,
        text: String,
        inferSegmentType: (Int, Int) -> String?,
        fallbackElementType: (FrameState) -> String?
    ): Context? {
        val state = frame?.asState() ?: return null
        val elementType =
            state.inferredElementType
                ?: inferSegmentType(state.currentSegmentStart, offset)
                ?: fallbackElementType(state)
                ?: return null

        return Context(
            expectedListType = state.expectedListType ?: "List<$elementType>",
            currentSegment = AikenCurrentExpressionSegment.fromRange(text, state.currentSegmentStart, offset)
        )
    }

    private data class MutableFrame(
        var currentSegmentStart: Int,
        var inferredElementType: String? = null,
        val expectedListType: String? = null
    ) {
        fun asState(): FrameState =
            FrameState(
                currentSegmentStart = currentSegmentStart,
                inferredElementType = inferredElementType,
                expectedListType = expectedListType
            )
    }
}
