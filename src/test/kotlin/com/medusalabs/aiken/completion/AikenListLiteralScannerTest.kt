package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AikenListLiteralScannerTest {
    @Test
    fun infersCurrentContextForOpenListSegment() {
        val source = "let xs = [alpha, be"

        val context =
            AikenListLiteralScanner.inferContext(
                text = source,
                offset = source.length,
                expectedListTypeAtListStart = { _, _ -> "List<Item>" },
                inferSegmentType = { _, _ -> null },
                fallbackElementType = { frame -> frame.expectedListType?.removePrefix("List<")?.removeSuffix(">") }
            )

        requireNotNull(context)
        assertEquals("List<Item>", context.expectedListType)
        assertEquals("be", context.currentSegment.text)
    }

    @Test
    fun usesParentFrameStateWhenEnteringNestedList() {
        val source = "[first, [se"

        val context =
            AikenListLiteralScanner.inferContext(
                text = source,
                offset = source.length,
                expectedListTypeAtListStart = { parent, _ ->
                    if (parent == null) "List<List<Item>>" else "List<Item>"
                },
                inferSegmentType = { startOffset, endExclusive ->
                    source.substring(startOffset, endExclusive).trim().takeIf { it == "first" }?.let { "List<Item>" }
                },
                fallbackElementType = { frame -> frame.expectedListType?.removePrefix("List<")?.removeSuffix(">") }
            )

        requireNotNull(context)
        assertEquals("List<Item>", context.expectedListType)
        assertEquals("se", context.currentSegment.text)
    }

    @Test
    fun returnsNullOutsideListContext() {
        assertNull(
            AikenListLiteralScanner.inferContext(
                text = "let value = alpha",
                offset = 5,
                expectedListTypeAtListStart = { _, _ -> null },
                inferSegmentType = { _, _ -> null },
                fallbackElementType = { null }
            )
        )
    }
}
