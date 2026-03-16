package com.medusalabs.aiken.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.medusalabs.aiken.AikenPlatformTestCase
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Test

class AikenRemoveAllUnusedImportsIntentionTest : AikenPlatformTestCase() {
    @Test
    fun availableWhenCaretIsOnUnusedImportAndFileHasMultipleUnusedImports() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use alpha.{<caret>first}
                use beta.{second}

                fn main() -> Int {
                  1
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(source.virtualFile)
        publishDiagnostics(
            source.virtualFile,
            listOf(
                unusedImportDiagnostic(0, 11, 16),
                unusedImportDiagnostic(1, 10, 16)
            )
        )

        val action = AikenRemoveAllUnusedImportsIntention()

        assertTrue(action.isAvailable(project, myFixture.editor, findElementAtCaret(myFixture.file)!!))
    }

    @Test
    fun unavailableWhenOnlyOneUnusedImportExists() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use alpha.{<caret>first}

                fn main() -> Int {
                  1
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(source.virtualFile)
        publishDiagnostics(source.virtualFile, listOf(unusedImportDiagnostic(0, 11, 16)))

        val action = AikenRemoveAllUnusedImportsIntention()

        assertFalse(action.isAvailable(project, myFixture.editor, findElementAtCaret(myFixture.file)!!))
    }

    @Test
    fun unavailableWhenCaretIsNotOnUnusedImportLine() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use alpha.{first}
                use beta.{second}

                fn main() -> Int {
                  <caret>1
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(source.virtualFile)
        publishDiagnostics(
            source.virtualFile,
            listOf(
                unusedImportDiagnostic(0, 11, 16),
                unusedImportDiagnostic(1, 10, 16)
            )
        )

        val action = AikenRemoveAllUnusedImportsIntention()

        assertFalse(action.isAvailable(project, myFixture.editor, findElementAtCaret(myFixture.file)!!))
    }

    @Test
    fun primesResolvedActionBeforeInvoke() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                fn main() -> Int {
                  <caret>1
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(source.virtualFile)

        val fakeAction = SequencedIntentionAction()

        assertTrue(
            AikenUnusedImportsQuickFixSupport.applyResolvedAction(
                fakeAction,
                project,
                myFixture.editor,
                myFixture.file
            )
        )
        assertEquals(listOf("available", "invoke"), fakeAction.events)
    }

    private fun publishDiagnostics(
        file: com.intellij.openapi.vfs.VirtualFile,
        diagnostics: List<Diagnostic>
    ) {
        project.getService(AikenLspDiagnosticsProjectViewService::class.java)
            .onPublishDiagnostics(file, diagnostics)
    }

    @Suppress("SameParameterValue")
    private fun unusedImportDiagnostic(line: Int, startCharacter: Int, endCharacter: Int): Diagnostic =
        Diagnostic(
            Range(Position(line, startCharacter), Position(line, endCharacter)),
            "Unused import",
            DiagnosticSeverity.Warning,
            "aiken"
        ).apply {
            code = Either.forLeft(AikenUnusedImportsQuickFixSupport.UNUSED_IMPORT_VALUE)
        }

    private class SequencedIntentionAction : IntentionAction {
        val events = mutableListOf<String>()

        override fun getText(): String = "Fake"

        override fun getFamilyName(): String = "Fake"

        override fun isAvailable(
            project: com.intellij.openapi.project.Project,
            editor: com.intellij.openapi.editor.Editor?,
            file: com.intellij.psi.PsiFile?
        ): Boolean {
            events += "available"
            return true
        }

        override fun invoke(
            project: com.intellij.openapi.project.Project,
            editor: com.intellij.openapi.editor.Editor?,
            file: com.intellij.psi.PsiFile?
        ) {
            events += "invoke"
        }

        override fun startInWriteAction(): Boolean = false
    }
}
