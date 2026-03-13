package com.medusalabs.aiken.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenStructureViewFactoryTest : AikenPlatformTestCase() {
    @Test
    fun showsTypedTopLevelSymbolsAndNestedConstructors() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val file =
            myFixture.configureByText(
                "main.ak",
                """
                pub fn compute(left: Int, right: Int) -> Int {
                  left + right
                }

                const give_i: fn(Int) -> Foo =
                  fn(value: Int) -> Foo {
                    Foo(value)
                  }

                type Choice {
                  This
                  That(Int)
                }

                type Foo {
                  Foo(Int)
                }

                test example() {
                  True
                }
                """.trimIndent()
            )

        withStructureRoot(file) { root ->
            val children = root.children.map { it.presentation.presentableText }

            assertTrue("children=$children", children.contains("compute(left: Int, right: Int) -> Int"))
            assertTrue("children=$children", children.contains("give_i(Int) -> Foo"))
            assertTrue("children=$children", children.contains("Choice"))
            assertTrue("children=$children", children.contains("Foo"))
            assertTrue("children=$children", children.contains("example()"))
            assertFalse(children.contains("This"))
            assertFalse(children.contains("That"))

            val choiceNode = root.children.single { it.presentation.presentableText == "Choice" }
            val constructorNames = choiceNode.children.map { it.presentation.presentableText }.toSet()
            assertEquals(setOf("That", "This"), constructorNames)
        }
    }

    @Test
    fun fallsBackToValidatorNameWhenSignatureIsNotFunctionLike() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val file =
            myFixture.configureByText(
                "validator.ak",
                """
                use aiken/collection/list
                use aiken/crypto.{VerificationKeyHash}
                use cardano/transaction.{OutputReference, Transaction}

                pub type Datum {
                  owner: VerificationKeyHash,
                }

                pub type Redeemer {
                  msg: ByteArray,
                }

                validator hello_world {
                  spend(
                    datum: Option<Datum>,
                    redeemer: Redeemer,
                    _: OutputReference,
                    transaction: Transaction,
                  ) {
                    let must_be_signed = list.has(transaction.extra_signatories, datum.owner)
                    redeemer.msg == "Hello" && must_be_signed
                  }

                  else(_) {
                    fail
                  }
                }
                """.trimIndent()
            )

        withStructureRoot(file) { root ->
            val children = root.children.map { it.presentation.presentableText }

            assertTrue(children.contains("hello_world"))
            val validatorNode = root.children.single { it.presentation.presentableText == "hello_world" }
            assertEquals("function", validatorNode.presentation.locationString)
        }
    }

    private fun withStructureRoot(file: PsiFile, block: (StructureViewTreeElement) -> Unit) {
        val factory = AikenStructureViewFactory()
        val builder =
            factory.getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
                ?: error("Tree-based structure view builder was not created")
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            block(model.root as StructureViewTreeElement)
        } finally {
            model.dispose()
        }
    }
}
