package com.medusalabs.aiken.navigation

import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenDeclarationResolverTest : AikenPlatformTestCase() {
    @Test
    fun resolvesModuleAliasDeclaredAfterImportList() {
        val file = myFixture.configureByText(
            AikenFileType,
            """
            use aiken/collection/list.{count as my_count} as native_list

            test thing() {
              native_list<caret>.count([1, 2, 3], fn(item) { item > 0 })
            }
            """.trimIndent()
        )

        val element = findElementAtCaret(file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("native_list", target!!.text)
        assertEquals(file.text.indexOf("native_list"), target.textRange.startOffset)
    }

    @Test
    fun resolvesItemAliasImportedInsideUseStatement() {
        val file = myFixture.configureByText(
            AikenFileType,
            """
            use aiken/collection/list.{count as my_count}

            test thing() {
              my_count<caret>([1, 2, 3], fn(item) { item > 0 })
            }
            """.trimIndent()
        )

        val element = findElementAtCaret(file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("my_count", target!!.text)
        assertEquals(file.text.indexOf("my_count"), target.textRange.startOffset)
    }

    @Test
    fun resolvesFunctionParameterBeforeGlobalDeclaration() {
        val file = myFixture.configureByText(
            AikenFileType,
            """
            const value = 0

            fn outer(value) {
              value<caret>
            }
            """.trimIndent()
        )

        val element = findElementAtCaret(file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("value", target!!.text)
        assertEquals(file.text.indexOf("value)"), target.textRange.startOffset)
    }

    @Test
    fun resolvesNearestLetShadowingInsideNestedScope() {
        val file = myFixture.configureByText(
            AikenFileType,
            """
            fn outer(value) {
              let value = 1
              if True {
                let value = 2
                value<caret>
              }
            }
            """.trimIndent()
        )

        val element = findElementAtCaret(file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("value", target!!.text)
        assertEquals(file.text.indexOf("let value = 2") + 4, target.textRange.startOffset)
    }

    @Test
    fun resolvesCallableLetBindingInCallPosition() {
        val file = myFixture.configureByText(
            AikenFileType,
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

              when check_funct<caret>ion(value, policy_id, asset_name) is {
                True -> [1]
                False -> []
              }
            }
            """.trimIndent()
        )

        val element = findElementAtCaret(file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("check_function", target!!.text)
        assertEquals(file.text.indexOf("let check_function =") + 4, target.textRange.startOffset)
    }

    @Test
    fun resolvesImportedFunctionToMatchingModuleWhenNamesCollide() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/first.ak",
            """
            pub fn compute(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/second.ak",
            """
            pub fn compute(left, right, carry) {
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

        val element = findElementAtCaret(myFixture.file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("compute", target!!.text)
        assertEquals("second.ak", target.containingFile?.name)
    }

    @Test
    fun resolvesQualifiedFunctionToModuleAliasTargetWhenNamesCollide() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/first.ak",
            """
            pub fn compute(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/second.ak",
            """
            pub fn helper() {
              Void
            }

            pub fn compute(left, right, carry) {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use second.{helper} as chosen

            fn main() {
              chosen.comp<caret>ute(1, 2, 3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val element = findElementAtCaret(myFixture.file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("compute", target!!.text)
        assertEquals("second.ak", target.containingFile?.name)
    }

    @Test
    fun resolvesSameFileTopLevelFunctionBeforeImportedSameName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn compute(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math.{compute}

            fn compute(seed: Int) -> Int {
              seed
            }

            fn main() -> Int {
              comp<caret>ute(1)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val element = findElementAtCaret(myFixture.file)
        assertNotNull(element)

        val target = AikenDeclarationResolver.resolve(element!!)
        assertNotNull(target)
        assertEquals("compute", target!!.text)
        assertEquals("main.ak", target.containingFile?.name)
        assertEquals(myFixture.file.text.indexOf("fn compute") + 3, target.textRange.startOffset)
    }
}
