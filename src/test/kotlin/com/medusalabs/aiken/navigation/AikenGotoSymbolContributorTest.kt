package com.medusalabs.aiken.navigation

import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.project.AikenSearchScopes
import org.junit.Test

class AikenGotoSymbolContributorTest : AikenPlatformTestCase() {
    @Test
    fun processesNamesWithinProvidedRootScope() {
        myFixture.addFileToProject("alpha/aiken.toml", "name = \"alpha\"\nversion = \"0.0.0\"\n")
        val alphaFile =
            myFixture.addFileToProject(
                "alpha/lib/math.ak",
                """
                pub fn compute(seed: Int) -> Int {
                  seed
                }

                const seed_value = 1

                type Choice {
                  This
                  That(Int)
                }
                """.trimIndent()
            )
        myFixture.addFileToProject("beta/aiken.toml", "name = \"beta\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "beta/lib/other.ak",
            """
            pub fn beta_only(value: Int) -> Int {
              value
            }
            """.trimIndent()
        )

        val contributor = AikenGotoSymbolContributor()
        val names = LinkedHashSet<String>()

        contributor.processNames(
            Processor { name ->
                names += name
                true
            },
            AikenSearchScopes.forFile(project, alphaFile.virtualFile),
            null
        )

        assertTrue(names.contains("compute"))
        assertTrue(names.contains("seed_value"))
        assertTrue(names.contains("Choice"))
        assertTrue(names.contains("This"))
        assertFalse(names.contains("beta_only"))
    }

    @Test
    fun returnsOnlySymbolsFromRequestedRoot() {
        myFixture.addFileToProject("alpha/aiken.toml", "name = \"alpha\"\nversion = \"0.0.0\"\n")
        val alphaFile =
            myFixture.addFileToProject(
                "alpha/lib/math.ak",
                """
                pub fn compute(seed: Int) -> Int {
                  seed
                }
                """.trimIndent()
            )
        myFixture.addFileToProject("beta/aiken.toml", "name = \"beta\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "beta/lib/math.ak",
            """
            pub fn compute(seed: Int) -> Int {
              seed + 1
            }
            """.trimIndent()
        )

        val contributor = AikenGotoSymbolContributor()
        val items = ArrayList<NavigationItem>()
        val scope = AikenSearchScopes.forFile(project, alphaFile.virtualFile)

        contributor.processElementsWithName(
            "compute",
            Processor { item ->
                items += item
                true
            },
            findSymbolParameters("compute", scope)
        )

        assertEquals(1, items.size)
        val element = items.single() as PsiElement
        assertEquals(alphaFile.virtualFile, element.containingFile.virtualFile)
        assertEquals("compute", element.text)
    }

    @Test
    fun returnsTypeAndConstructorTargets() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                type Choice {
                  This
                  That(Int)
                }
                """.trimIndent()
            )

        val contributor = AikenGotoSymbolContributor()
        val scope = AikenSearchScopes.forFile(project, source.virtualFile)

        val typeItems = ArrayList<NavigationItem>()
        contributor.processElementsWithName(
            "Choice",
            Processor { item ->
                typeItems += item
                true
            },
            findSymbolParameters("Choice", scope)
        )

        val constructorItems = ArrayList<NavigationItem>()
        contributor.processElementsWithName(
            "This",
            Processor { item ->
                constructorItems += item
                true
            },
            findSymbolParameters("This", scope)
        )

        assertEquals(1, typeItems.size)
        assertEquals(1, constructorItems.size)
        assertEquals("Choice", (typeItems.single() as PsiElement).text)
        assertEquals("This", (constructorItems.single() as PsiElement).text)
    }

    @Suppress("DEPRECATION")
    private fun findSymbolParameters(
        name: String,
        scope: com.intellij.psi.search.GlobalSearchScope
    ): FindSymbolParameters = FindSymbolParameters(name, name, scope, null)
}
