package com.medusalabs.aiken.completion

internal data class AikenCurrentExpressionSegment(
    val text: String
) {
    val isSpread: Boolean
        get() = text.trimStart().startsWith("..")

    val effectiveValueText: String
        get() = if (isSpread) text.trimStart().removePrefix("..").trimStart() else text

    companion object {
        fun fromText(source: CharSequence): AikenCurrentExpressionSegment =
            AikenCurrentExpressionSegment(source.toString().trim())

        fun fromRange(
            source: String,
            startOffset: Int,
            endExclusive: Int
        ): AikenCurrentExpressionSegment {
            val safeStart = startOffset.coerceIn(0, source.length)
            val safeEnd = endExclusive.coerceIn(safeStart, source.length)
            return fromText(source.substring(safeStart, safeEnd))
        }

        fun fromDelimitedRange(
            text: CharSequence,
            delimiter: Char,
            start: Int = 0,
            endExclusive: Int = text.length,
            closingDelimiter: Char? = null,
            trackAngles: Boolean = false
        ): AikenCurrentExpressionSegment {
            val range =
                AikenTopLevelText.currentSegmentRange(
                    text = text,
                    delimiter = delimiter,
                    start = start,
                    endExclusive = endExclusive,
                    closingDelimiter = closingDelimiter,
                    trackAngles = trackAngles
                )
            return fromText(text.subSequence(range.startOffset, range.endOffset))
        }
    }
}
