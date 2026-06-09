package com.medusalabs.aiken.search

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenUseScopeProviderTest : AikenPlatformTestCase() {
    @Test
    fun importedFunctionUseScopeStaysInsideOwningRoot() {
        myFixture.addFileToProject("alpha/aiken.toml", "name = \"alpha\"\nversion = \"0.0.0\"\n")
        val alphaMath =
            myFixture.addFileToProject(
                "alpha/lib/math.ak",
                """
                pub fn ad<caret>d(left: Int, right: Int) -> Int {
                  left
                }
                """.trimIndent()
            )
        val alphaMain =
            myFixture.addFileToProject(
                "alpha/lib/main.ak",
                """
                use math.{add}

                fn main(seed: Int) -> Int {
                  add(1, 2)
                }
                """.trimIndent()
            )
        myFixture.addFileToProject("beta/aiken.toml", "name = \"beta\"\nversion = \"0.0.0\"\n")
        val betaMain =
            myFixture.addFileToProject(
                "beta/lib/main.ak",
                """
                use math.{add}

                fn main(seed: Int) -> Int {
                  add(3, 4)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(alphaMath.virtualFile)

        val target = namedTargetAtCaret(myFixture.file)
        val scope = effectiveUseScope(target) as? GlobalSearchScope ?: error("Expected GlobalSearchScope")

        assertTrue(scope.contains(alphaMath.virtualFile))
        assertTrue(scope.contains(alphaMain.virtualFile))
        assertFalse(scope.contains(betaMain.virtualFile))
    }

    @Test
    fun localBindingUseScopeStaysInCurrentFile() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val otherFile =
            myFixture.addFileToProject(
            "lib/other.ak",
            """
            fn other(seed: Int) -> Int {
              let value = 3
              value
            }
            """.trimIndent()
            )
        val file =
            myFixture.configureByText(
                AikenFileType,
                """
                fn main(seed: Int) -> Int {
                  let val<caret>ue = 1
                  value + seed
                }
                """.trimIndent()
            )

        val target = namedTargetAtCaret(file)
        val scope = effectiveUseScope(target) as? GlobalSearchScope ?: error("Expected GlobalSearchScope")

        assertTrue(scope.contains(file.virtualFile))
        assertFalse(scope.contains(otherFile.virtualFile))
    }

    @Test
    fun constUseScopeIncludesImportingFiles() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val constantsFile =
            myFixture.addFileToProject(
                "lib/constants.ak",
                """
                pub const val<caret>ue = 1
                """.trimIndent()
            )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use constants.{value}

                fn main(seed: Int) -> Int {
                  value + seed
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(constantsFile.virtualFile)

        val target = namedTargetAtCaret(myFixture.file)
        val scope = effectiveUseScope(target) as? GlobalSearchScope ?: error("Expected GlobalSearchScope")

        assertTrue(scope.contains(constantsFile.virtualFile))
        assertTrue(scope.contains(mainFile.virtualFile))
    }

    @Test
    fun importedFunctionUseScopeIncludesModuleAndItemAliasImporters() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mathFile =
            myFixture.addFileToProject(
                "lib/math.ak",
                """
                pub fn ad<caret>d(left: Int, right: Int) -> Int {
                  left
                }
                """.trimIndent()
            )
        val moduleAliasFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as math_utils

                fn main(seed: Int) -> Int {
                  math_utils.add(1, 2)
                }
                """.trimIndent()
            )
        val itemAliasFile =
            myFixture.addFileToProject(
                "lib/other.ak",
                """
                use math.{add as sum}

                fn other(seed: Int) -> Int {
                  sum(3, 4)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(mathFile.virtualFile)

        val target = namedTargetAtCaret(myFixture.file)
        val scope = effectiveUseScope(target) as? GlobalSearchScope ?: error("Expected GlobalSearchScope")

        assertTrue(scope.contains(mathFile.virtualFile))
        assertTrue(scope.contains(moduleAliasFile.virtualFile))
        assertTrue(scope.contains(itemAliasFile.virtualFile))
    }

    @Test
    fun directElementUseScopeRemainsCurrentFileWhileSearchHelperAddsImporters() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mathFile =
            myFixture.addFileToProject(
                "lib/math.ak",
                """
                pub fn ad<caret>d(left: Int, right: Int) -> Int {
                  left
                }
                """.trimIndent()
            )
        val importerFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math.{add}

                fn main(seed: Int) -> Int {
                  add(1, 2)
                }
                """.trimIndent()
            )

        myFixture.configureFromExistingVirtualFile(mathFile.virtualFile)

        val target = namedTargetAtCaret(myFixture.file)
        val directScope = target.useScope as? GlobalSearchScope ?: error("Expected direct GlobalSearchScope")
        val effectiveScope = effectiveUseScope(target) as? GlobalSearchScope ?: error("Expected effective GlobalSearchScope")

        assertTrue(directScope.contains(mathFile.virtualFile))
        assertFalse(directScope.contains(importerFile.virtualFile))
        assertTrue(effectiveScope.contains(importerFile.virtualFile))
    }

    private fun namedTargetAtCaret(file: com.intellij.psi.PsiFile): PsiNamedElement {
        val element = findElementAtCaret(file) ?: error("Target at caret not found")
        return element as? PsiNamedElement
            ?: element.parent as? PsiNamedElement
            ?: error("PsiNamedElement at caret not found")
    }

    private fun effectiveUseScope(target: PsiNamedElement) =
        PsiSearchHelper.getInstance(project).getUseScope(target)
}
