package com.medusalabs.aiken.completion

import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.imports.AikenUseStatementParser
import org.junit.Test

class AikenCompletionScenarioResolverTest : AikenPlatformTestCase() {
    @Test
    fun resolvesUseModuleScenario() {
        val resolution = resolveScenario("use card<caret>")

        assertEquals(AikenCompletionScenario.UseModule, resolution.scenario)
    }

    @Test
    fun resolvesUseSymbolScenario() {
        val resolution = resolveScenario("use cardano/transaction.{Tra<caret>}")

        assertEquals(AikenCompletionScenario.UseSymbol, resolution.scenario)
    }

    @Test
    fun doesNotTreatUsePrefixIdentifierAsUseCompletion() {
        val resolution =
            resolveScenario(
                """
                fn main(user_output: Int) {
                  user<caret>
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.OrdinaryExpression, resolution.scenario)
        assertEquals("user", resolution.prefix)
    }

    @Test
    fun keepsKeywordLikeIdentifiersInOrdinaryExpressionScenario() {
        val keywordLikePrefixes =
            listOf(
                "useful",
                "whenx",
                "ifx",
                "elsex",
                "letdown",
                "expected",
                "fnx",
                "validatorx",
                "testx",
                "constx",
                "public"
            )

        for (prefix in keywordLikePrefixes) {
            val resolution =
                resolveScenario(
                    """
                    fn main($prefix: Int) {
                      $prefix<caret>
                    }
                    """.trimIndent()
                )

            assertEquals("Expected ordinary expression for `$prefix`", AikenCompletionScenario.OrdinaryExpression, resolution.scenario)
            assertEquals(prefix, resolution.prefix)
        }
    }

    @Test
    fun suppressesCompletionForTopLevelDeclarationNames() {
        val sources =
            listOf(
                "const user<caret>",
                "fn use_value<caret>",
                "pub fn type_value<caret>",
                "type User<caret>",
                "pub opaque type Hidden<caret>",
                "validator contract<caret>",
                "test success<caret>",
                "bench measure<caret>"
            )

        for (source in sources) {
            val resolution = resolveScenario(source)

            assertEquals("Expected no suggestions for `$source`", AikenCompletionScenario.NoSuggestions, resolution.scenario)
            assertEquals(AikenKeywordVisibility.NONE, resolution.policy.keywordVisibility)
        }
    }

    @Test
    fun doesNotSuppressCompletionAfterTopLevelDeclarationName() {
        val sources =
            listOf(
                "const user = us<caret>",
                "const user: In<caret>",
                "fn use_value(input: In<caret>)",
                "type User { field: In<caret> }"
            )

        for (source in sources) {
            val resolution = resolveScenario(source)

            assertNotSame("Did not expect declaration-name suppression for `$source`", AikenCompletionScenario.NoSuggestions, resolution.scenario)
        }
    }

    @Test
    fun useStatementParserRequiresKeywordBoundary() {
        val statements =
            AikenUseStatementParser.parse(
                """
                fn main(user_output: Int) {
                  user_output
                }
                """.trimIndent()
            )

        assertTrue("Did not expect `user` identifier to be parsed as use statement: $statements", statements.isEmpty())
    }

    @Test
    fun prefersRecordFieldValueOverOuterFunctionArgumentContext() {
        val resolution =
            resolveScenario(
                """
                fn consume(value) {
                  value
                }

                fn main() {
                  consume(Foo { amount: ba<caret> })
                }
                """.trimIndent()
            )

        assertTrue(resolution.scenario is AikenCompletionScenario.RecordFieldValue)
    }

    @Test
    fun prefersNestedArgumentContextInsideRecordFieldValue() {
        val resolution =
            resolveScenario(
                """
                fn wrap(value) {
                  value
                }

                fn main() {
                  let prefix = 1
                  Foo { amount: wrap(pr<caret>) }
                }
                """.trimIndent()
        )

        assertEquals(AikenCompletionScenario.FunctionArgument, resolution.scenario)
    }

    @Test
    fun keepsRecordFieldValueScenarioForSomeWrapper() {
        val resolution =
            resolveScenario(
                """
                fn main() {
                  let prefix = Some(1)
                  Foo { amount: Some(pr<caret>) }
                }
                """.trimIndent()
            )

        assertTrue(resolution.scenario is AikenCompletionScenario.RecordFieldValue)
    }

    @Test
    fun prefersListItemOverOuterFunctionArgumentContext() {
        val resolution =
            resolveScenario(
                """
                fn consume(items) {
                  items
                }

                fn main() {
                  consume([it<caret>])
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.ListItem, resolution.scenario)
    }

    @Test
    fun resolvesPipeTargetBeforeQualifiedAccess() {
        val resolution =
            resolveScenario(
                """
                fn main() {
                  items |> list.ma<caret>
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.PipeTarget, resolution.scenario)
    }

    @Test
    fun resolvesQualifiedAccessOutsidePipeContext() {
        val resolution =
            resolveScenario(
                """
                fn main() {
                  list.ma<caret>
                }
                """.trimIndent()
            )

        val scenario = resolution.scenario as? AikenCompletionScenario.QualifiedAccess
            ?: error("Expected qualified access scenario, got ${resolution.scenario}")
        assertEquals("list", scenario.qualifier)
    }

    @Test
    fun keepsTypedBindingInitializerPrefixForPartialIdentifier() {
        val resolution =
            resolveScenario(
                """
                fn main() {
                  let len2: List<Bool> = reduc<caret>
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.OrdinaryExpression, resolution.scenario)
        assertEquals("reduc", resolution.prefix)
    }

    @Test
    fun resolvesOrdinaryExpressionAfterDestructuringBindingStatement() {
        val resolution =
            resolveScenario(
                """
                pub type Output {
                  datum: Int,
                }

                pub type Input {
                  output: Output,
                }

                pub fn qwer(Input { output, .. }, redeemerq: Int) -> Bool {
                  let Output { datum, .. } = output
                  wh<caret>
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.OrdinaryExpression, resolution.scenario)
        assertEquals("wh", resolution.prefix)
        assertEquals(AikenKeywordVisibility.ALL, resolution.policy.keywordVisibility)
    }

    @Test
    fun resolvesTypeReferenceAtFunctionParameterDeclarationStart() {
        val resolution =
            resolveScenario(
                """
                fn main(<caret>) -> Int {
                  0
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.TypeReference, resolution.scenario)
    }

    @Test
    fun resolvesTypeReferenceAfterCommaInFunctionParameterDeclaration() {
        val resolution =
            resolveScenario(
                """
                fn main(seed: Int, <caret>) -> Int {
                  seed
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.TypeReference, resolution.scenario)
    }

    @Test
    fun resolvesTypeReferenceAtUnfinishedLetPatternDeclarationStart() {
        val resolution =
            resolveScenario(
                """
                fn main() {
                  let Outp<caret>
                  True
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.TypeReference, resolution.scenario)
        assertEquals("Outp", resolution.prefix)
    }

    @Test
    fun resolvesTypeReferenceAtExpectPatternDeclarationStart() {
        val resolution =
            resolveScenario(
                """
                fn main(output) {
                  expect Outp<caret> = output
                  True
                }
                """.trimIndent()
            )

        assertEquals(AikenCompletionScenario.TypeReference, resolution.scenario)
        assertEquals("Outp", resolution.prefix)
    }

    private fun resolveScenario(source: String): AikenCompletionResolution {
        val file = myFixture.configureByText("main.ak", source)
        return AikenCompletionScenarioResolver.resolve(file, myFixture.caretOffset)
    }
}
