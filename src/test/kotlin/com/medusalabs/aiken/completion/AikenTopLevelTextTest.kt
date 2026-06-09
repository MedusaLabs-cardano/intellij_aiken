package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AikenTopLevelTextTest {
    @Test
    fun splitsRangesIgnoringNestedCommasAndAngleArguments() {
        val text = "alpha, Pair(beta, gamma), Option<Result<Int, String>>, delta"

        val segments =
            AikenTopLevelText
                .splitRanges(text, ',', trackAngles = true)
                .map { range -> text.substring(range.startOffset, range.endOffset).trim() }

        assertEquals(
            listOf("alpha", "Pair(beta, gamma)", "Option<Result<Int, String>>", "delta"),
            segments
        )
    }

    @Test
    fun findsOnlyTopLevelColon() {
        val text = "predicate: fn(Int, Option<Result<Int, String>>) -> Bool"

        assertEquals(9, AikenTopLevelText.indexOf(text, ':', trackAngles = true))
    }

    @Test
    fun returnsCurrentSegmentRangeBeforeClosingParen() {
        val text = "alpha, Pair(beta, gamma), de)"

        val range =
            AikenTopLevelText.currentSegmentRange(
                text = text,
                delimiter = ',',
                closingDelimiter = ')'
            )

        assertEquals(" de", text.substring(range.startOffset, range.endOffset))
    }

    @Test
    fun returnsNullSegmentIndexAfterTopLevelClosingParen() {
        val text = "alpha, beta)"

        val segmentIndex =
            AikenTopLevelText.segmentIndexAt(
                text = text,
                delimiter = ',',
                endExclusive = text.length,
                closingDelimiter = ')'
            )

        assertNull(segmentIndex)
    }

    @Test
    fun findsEnclosingOpeningThroughNestedGroups() {
        val text = "consume(Foo { amount: wrap(Bar { value: 1 }), other: pr })"
        val offset = text.indexOf("pr") + 2

        val braceOffset = AikenTopLevelText.findEnclosingOpening(text, '{', offset)

        assertEquals(text.indexOf("Foo {") + "Foo ".length, braceOffset)
    }

    @Test
    fun findsExpressionStartBeforePipeInsideGrouping() {
        val text = "wrap(items |> map)"
        val pipeOffset = text.indexOf("|>")

        val start = AikenTopLevelText.findExpressionStartBefore(text, pipeOffset, stopAtPipeOperator = true)

        assertEquals("items ", text.substring(start ?: error("Expected start"), pipeOffset))
    }

    @Test
    fun findsExpressionStartBeforePipeAfterPreviousPipe() {
        val text = "alpha |> beta |> gamma"
        val pipeOffset = text.lastIndexOf("|>")

        val start = AikenTopLevelText.findExpressionStartBefore(text, pipeOffset, stopAtPipeOperator = true)

        assertEquals(" beta ", text.substring(start ?: error("Expected start"), pipeOffset))
    }
}
