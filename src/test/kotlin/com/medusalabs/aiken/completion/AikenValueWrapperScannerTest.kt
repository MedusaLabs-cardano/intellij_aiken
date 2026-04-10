package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AikenValueWrapperScannerTest {
    @Test
    fun keepsOptionSomeWrapperOpen() {
        val wrappers = AikenValueWrapperScanner.scan("Some(ba")

        assertEquals(listOf(AikenValueWrapperScanner.Wrapper.OptionSome), wrappers)
    }

    @Test
    fun marksOpenListWrapperAsSpreadWhenCurrentSegmentStartsWithDots() {
        val wrappers = AikenValueWrapperScanner.scan("Some([input, ..rem")

        assertEquals(2, wrappers.size)
        assertEquals(AikenValueWrapperScanner.Wrapper.OptionSome, wrappers.first())
        val listWrapper = wrappers.last() as? AikenValueWrapperScanner.Wrapper.ListLiteral
            ?: error("Expected list wrapper")
        assertTrue(listWrapper.currentSegmentIsSpread)
    }

    @Test
    fun ignoresSomeInsideIdentifierPrefix() {
        val wrappers = AikenValueWrapperScanner.scan("awesome(value")

        assertTrue(wrappers.isEmpty())
    }
}
