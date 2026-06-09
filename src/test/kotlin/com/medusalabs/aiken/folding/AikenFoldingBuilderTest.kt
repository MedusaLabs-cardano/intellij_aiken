package com.medusalabs.aiken.folding

import com.intellij.psi.PsiDocumentManager
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenFoldingBuilderTest : AikenPlatformTestCase() {
    @Test
    fun foldsTypedFunctionBody() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                pub fn compute(value: Int) -> Int {
                  let result = value + 1
                  result
                }
                """.trimIndent()
            )
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: error("Document not found")
        val functionOpenBrace = file.text.indexOf('{')
        val bodyOffset = file.text.indexOf("let result = value + 1")

        val ranges = AikenFoldingBuilder().buildFoldRegions(file, document, false).map { it.range }

        assertTrue(ranges.toString(), ranges.any { it.startOffset == functionOpenBrace + 1 && it.endOffset > bodyOffset })
    }

    @Test
    fun foldsWhenArrowBranches() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                fn decide(value: Int) -> Int {
                  when value is {
                    0 -> {
                      let nested = 1
                      nested
                    }
                    _ -> {
                      value
                    }
                  }
                }
                """.trimIndent()
            )
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: error("Document not found")

        val firstArrowStart = document.getLineEndOffset(document.getLineNumber(file.text.indexOf("0 -> {")))
        val secondArrowStart = document.getLineEndOffset(document.getLineNumber(file.text.indexOf("_ -> {")))
        val ranges = AikenFoldingBuilder().buildFoldRegions(file, document, false).map { it.range }

        assertTrue(
            ranges.toString(),
            ranges.any { it.startOffset == firstArrowStart && document.getText(it).contains("let nested = 1") }
        )
        assertTrue(
            ranges.toString(),
            ranges.any { it.startOffset == secondArrowStart && document.getText(it).contains("value") }
        )
    }
}
