package com.medusalabs.aiken.completion

import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenCompletionScenarioPolicyTest : AikenPlatformTestCase() {
    @Test
    fun usesExpressionOnlyKeywordsAndDisallowsBareTypesInValueExpressions() {
        val policy =
            scenarioPolicy(
                """
                fn main() {
                  let value = <caret>
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.EXPRESSION_ONLY, policy.keywordVisibility)
        assertFalse(policy.bareTypesAllowed)
        assertTrue(policy.lexicalFallbackAllowed)
        assertFalse(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun allowsAllKeywordsAndBareTypesInTypeAnnotationPosition() {
        val policy =
            scenarioPolicy(
                """
                pub type VerificationKey = ByteArray

                fn main(key: Ver<caret>) -> VerificationKey {
                  key
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.ALL, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.lexicalFallbackAllowed)
    }

    @Test
    fun suppressesKeywordsInsideListItemsAndStopsFurtherMerging() {
        val policy =
            scenarioPolicy(
                """
                fn main() {
                  let values = [<caret>]
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertFalse(policy.bareTypesAllowed)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun suppressesKeywordsAndLexicalFallbackForQualifiedAccess() {
        val policy =
            scenarioPolicy(
                """
                fn main() {
                  list.ma<caret>
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertFalse(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun suppressesEverythingForUseContexts() {
        val policy = scenarioPolicy("use cardano/transaction.{Tra<caret>}")

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertFalse(policy.bareTypesAllowed)
        assertFalse(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    private fun scenarioPolicy(source: String): AikenCompletionScenarioPolicy {
        val file = myFixture.configureByText("main.ak", source)
        return AikenCompletionScenarioPolicies.forFile(file, myFixture.caretOffset)
    }
}
