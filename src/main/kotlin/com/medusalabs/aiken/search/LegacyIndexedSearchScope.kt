package com.medusalabs.aiken.search

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope

// Legacy indexed/text search helper kept for UPLC until that path is migrated off lexer-driven search.
class LegacyIndexedSearchScope private constructor(
    val indexScope: GlobalSearchScope,
    private val requestedScope: SearchScope,
    private val localScope: LocalSearchScope?
) {
    fun contains(file: VirtualFile): Boolean = indexScope.contains(file) && requestedScope.contains(file)

    fun contains(file: PsiFile, range: TextRange): Boolean {
        val virtualFile = file.virtualFile ?: return false
        if (!contains(virtualFile)) return false
        return localScope?.containsRange(file, range) ?: true
    }

    companion object {
        fun create(
            project: Project,
            rootScope: GlobalSearchScope,
            requestedScope: SearchScope
        ): LegacyIndexedSearchScope {
            val localScope = requestedScope as? LocalSearchScope
            val indexScope =
                when (requestedScope) {
                    is GlobalSearchScope -> rootScope.intersectWith(requestedScope)
                    is LocalSearchScope -> {
                        val localFiles =
                            requestedScope.virtualFiles
                                .asSequence()
                                .filter(rootScope::contains)
                                .distinct()
                                .toList()
                        if (localFiles.isEmpty()) {
                            GlobalSearchScope.EMPTY_SCOPE
                        } else {
                            rootScope.intersectWith(GlobalSearchScope.filesScope(project, localFiles))
                        }
                    }
                    else -> rootScope
                }
            return LegacyIndexedSearchScope(indexScope, requestedScope, localScope)
        }
    }
}
