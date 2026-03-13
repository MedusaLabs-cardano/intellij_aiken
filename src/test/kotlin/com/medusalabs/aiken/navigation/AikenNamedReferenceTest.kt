package com.medusalabs.aiken.navigation

import com.intellij.psi.PsiReference
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenNamedReferenceTest : AikenPlatformTestCase() {
    @Test
    fun resolvesImportedFunctionReferenceToMatchingModuleWhenNamesCollide() {
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

        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
        val target = reference.resolve()

        assertNotNull(target)
        assertEquals("compute", target!!.text)
        assertEquals("second.ak", target.containingFile?.name)
    }

    @Test
    fun resolvesQualifiedFunctionReferenceViaModuleAlias() {
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

            fn main(current: Int) -> Int {
              chosen.comp<caret>ute(1, 2, 3)
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
        val target = reference.resolve()

        assertNotNull(target)
        assertEquals("compute", target!!.text)
        assertEquals("second.ak", target.containingFile?.name)
    }

    @Test
    fun declarationTokenDoesNotExposeReference() {
        myFixture.configureByText(
            "main.ak",
            """
            fn comp<caret>ute(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )

        val reference: PsiReference? = myFixture.getReferenceAtCaretPosition()
        assertNull(reference)
    }
}
