package com.medusalabs.aiken.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.jsonrpc.messages.Either

object AikenUnusedImportsQuickFixSupport {
    const val UNUSED_IMPORT_VALUE = "aiken::check::unused:import::value"
    const val UNUSED_IMPORT_MODULE = "aiken::check::unused::import::module"
    const val REMOVE_ONE_UNUSED_IMPORT = "Remove unused import"
    const val REMOVE_ALL_UNUSED_IMPORTS = "Remove all unused imports"
    private const val LSP_REMOVE_UNUSED_IMPORTS = "Remove redundant imports"
    internal const val CODE_ACTION_REQUEST_TIMEOUT_MS = 10_000

    fun collectUnusedImportDiagnostics(diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.filter(::isUnusedImportDiagnostic)

    fun isUnusedImportDiagnostic(diagnostic: Diagnostic): Boolean {
        val code = diagnostic.code ?: return false
        if (code.isLeft) {
            return when (code.left) {
                UNUSED_IMPORT_VALUE,
                UNUSED_IMPORT_MODULE -> true
                else -> false
            }
        }
        return false
    }

    fun hasUnusedImportDiagnosticAtOffset(
        document: Document,
        diagnostics: List<Diagnostic>,
        offset: Int
    ): Boolean = findUnusedImportDiagnosticAtOffset(document, diagnostics, offset) != null

    fun findUnusedImportDiagnosticAtOffset(
        document: Document,
        diagnostics: List<Diagnostic>,
        offset: Int
    ): Diagnostic? {
        if (offset < 0 || document.lineCount <= 0) return null
        val caretLine = document.getLineNumber(offset.coerceAtMost(document.textLength))
        return diagnostics.firstOrNull { diagnostic ->
            val range = diagnostic.range ?: return@firstOrNull false
            val startLine = range.start?.line?.toInt() ?: return@firstOrNull false
            val endLine = range.end?.line?.toInt() ?: startLine
            caretLine in startLine..endLine
        }
    }

    fun isAtomicUnusedImportCodeAction(codeAction: CodeAction): Boolean {
        if (codeAction.title != LSP_REMOVE_UNUSED_IMPORTS) return false
        val diagnostics = codeAction.diagnostics ?: return false
        return diagnostics.size == 1 && isUnusedImportDiagnostic(diagnostics.single())
    }

    fun requestRemoveAllUnusedImportsAction(
        project: Project,
        file: VirtualFile,
        diagnostics: List<Diagnostic>,
        anchorDiagnostic: Diagnostic
    ): IntentionAction? {
        if (diagnostics.size < 2) return null
        val server = findServer(project, file) ?: return null
        val request = CodeActionParams(server.getDocumentIdentifier(file), anchorDiagnostic.range, buildContext(diagnostics))
        val actions = requestCodeActions(server, request) ?: return null

        val codeAction = actions.asSequence().mapNotNull(::asCodeAction).firstOrNull() ?: return null
        return AikenPreparedLspIntentionAction(server, codeAction, REMOVE_ALL_UNUSED_IMPORTS)
    }

    fun applyResolvedAction(
        action: IntentionAction,
        project: Project,
        editor: Editor,
        file: PsiFile
    ): Boolean {
        if (!action.isAvailable(project, editor, file)) return false
        if (action !is AikenPreparedLspIntentionAction) {
            AikenLspQuickFixPreparation.prepare(project, editor, file)
        }
        action.invoke(project, editor, file)
        return true
    }

    private fun findServer(project: Project, file: VirtualFile): LspServer? {
        val manager = LspServerManager.getInstance(project)
        return manager
            .getServersForProvider(AikenLspServerSupportProvider::class.java)
            .firstOrNull { it.descriptor.isSupportedFile(file) }
    }

    private fun buildContext(diagnostics: List<Diagnostic>): CodeActionContext =
        CodeActionContext().apply {
            setOnly(listOf("quickfix"))
            setDiagnostics(diagnostics)
            setTriggerKind(CodeActionTriggerKind.Invoked)
        }

    internal fun requestCodeActions(
        server: LspServer,
        request: CodeActionParams
    ): List<Either<out org.eclipse.lsp4j.Command, CodeAction>>? =
        server.sendRequestSync(CODE_ACTION_REQUEST_TIMEOUT_MS) { languageServer ->
            languageServer.textDocumentService.codeAction(request)
        }

    private fun asCodeAction(entry: Either<out org.eclipse.lsp4j.Command, CodeAction>): CodeAction? =
        when {
            entry.isRight -> entry.right
            entry.isLeft ->
                CodeAction(entry.left.title).apply {
                    kind = "quickfix"
                    command = entry.left
                }
            else -> null
        }
}
