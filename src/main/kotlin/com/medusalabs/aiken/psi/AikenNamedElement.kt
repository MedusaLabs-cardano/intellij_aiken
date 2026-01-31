package com.medusalabs.aiken.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiDocumentManager

class AikenNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNamedElement {
    override fun getName(): String = text

    override fun setName(name: String): PsiElement {
        val project = project
        val file = containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return this

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(textRange.startOffset, textRange.endOffset, name)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        return this
    }
}

