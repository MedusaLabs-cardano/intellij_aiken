package com.medusalabs.aiken.refactor

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isAvailable(element: PsiElement): Boolean {
        val fileType = element.containingFile?.fileType ?: return false
        if (fileType != AikenFileType && fileType != UplcFileType) return false
        return element is PsiNamedElement || element.parent is PsiNamedElement
    }

    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = false

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = false
}
