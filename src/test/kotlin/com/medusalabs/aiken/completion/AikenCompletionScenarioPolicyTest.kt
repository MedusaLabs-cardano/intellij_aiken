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
        assertFalse(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertFalse(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyInTypeAnnotationPosition() {
        val policy =
            scenarioPolicy(
                """
                pub type VerificationKey = ByteArray

                fn main(key: Ver<caret>) -> VerificationKey {
                  key
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyAtFunctionParameterDeclarationStart() {
        val policy =
            scenarioPolicy(
                """
                fn main(<caret>) -> Int {
                  0
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyAfterCommaInFunctionParameterDeclaration() {
        val policy =
            scenarioPolicy(
                """
                fn main(seed: Int, <caret>) -> Int {
                  seed
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyInFunctionReturnAnnotationPosition() {
        val policy =
            scenarioPolicy(
                """
                fn main(key: Int) -> <caret> {
                  key
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyInAnonymousFunctionArgumentReturnAnnotationPosition() {
        val policy =
            scenarioPolicy(
                """
                fn apply(predicate: fn(Int) -> Bool) -> Bool {
                  predicate(1)
                }

                fn main(key: Bool) -> Bool {
                  apply(fn(value: Int) -> Bo<caret> {
                    key
                  })
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyInAssignedAnonymousFunctionReturnAnnotationPosition() {
        val policy =
            scenarioPolicy(
                """
                fn main(key: Bool) -> Bool {
                  let predicate = fn(value: Int) -> Bo<caret> {
                    key
                  }
                  predicate(1)
                }
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun usesTypeOnlyPolicyInConstAnnotationPosition() {
        val policy =
            scenarioPolicy(
                """
                const always: <caret> = True
                """.trimIndent()
            )

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertTrue(policy.bareTypesAllowed)
        assertTrue(policy.typeOnlySuggestions)
        assertTrue(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
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
        assertFalse(policy.typeOnlySuggestions)
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
        assertFalse(policy.typeOnlySuggestions)
        assertFalse(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    @Test
    fun suppressesEverythingForUseContexts() {
        val policy = scenarioPolicy("use cardano/transaction.{Tra<caret>}")

        assertEquals(AikenKeywordVisibility.NONE, policy.keywordVisibility)
        assertFalse(policy.bareTypesAllowed)
        assertFalse(policy.typeOnlySuggestions)
        assertFalse(policy.lexicalFallbackAllowed)
        assertTrue(policy.typedCompletionStopsFurtherMerging)
    }

    private fun scenarioPolicy(source: String): AikenCompletionScenarioPolicy {
        val file = myFixture.configureByText("main.ak", source)
        return AikenCompletionScenarioPolicies.forFile(file, myFixture.caretOffset)
    }
}
