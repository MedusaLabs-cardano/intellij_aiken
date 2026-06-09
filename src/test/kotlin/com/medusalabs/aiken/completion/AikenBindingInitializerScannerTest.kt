package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AikenBindingInitializerScannerTest {
    @Test
    fun findsInitializerStartAfterAnnotatedBinding() {
        val source = "let inputs: List<Input> = [value]"

        val start =
            AikenBindingInitializerScanner.findInitializerExpressionStart(
                text = source,
                declarationOffset = source.indexOf("inputs"),
                bindingName = "inputs"
            )

        assertEquals(source.indexOf('['), start)
    }

    @Test
    fun detectsCaretInsideOwnInitializerBeforeTopLevelLineBreak() {
        val source =
            """
            let tx: Transaction = Transaction {
              inputs: [preserved],
            }
            """.trimIndent()
        val caretOffset = source.indexOf("preserved") + 4

        assertTrue(
            AikenBindingInitializerScanner.isInsideOwnInitializer(
                text = source,
                declarationOffset = source.indexOf("tx"),
                bindingName = "tx",
                caretOffset = caretOffset
            )
        )
    }

    @Test
    fun stopsInitializerScanAtTopLevelNewline() {
        val source =
            """
            let values = []
            values
            """.trimIndent()
        val caretOffset = source.lastIndexOf("values") + 2

        assertFalse(
            AikenBindingInitializerScanner.isInsideOwnInitializer(
                text = source,
                declarationOffset = source.indexOf("values"),
                bindingName = "values",
                caretOffset = caretOffset
            )
        )
    }
}
