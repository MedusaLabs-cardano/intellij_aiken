package com.medusalabs.aiken.lsp

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

internal object AikenLspQuickFixPreparation {
    @Volatile
    internal var beforeApplyOverride: ((Project, Editor?, PsiFile?) -> Unit)? = null

    fun prepare(project: Project, editor: Editor?, file: PsiFile?) {
        beforeApplyOverride?.let {
            it(project, editor, file)
            return
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val fileDocumentManager = FileDocumentManager.getInstance()
        when {
            editor != null -> fileDocumentManager.saveDocument(editor.document)
            file?.virtualFile != null -> {
                val document = fileDocumentManager.getDocument(file.virtualFile)
                if (document != null) {
                    fileDocumentManager.saveDocument(document)
                } else {
                    fileDocumentManager.saveAllDocuments()
                }
            }
            else -> fileDocumentManager.saveAllDocuments()
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
}
