package com.medusalabs.aiken.scope

import com.intellij.psi.PsiDocumentManager
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AikenLocalScopeAnalyzerTest : AikenPlatformTestCase() {
    @Test
    fun collectsNestedTupleBindingsInsideWhenPattern() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn load_value() -> Option<(Int, ByteArray)> {
              Some((1, "abc"))
            }

            fn main() {
              when load_value() is {
                Some((s1, a)) -> {
                  <caret>
                }
                _ -> Void
              }
            }
            """.trimIndent()
        )

        val document = PsiDocumentManager.getInstance(project).getDocument(myFixture.file) ?: error("No document")
        val bindings = AikenLocalScopeAnalyzer.collectVisibleBindings(document, myFixture.caretOffset)
        val bindingsByName = bindings.associateBy { it.name }

        assertTrue(bindings.toString(), bindingsByName.containsKey("s1"))
        assertTrue(bindings.toString(), bindingsByName.containsKey("a"))
        assertNotEquals(bindings.toString(), bindingsByName.getValue("s1").declarationOffset, bindingsByName.getValue("a").declarationOffset)
        assertEquals("s1", myFixture.file.text.substring(bindingsByName.getValue("s1").declarationOffset, bindingsByName.getValue("s1").declarationOffset + 2))
        assertEquals("a", myFixture.file.text.substring(bindingsByName.getValue("a").declarationOffset, bindingsByName.getValue("a").declarationOffset + 1))
    }
}
