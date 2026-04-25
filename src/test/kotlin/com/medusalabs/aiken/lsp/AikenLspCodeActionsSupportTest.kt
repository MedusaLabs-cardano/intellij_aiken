package com.medusalabs.aiken.lsp

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class AikenLspCodeActionsSupportTest {
    private val support = AikenLspCodeActionsSupport()
    private val server = FakeLspServer()

    @Test
    fun promotesAtomicUnusedImportQuickFixToRemoveAllByDefault() {
        val action =
            CodeAction("Remove redundant imports").apply {
                kind = "quickfix"
                diagnostics = listOf(unusedImportDiagnostic())
            }

        val quickFix = support.createQuickFix(server, action)

        assertEquals(AikenUnusedImportsQuickFixSupport.REMOVE_ALL_UNUSED_IMPORTS, quickFix.text)
        assertEquals(PriorityAction.Priority.TOP, (quickFix as PriorityAction).priority)
        assertTrue(quickFix is AikenPreparedLspIntentionAction)
    }

    @Test
    fun keepsAggregatedUnusedImportQuickFixTitleUnchanged() {
        val action =
            CodeAction("Remove redundant imports").apply {
                kind = "quickfix"
                diagnostics = listOf(unusedImportDiagnostic(), unusedImportDiagnostic(line = 1))
            }

        val quickFix = support.createQuickFix(server, action)

        assertEquals("Remove redundant imports", quickFix.text)
        assertEquals(PriorityAction.Priority.NORMAL, (quickFix as PriorityAction).priority)
        assertTrue(quickFix is AikenPreparedLspIntentionAction)
    }

    @Test
    fun filtersUnusedImportDiagnosticsByCode() {
        val diagnostics =
            listOf(
                unusedImportDiagnostic(),
                Diagnostic(
                    Range(Position(2, 0), Position(2, 5)),
                    "Some other warning",
                    DiagnosticSeverity.Warning,
                    "aiken"
                ).apply {
                    code = Either.forLeft("aiken::check::unused::function")
                }
            )

        val unused = AikenUnusedImportsQuickFixSupport.collectUnusedImportDiagnostics(diagnostics)

        assertEquals(1, unused.size)
        assertTrue(AikenUnusedImportsQuickFixSupport.isUnusedImportDiagnostic(unused.single()))
        assertFalse(
            AikenUnusedImportsQuickFixSupport.isUnusedImportDiagnostic(diagnostics.last())
        )
    }

    @Test
    fun aggregatedCodeActionRequestsUseNonZeroTimeout() {
        val recordingServer = RecordingLspServer()
        val request =
            CodeActionParams(
                TextDocumentIdentifier("file:///tmp/main.ak"),
                Range(Position(0, 0), Position(0, 1)),
                CodeActionContext()
            )

        AikenUnusedImportsQuickFixSupport.requestCodeActions(recordingServer, request)

        assertEquals(AikenUnusedImportsQuickFixSupport.CODE_ACTION_REQUEST_TIMEOUT_MS, recordingServer.lastTimeoutMs)
    }

    private fun unusedImportDiagnostic(line: Int = 0): Diagnostic =
        Diagnostic(
            Range(Position(line, 4), Position(line, 10)),
            "Unused import",
            DiagnosticSeverity.Warning,
            "aiken"
        ).apply {
            code = Either.forLeft(AikenUnusedImportsQuickFixSupport.UNUSED_IMPORT_VALUE)
        }

    private open class FakeLspServer : LspServer {
        override val providerClass = AikenLspServerSupportProvider::class.java

        override val project: Project
            get() = throw UnsupportedOperationException()

        override val descriptor: LspServerDescriptor
            get() = throw UnsupportedOperationException()

        override val state: LspServerState
            get() = throw UnsupportedOperationException()

        override val initializeResult: InitializeResult?
            get() = null

        override fun sendNotification(lsp4jSender: (LanguageServer) -> Unit) = Unit

        override suspend fun <Lsp4jResponse> sendRequest(
            lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>
        ): Lsp4jResponse? = throw UnsupportedOperationException()

        override fun <Lsp4jResponse : Any?> sendRequestSync(
            timeoutMs: Int,
            lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>
        ): Lsp4jResponse? = throw UnsupportedOperationException()

        override fun getDocumentIdentifier(file: VirtualFile): TextDocumentIdentifier =
            throw UnsupportedOperationException()

        override fun getDocumentVersion(document: Document): Int =
            throw UnsupportedOperationException()
    }

    private class RecordingLspServer : FakeLspServer() {
        var lastTimeoutMs: Int? = null

        override fun <Lsp4jResponse : Any?> sendRequestSync(
            timeoutMs: Int,
            lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>
        ): Lsp4jResponse? {
            lastTimeoutMs = timeoutMs
            @Suppress("UNCHECKED_CAST")
            return emptyList<Either<Nothing, CodeAction>>() as Lsp4jResponse
        }
    }
}
