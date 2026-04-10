package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
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

    @Test
    fun suggestsModulesByExportedSymbolName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/types.ak",
            """
            pub type Qwe {
              Qwe
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/types_extra.ak",
            """
            pub type QweBox {
              QweBox
            }
            """.trimIndent()
        )

        val file = myFixture.addFileToProject("lib/main.ak", "use Qwe<caret>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue("Expected reverse export suggestions, got: $suggestions", suggestions.contains("types.{Qwe}"))
        assertTrue("Expected reverse export suggestions, got: $suggestions", suggestions.contains("types_extra.{QweBox}"))
        assertEquals("type Qwe", presentationByLookup["types.{Qwe}"])
        assertEquals("type QweBox", presentationByLookup["types_extra.{QweBox}"])
    }

    @Test
    fun prefersExactReverseExportMatchBeforeFuzzyReverseExportMatch() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/zzz_exact.ak",
            """
            pub type Qwe {
              Qwe
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/aaa_fuzzy.ak",
            """
            pub type QweBox {
              QweBox
            }
            """.trimIndent()
        )

        val file = myFixture.addFileToProject("lib/main.ak", "use Qwe<caret>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val suggestions = completionVariants()
        assertTrue("Expected exact reverse export suggestion, got: $suggestions", suggestions.contains("zzz_exact.{Qwe}"))
        assertTrue("Expected fuzzy reverse export suggestion, got: $suggestions", suggestions.contains("aaa_fuzzy.{QweBox}"))
        assertTrue(
            "Expected exact reverse export match before fuzzy match. Suggestions were: $suggestions",
            suggestions.indexOf("zzz_exact.{Qwe}") < suggestions.indexOf("aaa_fuzzy.{QweBox}")
        )
    }

    @Test
    fun insertsFullImportForReverseExportSuggestion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/placeholder.ak",
            """
            pub type Qwe {
              Qwe
            }
            """.trimIndent()
        )

        val file = myFixture.addFileToProject("lib/main.ak", "use Qw<caret>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val elements = myFixture.completeBasic() ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "placeholder.{Qwe}" } ?: error("Expected reverse import suggestion")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        myFixture.checkResult("use placeholder.{Qwe}")
    }

    @Test
    fun suggestsModulesByExportedSymbolNameForWrongKeyboardLayout() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/placeholder.ak",
            """
            pub type Qwe {
              Qwe
            }
            """.trimIndent()
        )

        val file = myFixture.addFileToProject("lib/main.ak", "use <caret>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        myFixture.type("йцу")

        val suggestions = completionVariants()
        assertTrue("Expected reverse export suggestions for wrong-layout prefix, got: $suggestions", suggestions.contains("placeholder.{Qwe}"))
    }

    private fun completionVariants(): List<String> =
        myFixture.completeBasic()
            ?.map { it.lookupString }
            .orEmpty()

    private fun completionPresentations(): List<Pair<String, String?>> =
        myFixture.lookupElements
            ?.map { element ->
                val presentation = LookupElementPresentation()
                element.renderElement(presentation)
                element.lookupString to presentation.typeText
            }
            .orEmpty()
}
