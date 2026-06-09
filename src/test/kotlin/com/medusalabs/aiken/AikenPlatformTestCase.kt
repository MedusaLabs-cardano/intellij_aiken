package com.medusalabs.aiken

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import java.nio.file.Path

abstract class AikenPlatformTestCase : LightPlatformCodeInsightFixture4TestCase() {
    override fun getTestDataPath(): String =
        Path.of(System.getProperty("user.dir"), "src", "test", "testData").toString()

    protected fun findElementAtCaret(file: PsiFile): PsiElement? {
        val offset = myFixture.editor.caretModel.offset.coerceAtLeast(0)
        return file.findElementAt((offset - 1).coerceAtLeast(0)) ?: file.findElementAt(offset)
    }
}
