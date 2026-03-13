package com.medusalabs.aiken.project

import com.intellij.psi.search.FileTypeIndex
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.lang.AikenFileType
import org.junit.Test

class AikenSearchScopesTest : AikenPlatformTestCase() {
    @Test
    fun restrictsSearchScopeToNearestAikenRoot() {
        myFixture.addFileToProject("alpha/aiken.toml", "name = \"alpha\"\nversion = \"0.0.0\"\n")
        val alphaFile = myFixture.addFileToProject("alpha/lib/main.ak", "test alpha() { True }\n")
        myFixture.addFileToProject("beta/aiken.toml", "name = \"beta\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject("beta/lib/main.ak", "test beta() { True }\n")

        val scope = AikenSearchScopes.forFile(project, alphaFile.virtualFile)
        val files = FileTypeIndex.getFiles(AikenFileType, scope).map { it.path }.sorted()

        assertEquals(listOf("/alpha/lib/main.ak"), files.map { it.substringAfter("/src") })
    }
}
