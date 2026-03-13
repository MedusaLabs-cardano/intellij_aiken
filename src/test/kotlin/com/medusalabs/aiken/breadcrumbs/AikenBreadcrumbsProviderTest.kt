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
