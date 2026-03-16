package com.medusalabs.aiken.search

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.index.AIKEN_IMPORT_INDEX_NAME
import com.medusalabs.aiken.index.aikenImportLookupKeysForDeclaration
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

class AikenUseScopeEnlarger : UseScopeEnlarger() {
    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        val file = element.containingFile ?: return null
        if (file.fileType != AikenFileType) return null
        if (!isImportableDeclaration(element)) return null

        val rootScope = AikenSearchScopes.forElement(element)
        val importedFiles = collectImportedFiles(element, rootScope)
        if (importedFiles.isEmpty()) return null

        return GlobalSearchScope.filesScope(element.project, importedFiles).intersectWith(rootScope)
    }

    private fun collectImportedFiles(
        element: PsiElement,
        scope: GlobalSearchScope
    ): Collection<VirtualFile> {
        val project = element.project
        if (DumbService.getInstance(project).isDumb) return emptyList()

        return try {
            val keys = aikenImportLookupKeysForDeclaration(element)
            if (keys.isEmpty()) return emptyList()

            val files = LinkedHashSet<VirtualFile>()
            val index = FileBasedIndex.getInstance()
            for (key in keys) {
                files += index.getContainingFiles(AIKEN_IMPORT_INDEX_NAME, key, scope)
            }
            files
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun isImportableDeclaration(element: PsiElement): Boolean =
        when (element.node?.elementType) {
            AikenTokenTypes.FUNCTION,
            AikenTokenTypes.TYPE -> true
            AikenTokenTypes.IDENTIFIER -> AikenLocalScopeAnalyzer.isConstDeclaration(element)
            else -> false
        }
}
