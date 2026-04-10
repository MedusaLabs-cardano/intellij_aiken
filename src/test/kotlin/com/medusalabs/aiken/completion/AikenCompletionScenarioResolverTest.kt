package com.medusalabs.aiken.completion

import com.medusalabs.aiken.AikenPlatformTestCase
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

    private fun resolveScenario(source: String): AikenCompletionResolution {
        val file = myFixture.configureByText("main.ak", source)
        return AikenCompletionScenarioResolver.resolve(file, myFixture.caretOffset)
    }
}
