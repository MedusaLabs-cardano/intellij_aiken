package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AikenCurrentExpressionSegmentTest {
    @Test
    fun trimsPlainSegment() {
        val segment = AikenCurrentExpressionSegment.fromRange("  value  ", 0, "  value  ".length)

        assertEquals("value", segment.text)
        assertFalse(segment.isSpread)
        assertEquals("value", segment.effectiveValueText)
    }

    @Test
    fun detectsSpreadAndStripsPrefixForEffectiveValue() {
        val source = "  ..remembered  "
        val segment = AikenCurrentExpressionSegment.fromRange(source, 0, source.length)

        assertEquals("..remembered", segment.text)
        assertTrue(segment.isSpread)
        assertEquals("remembered", segment.effectiveValueText)
    }

    @Test
    fun extractsCurrentDelimitedSegmentRespectingNestedCalls() {
        val source = "first, Pair(left, right),  target  "
        val segment =
            AikenCurrentExpressionSegment.fromDelimitedRange(
                text = source,
                delimiter = ',',
                trackAngles = true
            )

        assertEquals("target", segment.text)
        assertFalse(segment.isSpread)
    }

    @Test
    fun stopsDelimitedSegmentBeforeClosingDelimiter() {
        val source = "call(first,  ..rest )"
        val segment =
            AikenCurrentExpressionSegment.fromDelimitedRange(
                text = source,
                delimiter = ',',
                start = source.indexOf('(') + 1,
                endExclusive = source.length,
                closingDelimiter = ')'
            )

        assertEquals("..rest", segment.text)
        assertTrue(segment.isSpread)
        assertEquals("rest", segment.effectiveValueText)
    }
}
