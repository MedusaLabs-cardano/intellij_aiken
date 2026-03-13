package com.medusalabs.aiken.search

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.LocalSearchScope
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenLocalReferenceSearchTest : AikenPlatformTestCase() {
    @Test
    fun usageSearchSupportFindsTypedCallInsideLocalSearchScope() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                pub fn ad<caret>d(left: Int, right: Int) -> Int {
                  left
                }

                fn main(seed: Int) -> Int {
                  let first = add(1, 2)
                  let second = add(3, 4)
                  first + second
                }
                """.trimIndent()
            )

        val target = namedTargetAtCaret(file)
        val scopedUsage = file.findElementAt(file.text.indexOf("add(1, 2)")) ?: error("Scoped usage not found")

        val hits =
            AikenUsageSearchSupport.findResolvedAikenReferencesInLocalScope(
                target,
                LocalSearchScope(scopedUsage),
                false
            )

        val offsets =
            hits
                .map { hit -> hit.range.startOffset }
                .sorted()

        assertEquals(listOf(file.text.indexOf("add(1, 2)")), offsets)
    }

    @Test
    fun usageSearchSupportFindsAliasCallInsideLocalSearchScope() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val declaration =
            myFixture.addFileToProject(
                "lib/math.ak",
                """
                pub fn ad<caret>d(left: Int, right: Int) -> Int {
                  left
                }
                """.trimIndent()
            )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as math_utils

                fn main(seed: Int) -> Int {
                  math_utils.add(1, 2)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(declaration.virtualFile)

        val target = namedTargetAtCaret(myFixture.file)
        val scopedUsage =
            mainFile.findElementAt(mainFile.text.indexOf("math_utils.add(1, 2)") + "math_utils.".length)
                ?: error("Scoped alias usage not found")

        val hits =
            AikenUsageSearchSupport.findResolvedAikenReferencesInLocalScope(
                target,
                LocalSearchScope(scopedUsage),
                false
            )

        val offsets =
            hits
                .map { hit -> hit.range.startOffset }
                .sorted()

        assertEquals(listOf(mainFile.text.indexOf("math_utils.add(1, 2)") + "math_utils.".length), offsets)
    }

    private fun namedTargetAtCaret(file: com.intellij.psi.PsiFile): PsiNamedElement {
        val element = findElementAtCaret(file) ?: error("Target at caret not found")
        return element as? PsiNamedElement
            ?: element.parent as? PsiNamedElement
            ?: error("PsiNamedElement at caret not found")
    }
}
