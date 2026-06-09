package com.medusalabs.aiken.refactor

import com.intellij.psi.PsiDocumentManager
import com.intellij.usageView.UsageInfo
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.rename.AikenRenamePsiElementProcessor
import org.junit.Test

class AikenCrossModuleRefactoringTest : AikenPlatformTestCase() {
    @Test
    fun findUsagesIncludesQualifiedReferencesFromModuleAliasImports() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val declaration = myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
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
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("add") + 1)

        val target = findElementAtCaret(myFixture.file) ?: error("Declaration at caret was not found")
        val usageFiles =
            myFixture.findUsages(target)
                .mapNotNull { it.locationPath()?.substringAfter("/src") }
                .toSet()

        assertTrue(usageFiles.toString(), usageFiles.contains("/lib/math.ak"))
        assertTrue(usageFiles.toString(), usageFiles.contains("/lib/main.ak"))
    }

    @Test
    fun findUsagesIncludesImportedItemNamesInsideUseStatements() {
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
                use math.{add}

                fn main(seed: Int) -> Int {
                  add(1, 2)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(declaration.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("add") + 1)

        val target = findElementAtCaret(myFixture.file) ?: error("Declaration at caret was not found")
        val usages = myFixture.findUsages(target)
        val mainOffsets =
            usages
                .filter { it.locationPath()?.endsWith("/lib/main.ak") == true }
                .map { it.navigationStartOffset() }
                .sorted()

        assertEquals(
            listOf(
                mainFile.text.indexOf("add}"),
                mainFile.text.indexOf("add(1, 2)")
            ),
            mainOffsets
        )
    }

    @Test
    fun renameUpdatesQualifiedReferencesFromModuleAliasImports() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val declaration = myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math as math_utils

            fn main(seed: Int) -> Int {
              math_utils.add(1, 2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(declaration.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("add") + 1)
        myFixture.renameElementAtCaret("sum")
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(declaration.text.contains("pub fn sum"))
        assertTrue(mainFile.text.contains("math_utils.sum(1, 2)"))
    }

    @Test
    fun renameUpdatesImportedItemNamesInsideUseStatements() {
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
                use math.{add}

                fn main(seed: Int) -> Int {
                  add(1, 2)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(declaration.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("add") + 1)
        myFixture.renameElementAtCaret("sum")
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(declaration.text.contains("pub fn sum"))
        assertTrue(mainFile.text.contains("use math.{sum}"))
        assertTrue(mainFile.text.contains("sum(1, 2)"))
    }

    @Test
    fun renameWithoutPrecomputedUsagesUpdatesQualifiedReferencesAndImportListHits() {
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
        val qualifiedFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as math_utils

                fn main(seed: Int) -> Int {
                  math_utils.add(1, 2)
                }
                """.trimIndent()
            )
        val importListFile =
            myFixture.addFileToProject(
                "lib/other.ak",
                """
                use math.{add}

                fn other(seed: Int) -> Int {
                  add(3, 4)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(declaration.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("add") + 1)

        val target = findElementAtCaret(myFixture.file) ?: error("Declaration at caret was not found")
        AikenRenamePsiElementProcessor().renameElement(target, "sum", emptyArray(), null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(declaration.text.contains("pub fn sum"))
        assertTrue(qualifiedFile.text.contains("math_utils.sum(1, 2)"))
        assertTrue(importListFile.text.contains("use math.{sum}"))
        assertTrue(importListFile.text.contains("sum(3, 4)"))
    }

    private fun UsageInfo.locationPath(): String? =
        file?.virtualFile?.path
            ?: virtualFile?.path
            ?: element?.containingFile?.virtualFile?.path

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
