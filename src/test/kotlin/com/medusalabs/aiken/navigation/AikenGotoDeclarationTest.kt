package com.medusalabs.aiken.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiElement
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenGotoDeclarationTest : AikenPlatformTestCase() {
    @Test
    fun gotoDeclarationMatchesReferenceResolveForImportedFunction() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/first.ak",
            """
            pub fn compute(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/second.ak",
            """
            pub fn compute(left: Int, right: Int, carry: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use second.{compute}

            fn main() {
              comp<caret>ute(1, 2, 3)
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val expected = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        val targets = gotoTargetsAtCaret()

        assertSingleTargetMatches(expected, targets)
    }

    @Test
    fun gotoDeclarationMatchesReferenceResolveForQualifiedModuleAliasCall() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/second.ak",
            """
            pub fn helper(seed: Int) -> Void {
              Void
            }

            pub fn compute(left: Int, right: Int, carry: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use second.{helper} as chosen

            fn main(seed: Int) -> Int {
              chosen.comp<caret>ute(1, 2, 3)
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val expected = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        val targets = gotoTargetsAtCaret()

        assertSingleTargetMatches(expected, targets)
    }

    @Test
    fun gotoDeclarationFindsShadowedLocalBinding() {
        myFixture.configureByText(
            "main.ak",
            """
            const value = 0

            fn outer(value: Int) -> Int {
              let value = 1
              if True {
                let value = 2
                val<caret>ue * 10
              }
              value
            }
            """.trimIndent()
        )

        val expected = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        val targets = gotoTargetsAtCaret()

        assertSingleTargetMatches(expected, targets)
    }

    @Test
    fun declarationTokenResolvesToItself() {
        val file =
            myFixture.configureByText(
            "main.ak",
            """
            fn comp<caret>ute(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
            )

        val expected = namedTargetAtCaret(file)
        val targets = gotoTargetsAtCaret()

        assertNotNull(targets)
        assertSameLocation(expected, targets!!)
    }

    private fun gotoTargetsAtCaret(): PsiElement? =
        TargetElementUtil.findTargetElement(
            myFixture.editor,
            TargetElementUtil.getInstance().definitionSearchFlags
        )

    private fun assertSingleTargetMatches(expected: PsiElement?, targets: PsiElement?) {
        assertNotNull(expected)
        assertNotNull(targets)
        assertSameLocation(expected!!, targets!!)
    }

    private fun assertSameLocation(expected: PsiElement, actual: PsiElement) {
        assertEquals(expected.containingFile?.virtualFile, actual.containingFile?.virtualFile)
        assertEquals(expected.textRange, actual.textRange)
        assertEquals(expected.text, actual.text)
    }

    private fun namedTargetAtCaret(file: com.intellij.psi.PsiFile): PsiElement {
        val element = findElementAtCaret(file) ?: error("Target at caret not found")
        return element.parent ?: element
    }
}
