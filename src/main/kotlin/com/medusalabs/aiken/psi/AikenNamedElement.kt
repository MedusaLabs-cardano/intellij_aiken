package com.medusalabs.aiken.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.medusalabs.aiken.search.AikenUseScopeProvider

class AikenNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNamedElement {
    override fun getName(): String = text

    override fun getReference(): PsiReference? = AikenNamedReference.create(this)

    override fun getUseScope(): SearchScope = AikenUseScopeProvider.forElement(this)

    override fun setName(name: String): PsiElement {
        val project = project
        val file = containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return this
        val range = textRange

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(range.startOffset, range.endOffset, name)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        return this
    }
}
