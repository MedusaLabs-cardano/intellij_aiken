package com.medusalabs.aiken.completion

import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenExpressionTypeInferenceTest : AikenPlatformTestCase() {
    @Test
    fun infersListTypeFromIfExpression() {
        val anchor =
            configureAnchor(
                """
                pub type Input {
                  amount: Int,
                }

                fn main(flag: Bool, input1: Input, input2: Input) {
                  in<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "if flag { [input1] } else { [input2] }"
            )

        assertEquals("List<Input>", inferredType)
    }

    @Test
    fun infersTypeFromWhenExpression() {
        val anchor =
            configureAnchor(
                """
                pub type Input {
                  amount: Int,
                }

                fn main(flag: Bool, input1: Input, input2: Input) {
                  in<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText =
                    """
                    when flag is {
                      True -> input1
                      False -> input2
                    }
                    """.trimIndent()
            )

        assertEquals("Input", inferredType)
    }

    @Test
    fun infersTypeFromPipeRightHandSide() {
        val anchor =
            configureAnchor(
                """
                pub type Input {
                  amount: Int,
                }

                fn reverse(items: List<Input>) -> List<Input> {
                  items
                }

                fn main(input1: Input) {
                  re<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "[input1] |> reverse()"
            )

        assertEquals("List<Input>", inferredType)
    }

    @Test
    fun infersTypeFromPipeIntoBareFunctionValue() {
        val anchor =
            configureAnchor(
                """
                pub type Input {
                  amount: Int,
                }

                fn reverse(items: List<Input>) {
                  items
                }

                fn main(input1: Input) {
                  re<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "[input1] |> reverse"
            )

        assertEquals("List<Input>", inferredType)
    }

    @Test
    fun infersTypeFromPipeIntoBoundFunctionValue() {
        val anchor =
            configureAnchor(
                """
                pub type Input {
                  amount: Int,
                }

                fn reverse(items: List<Input>) {
                  items
                }

                fn main(input1: Input) {
                  let transformer = reverse
                  tr<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "[input1] |> transformer"
            )

        assertEquals("List<Input>", inferredType)
    }

    @Test
    fun infersIntFromArithmeticExpression() {
        val anchor =
            configureAnchor(
                """
                fn main(left: Int, right: Int) {
                  le<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "left + right * 2"
            )

        assertEquals("Int", inferredType)
    }

    @Test
    fun infersBoolFromComparisonAndLogicalExpression() {
        val anchor =
            configureAnchor(
                """
                fn main(left: Int, right: Int, flag: Bool) {
                  fl<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "left > right && flag"
            )

        assertEquals("Bool", inferredType)
    }

    @Test
    fun infersBoolFromTraceIfFalseExpression() {
        val anchor =
            configureAnchor(
                """
                fn main(flag: Bool) {
                  fl<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "flag?"
            )

        assertEquals("Bool", inferredType)
    }

    @Test
    fun infersByteArrayFromPlainQuotedLiteral() {
        val anchor =
            configureAnchor(
                """
                fn main() {
                  by<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "\"test\""
            )

        assertEquals("ByteArray", inferredType)
    }

    @Test
    fun infersStringFromAtQuotedLiteral() {
        val anchor =
            configureAnchor(
                """
                fn main() {
                  st<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "@\"test\""
            )

        assertEquals("String", inferredType)
    }

    @Test
    fun infersTypeThroughTraceContinuation() {
        val anchor =
            configureAnchor(
                """
                fn main(flag: Bool, value: Int) {
                  fl<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText =
                    """
                    trace flag
                    value + 1
                    """.trimIndent()
            )

        assertEquals("Int", inferredType)
    }

    @Test
    fun infersTupleTypeFromTupleLiteral() {
        val anchor =
            configureAnchor(
                """
                fn main(flag: Bool) {
                  fl<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "(1, True)"
            )

        assertEquals("(Int, Bool)", inferredType)
    }

    @Test
    fun infersPairTypeFromPairLiteral() {
        val anchor =
            configureAnchor(
                """
                fn main(flag: Bool) {
                  fl<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "Pair(1, True)"
            )

        assertEquals("Pair<Int, Bool>", inferredType)
    }

    @Test
    fun infersTypeFromFieldAccess() {
        val anchor =
            configureAnchor(
                """
                pub type User {
                  age: Int,
                }

                fn main(user: User) {
                  us<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "user.age"
            )

        assertEquals("Int", inferredType)
    }

    @Test
    fun infersTypeFromTupleAndPairIndex() {
        val anchor =
            configureAnchor(
                """
                fn main() {
                  pa<caret>
                }
                """.trimIndent()
            )

        val tupleType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "(1, True).2nd"
            )
        val pairType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "Pair(1, True).2nd"
            )

        assertEquals("Bool", tupleType)
        assertEquals("Bool", pairType)
    }

    @Test
    fun infersFunctionTypeFromBareFunctionValue() {
        val anchor =
            configureAnchor(
                """
                fn increment(value: Int) {
                  value + 1
                }

                fn main() {
                  in<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "increment"
            )

        assertEquals("fn(Int) -> Int", inferredType)
    }

    @Test
    fun infersFunctionTypeFromQualifiedImportedFunctionValue() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn increment(value: Int) {
              value + 1
            }
            """.trimIndent()
        )

        val anchor =
            configureAnchor(
                """
                use math as ops

                fn main() {
                  op<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "ops.increment"
            )

        assertEquals("fn(Int) -> Int", inferredType)
    }

    @Test
    fun infersTypeFromUnannotatedFunctionBodyWithNestedBlocksAndWhenBranches() {
        val anchor =
            configureAnchor(
                """
                pub type Input {
                  amount: Int,
                }

                fn choose(flag: Bool, input1: Input, input2: Input) {
                  if flag {
                    {
                      input1
                    }
                  } else {
                    when flag is {
                      True -> input2
                      False -> {
                        input1
                      }
                    }
                  }
                }

                fn main(flag: Bool, input1: Input, input2: Input) {
                  ch<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "choose(flag, input1, input2)"
            )

        assertEquals("Input", inferredType)
    }

    @Test
    fun infersIntFromUnannotatedFunctionReturningLiteral() {
        val anchor =
            configureAnchor(
                """
                fn count() {
                  42
                }

                fn main() {
                  co<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "count()"
            )

        assertEquals("Int", inferredType)
    }

    @Test
    fun infersVoidFromUnannotatedFunctionEndingWithExpect() {
        val anchor =
            configureAnchor(
                """
                fn ensure(flag: Bool) {
                  expect flag == True
                }

                fn main(flag: Bool) {
                  en<caret>
                }
                """.trimIndent()
            )

        val inferredType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(
                anchor = anchor,
                expressionText = "ensure(flag)"
            )

        assertEquals("Void", inferredType)
    }

    private fun configureAnchor(source: String) =
        findElementAtCaret(myFixture.configureByText("main.ak", source))
            ?: error("Expected anchor at caret")
}
