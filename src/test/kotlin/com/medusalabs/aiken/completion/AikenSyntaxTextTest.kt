package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AikenSyntaxTextTest {
    @Test
    fun extractsIdentifierPrefixAndQualifierBeforeOffset() {
        val text = "let value = math_utils.ad"
        val offset = text.length

        assertEquals("ad", AikenSyntaxText.identifierPrefix(text, offset))
        assertEquals("math_utils", AikenSyntaxText.qualifierBeforeOffset(text, offset))
        assertEquals("math_utils", AikenSyntaxText.qualifiedChainBeforeOffset(text, offset))
    }

    @Test
    fun extractsQualifiedChainBeforeOffset() {
        val text = "let value = math.rational.redu"

        assertEquals("math.rational", AikenSyntaxText.qualifiedChainBeforeOffset(text, text.length))
    }

    @Test
    fun readsLeadingQualifiedIdentifierAndQualifier() {
        val text = "  native_list.count(extra)"

        val trimmedStart = text.indexOf('n')
        val range = AikenSyntaxText.leadingQualifiedIdentifierRange(text, trimmedStart)

        assertEquals("native_list.count", text.substring(range ?: error("Expected range")))
        assertEquals("native_list", AikenSyntaxText.qualifierOfLeadingIdentifier(text))
    }

    @Test
    fun findsLastTopLevelPipeIgnoringNestedGroupsStringsAndComments() {
        val text =
            """
            alpha |> keep("x |> y", wrap(beta |> gamma)) // comment |>
            |> final
            """.trimIndent()

        val pipeOffset = AikenSyntaxText.findLastTopLevelPipeOffset(text)

        assertEquals(text.lastIndexOf("|>"), pipeOffset)
    }

    @Test
    fun findsPipeInsideOuterBlockWhenNestingTrackingIsDisabled() {
        val text =
            """
            fn main() {
              items |> list.map
            }
            """.trimIndent()

        val pipeOffset = AikenSyntaxText.findLastTopLevelPipeOffset(text, text.indexOf("map"), trackNesting = false)

        assertEquals(text.indexOf("|>"), pipeOffset)
    }

    @Test
    fun matchesDelimitersIgnoringStringsAndComments() {
        val text =
            """
            fn sample() {
              trace "}"
              // }
              wrap({ value: 1 })
            }
            """.trimIndent()

        val openBrace = text.indexOf('{')
        val closeBrace = AikenSyntaxText.findMatchingDelimiter(text, openBrace, '{', '}')

        assertEquals(text.lastIndexOf('}'), closeBrace)
    }

    @Test
    fun returnsNullWhenQualifierIsMissing() {
        assertNull(AikenSyntaxText.qualifierBeforeOffset("value", 5))
        assertNull(AikenSyntaxText.qualifierOfLeadingIdentifier("  value"))
    }
}
