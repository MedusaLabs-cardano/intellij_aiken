package com.medusalabs.aiken.search

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenSearchScopes

object AikenUseScopeProvider {
    fun forElement(element: PsiElement): SearchScope {
        val file = element.containingFile ?: return GlobalSearchScope.EMPTY_SCOPE
        if (file.fileType != AikenFileType) return file.useScope

        val currentFile = file.virtualFile ?: return GlobalSearchScope.EMPTY_SCOPE
        val rootScope = AikenSearchScopes.forElement(element)
        return GlobalSearchScope.fileScope(element.project, currentFile).intersectWith(rootScope)
    }

    fun effectiveForElement(element: PsiElement): SearchScope =
        PsiSearchHelper.getInstance(element.project).getUseScope(element)
}
