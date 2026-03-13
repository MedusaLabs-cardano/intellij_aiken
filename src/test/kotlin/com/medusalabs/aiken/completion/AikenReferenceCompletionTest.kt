package com.medusalabs.aiken.completion

import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenReferenceCompletionTest : AikenPlatformTestCase() {
    @Test
    fun suggestsTypedParameterAndLocalBindingForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(seed: Int, extra: Int) -> Int {
              let inner_value = seed + extra
              i<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inner_value"))
    }

    @Test
    fun suggestsImportedAliasesForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math.{add as sum}
                use math as math_utils

                fn main(seed: Int) -> Int {
                  s
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\n  s") + 4)

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("sum"))
    }

    @Test
    fun suggestsSameFileTopLevelFunctionForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn helper(seed: Int) -> Int {
              seed
            }

            fn main(seed: Int) -> Int {
              h<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.contains("helper"))
    }

    @Test
    fun completesSameFileTypeInTypedPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Helper {
              Helper
            }

            fn main(seed: H<caret>) -> Helper {
              seed
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted(
            expectedLookup = "Helper",
            expectedInsertedSnippet = "fn main(seed: Helper) -> Helper {"
        )
    }

    @Test
    fun semanticVariantsIncludeSameFileTypeInTypedPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val file =
            myFixture.configureByText(
                "main.ak",
                """
                pub type Helper {
                  Helper
                }

                fn main(seed: H<caret>) -> Helper {
                  seed
                }
                """.trimIndent()
            )

        val anchor = findElementAtCaret(file) ?: error("Anchor at caret not found")
        val variants =
            AikenReferenceVariants.forElement(anchor)
                .mapNotNull { it as? com.intellij.codeInsight.lookup.LookupElement }
                .map { it.lookupString }

        assertTrue(variants.toString(), variants.contains("Helper"))
    }

    @Test
    fun completesCallableLocalBindingInCallPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn has_nft(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              True
            }

            fn has_nft_strict(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              False
            }

            fn main(strict_mode: Bool, value: Int, policy_id: ByteArray, asset_name: ByteArray) -> List<Int> {
              let check_function =
                when strict_mode is {
                  True -> has_nft_strict
                  False -> has_nft
                }

              when check_f<caret>(value, policy_id, asset_name) is {
                True -> [1]
                False -> []
              }
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted(
            expectedLookup = "check_function",
            expectedInsertedSnippet = "when check_function(value, policy_id, asset_name) is {"
        )
    }

    @Test
    fun doesNotSuggestUnimportedCrossFileFunctionForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn helper(seed: Int) -> Int {
              seed
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                fn main(seed: Int) -> Int {
                  h
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\n  h") + 4)

        val suggestions = completionVariants()

        assertFalse(suggestions.toString(), suggestions.contains("helper"))
    }

    @Test
    fun suggestsQualifiedExportsThroughModuleAliasForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }

            pub fn sub(left: Int, right: Int) -> Int {
              right
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as math_utils

                fn main(seed: Int) -> Int {
                  math_utils.a
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("math_utils.a") + "math_utils.a".length)

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("add"))
        assertFalse(suggestions.toString(), suggestions.contains("sub"))
    }

    private fun completionVariants(): List<String> =
        myFixture.completeBasic()
            ?.map { it.lookupString }
            .orEmpty()

    private fun assertCompletionContainsOrAutoInserted(
        expectedLookup: String,
        expectedInsertedSnippet: String
    ) {
        val suggestions = completionVariants()
        if (suggestions.contains(expectedLookup)) return

        assertTrue(
            "Expected lookup '$expectedLookup' or inserted snippet '$expectedInsertedSnippet'. File text was:\n${myFixture.file.text}",
            myFixture.file.text.contains(expectedInsertedSnippet)
        )
    }
}
