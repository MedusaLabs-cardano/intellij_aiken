package com.medusalabs.aiken.breadcrumbs

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenBreadcrumbsProviderTest : AikenPlatformTestCase() {
    @Test
    fun breadcrumbsIncludeTypedFunctionSignature() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                pub fn compute(value: Int) -> Int {
                  let res<caret>ult = value + 1
                  result
                }
                """.trimIndent()
            )

        val labels = breadcrumbLabelsAtCaret(file)

        assertTrue(labels.toString(), labels.contains("pub fn compute(value: Int) -> Int"))
    }

    @Test
    fun breadcrumbsIncludeWhenAndArrowScopes() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                fn decide(value: Int) -> Int {
                  when value is {
                    0 -> {
                      let ins<caret>ide = 1
                      inside
                    }
                    _ -> {
                      value
                    }
                  }
                }
                """.trimIndent()
            )

        val labels = breadcrumbLabelsAtCaret(file)

        assertTrue(labels.toString(), labels.contains("fn decide(value: Int) -> Int"))
        assertTrue(labels.toString(), labels.any { it.contains("when value is") })
        assertTrue(labels.toString(), labels.any { it.trim().startsWith("0") })
    }

    @Test
    fun breadcrumbsDoNotCreateParentCycleThroughPsiFile() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                use cardano/transaction.{Transaction}
                
                validator contract {
                  mint(redeemer: Int, tx: Transaction) {
                    when redeemer is {
                      0 -> {
                        let ins<caret>ide = tx
                        inside
                      }
                      _ -> tx
                    }
                  }
                }
                """.trimIndent()
            )

        val provider = AikenBreadcrumbsProvider()
        val leaf = findElementAtCaret(file) ?: error("Element at caret not found")
        val visited = HashSet<PsiElement>()
        var current: PsiElement? = leaf
        var steps = 0

        while (current != null) {
            assertTrue("Breadcrumb parent chain contains a cycle at $current", visited.add(current))
            assertTrue("Breadcrumb parent chain is unexpectedly deep", steps++ < 20)
            current = provider.getParent(current)
        }
    }

    @Test
    fun breadcrumbsDoNotBuildSyntheticParentForValidatorFileWithoutLeadingImports() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                validator forever_false {
                  spend(_d, _r, _u, _t) {
                    Fal<caret>se
                  }
                
                  else(_) {
                    fail
                  }
                }
                """.trimIndent()
            )

        val provider = AikenBreadcrumbsProvider()

        assertFalse(provider.acceptElement(file))
        assertNull(provider.getParent(file))
    }

    private fun breadcrumbLabelsAtCaret(file: PsiFile): List<String> {
        val provider = AikenBreadcrumbsProvider()
        val leaf = findElementAtCaret(file) ?: error("Element at caret not found")
        val labels = ArrayList<String>()

        var current: PsiElement? = provider.getParent(leaf)
        while (current != null && current !is PsiFile) {
            labels += provider.getElementInfo(current)
            current = provider.getParent(current)
        }

        return labels
    }
}
