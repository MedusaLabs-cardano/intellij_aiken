package com.medusalabs.aiken.lsp

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.AikenLanguage

class AikenRemoveAllUnusedImportsIntention :
    PsiElementBaseIntentionAction(),
    HighPriorityAction {
    init {
        text = AikenUnusedImportsQuickFixSupport.REMOVE_ALL_UNUSED_IMPORTS
    }

    override fun getFamilyName(): String = AikenUnusedImportsQuickFixSupport.REMOVE_ALL_UNUSED_IMPORTS

    override fun checkFile(file: PsiFile?): Boolean = file?.fileType == AikenFileType

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        if (file.language != AikenLanguage) return false
        val virtualFile = file.virtualFile ?: return false
        val service = project.getService(AikenLspDiagnosticsProjectViewService::class.java) ?: return false
        val unusedDiagnostics =
            AikenUnusedImportsQuickFixSupport.collectUnusedImportDiagnostics(
                service.getDiagnostics(virtualFile)
            )
        if (unusedDiagnostics.size < 2) return false
        return AikenUnusedImportsQuickFixSupport.hasUnusedImportDiagnosticAtOffset(
            editor.document,
            unusedDiagnostics,
            editor.caretModel.offset
        )
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        val service = project.getService(AikenLspDiagnosticsProjectViewService::class.java) ?: return
        val unusedDiagnostics =
            AikenUnusedImportsQuickFixSupport.collectUnusedImportDiagnostics(
                service.getDiagnostics(virtualFile)
            )
        if (unusedDiagnostics.size < 2) return
        val anchor =
            AikenUnusedImportsQuickFixSupport.findUnusedImportDiagnosticAtOffset(
                editor.document,
                unusedDiagnostics,
                editor.caretModel.offset
            )
                ?: return
        val action =
            AikenUnusedImportsQuickFixSupport.requestRemoveAllUnusedImportsAction(
                project,
                virtualFile,
                unusedDiagnostics,
                anchor
            )
                ?: return
        AikenUnusedImportsQuickFixSupport.applyResolvedAction(action, project, editor, file)
    }
}
