package com.medusalabs.aiken.completion

import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenUseCompletionProviderTest : AikenPlatformTestCase() {
    @Test
    fun suggestsModulesOnlyFromCurrentAikenRoot() {
        myFixture.addFileToProject("alpha/aiken.toml", "name = \"alpha\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "alpha/lib/alpha/math.ak",
            """
            pub fn add(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.addFileToProject("beta/aiken.toml", "name = \"beta\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "beta/lib/beta/math.ak",
            """
            pub fn sub(left, right) {
              right
            }
            """.trimIndent()
        )

        val file = myFixture.addFileToProject("alpha/lib/main.ak", "use <caret>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val suggestions = completionVariants()

        assertTrue(suggestions.contains("alpha/math"))
        assertFalse(suggestions.contains("beta/math"))
    }

    @Test
    fun suggestsPublicEntitiesFromIndexedExports() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left, right) {
              left
            }

            fn hidden() {
              0
            }

            pub type Number {
              Number
            }
            """.trimIndent()
        )

        val file = myFixture.addFileToProject("lib/main.ak", "use math.{<caret>}")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val suggestions = completionVariants()

        assertTrue(suggestions.contains("add"))
        assertTrue(suggestions.contains("Number"))
        assertFalse(suggestions.contains("hidden"))
    }

    private fun completionVariants(): List<String> =
        myFixture.completeBasic()
            ?.map { it.lookupString }
            .orEmpty()
}
