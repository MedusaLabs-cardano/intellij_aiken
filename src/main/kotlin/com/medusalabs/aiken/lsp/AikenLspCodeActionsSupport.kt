package com.medusalabs.aiken.lsp

import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspIntentionAction
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
        if (!AikenUnusedImportsQuickFixSupport.isAtomicUnusedImportCodeAction(codeAction)) {
            return super.createQuickFix(lspServer, codeAction) ?: LspIntentionAction(lspServer, codeAction)
        }

        return object : LspIntentionAction(lspServer, codeAction) {
            override fun getText(): String = AikenUnusedImportsQuickFixSupport.REMOVE_ONE_UNUSED_IMPORT
        }
    }
}
