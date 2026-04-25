package com.medusalabs.aiken.lsp

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeAction

class AikenLspCustomization : LspCustomization() {
    private val codeActionsSupport = AikenLspCodeActionsSupport()

    override val codeActionsCustomizer: LspCodeActionsCustomizer
        get() = codeActionsSupport
}

class AikenLspCodeActionsSupport : LspCodeActionsSupport() {
    override fun createQuickFix(lspServer: LspServer, codeAction: CodeAction): LspIntentionAction =
        createAction(lspServer, codeAction)

    override fun createIntentionAction(
        lspServer: LspServer,
        codeAction: CodeAction
    ): LspIntentionAction = createAction(lspServer, codeAction)

    private fun createAction(lspServer: LspServer, codeAction: CodeAction): LspIntentionAction {
        val atomicUnusedImport = AikenUnusedImportsQuickFixSupport.isAtomicUnusedImportCodeAction(codeAction)
        val textOverride =
            if (atomicUnusedImport) {
                AikenUnusedImportsQuickFixSupport.REMOVE_ALL_UNUSED_IMPORTS
            } else {
                null
            }
        val priorityOverride =
            if (atomicUnusedImport) {
                PriorityAction.Priority.TOP
            } else {
                null
            }

        return AikenPreparedLspIntentionAction(
            lspServer,
            codeAction,
            textOverride,
            priorityOverride,
            preferRemoveAllUnusedImports = atomicUnusedImport
        )
    }
}

internal class AikenPreparedLspIntentionAction(
    lspServer: LspServer,
    codeAction: CodeAction,
    private val textOverride: String? = null,
    private val priorityOverride: PriorityAction.Priority? = null,
    private val preferRemoveAllUnusedImports: Boolean = false
) : LspIntentionAction(lspServer, codeAction), PriorityAction {
    override fun getText(): String = textOverride ?: super.getText()

    override fun getPriority(): PriorityAction.Priority = priorityOverride ?: PriorityAction.Priority.NORMAL

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        AikenLspQuickFixPreparation.prepare(project, editor, psiFile)
        if (
            preferRemoveAllUnusedImports &&
            AikenUnusedImportsQuickFixSupport.applyRemoveAllUnusedImportsIfAvailable(project, editor, psiFile)
        ) {
            return
        }
        super.invoke(project, editor, psiFile)
    }
}
