package com.medusalabs.aiken.refactor

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.rename.AikenRenamePsiElementProcessor
import org.junit.Test

class AikenSelectiveRenameTest : AikenPlatformTestCase() {
    @Test
    fun renameElementRespectsProvidedUsageSubset() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val file = myFixture.addFileToProject(
            "lib/main.ak",
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
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val target = findElementAtCaret(myFixture.file) ?: error("Rename target not found at caret")
        val processor = AikenRenamePsiElementProcessor()
        val references =
            processor.findReferences(
                target,
                GlobalSearchScope.fileScope(project, file.virtualFile),
                false
            )

        val selectedUsages =
            references
                .map { UsageInfo(it) }
                .filter { usage ->
                    val segment = usage.segment
                    segment == null || file.text.substring(segment.startOffset, segment.endOffset) == "add" &&
                        usage.segment?.startOffset != file.text.indexOf("add(3, 4)")
                }
                .toTypedArray()

        processor.renameElement(target, "sum", selectedUsages, null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(
            """
            pub fn sum(left: Int, right: Int) -> Int {
              left
            }

            fn main(seed: Int) -> Int {
              let first = sum(1, 2)
              let second = add(3, 4)
              first + second
            }
            """.trimIndent(),
            file.text
        )
    }

    @Test
    fun renameElementWithoutUsagesStillRenamesDeclarationAndReferences() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val file = myFixture.addFileToProject(
            "lib/main.ak",
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
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val target = findElementAtCaret(myFixture.file) ?: error("Rename target not found at caret")
        AikenRenamePsiElementProcessor().renameElement(target, "sum", emptyArray(), null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(
            """
            pub fn sum(left: Int, right: Int) -> Int {
              left
            }

            fn main(seed: Int) -> Int {
              let first = sum(1, 2)
              let second = sum(3, 4)
              first + second
            }
            """.trimIndent(),
            file.text
        )
    }
}
