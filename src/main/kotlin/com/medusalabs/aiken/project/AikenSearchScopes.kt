package com.medusalabs.aiken.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

object AikenSearchScopes {
    fun forFile(project: Project, anchorFile: VirtualFile?): GlobalSearchScope =
        AikenProjectRoots.scopeForFile(project, anchorFile)

    fun forElement(element: PsiElement): GlobalSearchScope =
        forFile(element.project, element.containingFile?.virtualFile)
}
