package com.medusalabs.aiken.refactor

import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.rename.AikenRenamePsiElementProcessor
import com.medusalabs.aiken.usages.AikenFindUsagesHandlerFactory
import org.junit.Test

class AikenScopedSearchTest : AikenPlatformTestCase() {
    @Test
    fun renameFindReferencesRespectsLocalSearchScope() {
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

        val target = findElementAtCaret(file) ?: error("Rename target not found at caret")
        val scopedUsage = file.findElementAt(file.text.indexOf("add(1, 2)")) ?: error("Scoped usage not found")
        val references =
            AikenRenamePsiElementProcessor().findReferences(
                target,
                LocalSearchScope(scopedUsage),
                false
            )

        val offsets =
            references
                .map { reference ->
                    reference.rangeInElement.shiftRight(reference.element.textRange.startOffset).startOffset
                }
                .sorted()

        assertEquals(
            listOf(
                file.text.indexOf("add(left: Int, right: Int)"),
                file.text.indexOf("add(1, 2)")
            ),
            offsets
        )
    }

    @Test
    fun renameFindReferencesForShadowedLocalBindingUsesReferenceIdentity() {
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                const value = 0

                fn outer(value: Int) -> Int {
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
        val references =
            AikenRenamePsiElementProcessor().findReferences(
                target,
                GlobalSearchScope.fileScope(project, file.virtualFile),
                false
            )

        val offsets =
            references
                .map { reference ->
                    reference.rangeInElement.shiftRight(reference.element.textRange.startOffset).startOffset
                }
                .sorted()

        assertEquals(
            listOf(
                file.text.indexOf("let value = 2") + 4,
                file.text.indexOf("value * 10")
            ),
            offsets
        )
    }

    @Test
    fun findUsagesHandlerRespectsFileSearchScope() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val declaration =
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
                use math as math_utils

                fn main(seed: Int) -> Int {
                  math_utils.add(1, 2)
                }
                """.trimIndent()
            )
        myFixture.addFileToProject(
            "lib/other.ak",
            """
            use math as math_utils

            fn other(seed: Int) -> Int {
              math_utils.add(3, 4)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(declaration.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("add") + 1)

        val target = findElementAtCaret(myFixture.file) ?: error("Usage target not found at caret")
        val handler =
            AikenFindUsagesHandlerFactory().createFindUsagesHandler(target, false)
                ?: error("Find usages handler was not created")
        val options = FindUsagesOptions(project)
        options.isUsages = true
        options.isSearchForTextOccurrences = false
        options.searchScope = GlobalSearchScope.fileScope(project, mainFile.virtualFile)

        val usages = ArrayList<UsageInfo>()
        val completed =
            handler.processElementUsages(
                target,
                Processor { usage ->
                    usages += usage
                    true
                },
                options
            )

        assertTrue(completed)

        val usageFiles =
            usages
                .mapNotNull { usage ->
                    usage.file?.virtualFile?.path
                        ?: usage.virtualFile?.path
                        ?: usage.element?.containingFile?.virtualFile?.path
                }
                .toSet()

        assertTrue(usageFiles.toString(), usageFiles.any { it.endsWith("/lib/math.ak") })
        assertTrue(usageFiles.toString(), usageFiles.any { it.endsWith("/lib/main.ak") })
        assertFalse(usageFiles.toString(), usageFiles.any { it.endsWith("/lib/other.ak") })
    }
}
