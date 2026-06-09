package com.medusalabs.aiken.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenGotoDeclarationTest : AikenPlatformTestCase() {
    @Test
    fun gotoDeclarationFromImportedTypeUsagePrefersImportEntry() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/transaction.ak",
            """
            pub type Transaction {
              Transaction
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use transaction.{Transaction}

            fn main(self: Trans<caret>action) {
              self
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val targets = gotoTargetsAtCaret()

        assertNotNull(targets)
        assertEquals(mainFile.virtualFile, targets!!.containingFile?.virtualFile)
        assertEquals(mainFile.text.indexOf("{Transaction}") + 1, targets.textRange.startOffset)
        assertEquals("Transaction", targets.text)
    }

    @Test
    fun gotoDeclarationFromImportedTypeInUseStatementNavigatesToSourceDeclaration() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/transaction.ak",
            """
            pub type Transaction {
              Transaction
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use transaction.{Trans<caret>action}

            fn main(self: Transaction) {
              self
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val targets = gotoTargetsAtCaret()

        assertNotNull(targets)
        assertEquals("transaction.ak", targets!!.containingFile?.name)
        assertEquals(mainFile.virtualFile, myFixture.file.virtualFile)
        assertEquals("Transaction", targets.text)
    }

    @Test
    fun gotoDeclarationFromQualifiedImportedMemberPrefersModuleImportPoint() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
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
            use second as chosen

            fn main() -> Int {
              chosen.comp<caret>ute(1, 2, 3)
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val targets = gotoTargetsAtCaret()

        assertNotNull(targets)
        assertEquals(mainFile.virtualFile, targets!!.containingFile?.virtualFile)
        assertEquals(mainFile.text.indexOf("chosen"), targets.textRange.startOffset)
        assertEquals("chosen", targets.text)
    }

    @Test
    fun gotoDeclarationFromImportedFunctionPrefersImportEntry() {
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

        val targets = gotoTargetsAtCaret()

        assertNotNull(targets)
        assertEquals(mainFile.virtualFile, targets!!.containingFile?.virtualFile)
        assertEquals(mainFile.text.indexOf("{compute}") + 1, targets.textRange.startOffset)
        assertEquals("compute", targets.text)
    }

    @Test
    fun gotoDeclarationFromQualifiedModuleAliasCallPrefersImportAlias() {
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

        val targets = gotoTargetsAtCaret()

        assertNotNull(targets)
        assertEquals(mainFile.virtualFile, targets!!.containingFile?.virtualFile)
        assertEquals(mainFile.text.indexOf("chosen"), targets.textRange.startOffset)
        assertEquals("chosen", targets.text)
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

        val targets = gotoTargetsAtCaret()
        val usagesTarget = GotoDeclarationAction.findElementToShowUsagesOf(myFixture.editor, myFixture.caretOffset)

        assertNull(targets)
        assertNotNull(usagesTarget)
        assertSameLocation(namedTargetAtCaret(file), usagesTarget!!)
    }

    private fun gotoTargetsAtCaret(): PsiElement? =
        GotoDeclarationAction.findTargetElement(
            project,
            myFixture.editor,
            myFixture.caretOffset
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
