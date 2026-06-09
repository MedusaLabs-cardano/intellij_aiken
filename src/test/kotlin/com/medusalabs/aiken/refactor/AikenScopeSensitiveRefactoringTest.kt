package com.medusalabs.aiken.refactor

import com.intellij.psi.PsiDocumentManager
import com.intellij.usageView.UsageInfo
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenScopeSensitiveRefactoringTest : AikenPlatformTestCase() {
    @Test
    fun findUsagesForShadowedLetBindingStaysInsideInnermostScope() {
        val file = myFixture.configureByText(
            AikenFileType,
            """
            const value = 0

            fn outer(value) {
              let value = 1
              if True {
                let val<caret>ue = 2
                value * 10
              }
              value
            }
            """.trimIndent()
        )

        val target = myFixture.getElementAtCaret()
        val usages = myFixture.findUsages(target)
        val offsets = usages.map { it.navigationStartOffset() }.sorted()

        assertEquals(
            listOf(
                file.text.indexOf("let value = 2") + 4,
                file.text.indexOf("value * 10")
            ),
            offsets
        )
    }

    @Test
    fun renameShadowedLetBindingDoesNotTouchOuterScopes() {
        val file = myFixture.configureByText(
            AikenFileType,
            """
            const value = 0

            fn outer(value) {
              let value = 1
              if True {
                let val<caret>ue = 2
                value * 10
              }
              value
            }
            """.trimIndent()
        )

        myFixture.renameElementAtCaret("inner_value")
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(
            """
            const value = 0

            fn outer(value) {
              let value = 1
              if True {
                let inner_value = 2
                inner_value * 10
              }
              value
            }
            """.trimIndent(),
            file.text
        )
    }

    @Test
    fun findUsagesForCallableLetBindingFromCallSiteIncludesDeclarationAndCall() {
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

        val target = myFixture.getElementAtCaret()
        val usages = myFixture.findUsages(target)
        val offsets = usages.map { it.navigationStartOffset() }.sorted()

        assertEquals(
            listOf(
                file.text.indexOf("let check_function =") + 4,
                file.text.indexOf("check_function(value, policy_id, asset_name)")
            ),
            offsets
        )
    }

    @Test
    fun renameCallableLetBindingUpdatesCallSite() {
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
              let check_funct<caret>ion =
                when strict_mode is {
                  True -> has_nft_strict
                  False -> has_nft
                }

              when check_function(value, policy_id, asset_name) is {
                True -> [1]
                False -> []
              }
            }
            """.trimIndent()
        )

        myFixture.renameElementAtCaret("selected_check")
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(
            """
            fn has_nft(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              True
            }

            fn has_nft_strict(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              False
            }

            fn main(strict_mode: Bool, value: Int, policy_id: ByteArray, asset_name: ByteArray) -> List<Int> {
              let selected_check =
                when strict_mode is {
                  True -> has_nft_strict
                  False -> has_nft
                }

              when selected_check(value, policy_id, asset_name) is {
                True -> [1]
                False -> []
              }
            }
            """.trimIndent(),
            file.text
        )
    }

    private fun UsageInfo.navigationStartOffset(): Int {
        val navigationOffset = navigationOffset
        if (navigationOffset >= 0) {
            return navigationOffset
        }

        val element = element ?: error("UsageInfo without element")
        val elementRange = rangeInElement
        return if (elementRange != null) {
            element.textRange.startOffset + elementRange.startOffset
        } else {
            element.textRange.startOffset
        }
    }
}
