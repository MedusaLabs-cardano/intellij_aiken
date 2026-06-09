package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AikenFunctionBodyScannerTest {
    @Test
    fun findsBodyForGenericUnannotatedFunction() {
        val source =
            """
            fn choose<a>(flag: Bool, left: a, right: a) {
              if flag {
                left
              } else {
                right
              }
            }
            """.trimIndent()

        val callable = AikenFunctionBodyScanner.findNamedCallableBody(source, "choose")

        assertNotNull(callable)
        assertEquals(
            """
            {
              if flag {
                left
              } else {
                right
              }
            }
            """.trimIndent(),
            source.substring(callable!!.bodyRange.startOffset, callable.bodyRange.endOffset)
        )
    }

    @Test
    fun findsBodyForFailingTestCallable() {
        val source =
            """
            test ensure() fail {
              expect 1 == 1
            }
            """.trimIndent()

        val callable = AikenFunctionBodyScanner.findNamedCallableBody(source, "ensure")

        assertNotNull(callable)
        assertEquals(
            """
            {
              expect 1 == 1
            }
            """.trimIndent(),
            source.substring(callable!!.bodyRange.startOffset, callable.bodyRange.endOffset)
        )
    }
}
