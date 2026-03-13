package com.medusalabs.aiken.search

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenSearchScopes

object AikenUsageSearchSupport {
    data class ReferenceHit(
        val reference: PsiReference,
        val file: PsiFile,
        val virtualFile: VirtualFile,
        val range: TextRange
    )

    fun collectAikenReferences(
        resolvedTarget: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean = false
    ): List<PsiReference> =
        ReferencesSearch.search(resolvedTarget, searchScope, searchInCommentsAndStrings)
            .findAll()
            .toList()

    fun findResolvedAikenReferencesInLocalScope(
        resolvedTarget: PsiElement,
        localScope: LocalSearchScope,
        searchInCommentsAndStrings: Boolean = false
    ): List<ReferenceHit> {
        if (resolvedTarget.containingFile?.fileType != AikenFileType) return emptyList()

        val rootScope = AikenSearchScopes.forElement(resolvedTarget)
        val declarationFile = resolvedTarget.containingFile?.virtualFile
        val declarationRange = resolvedTarget.textRange
        val hits = LinkedHashMap<Pair<VirtualFile, TextRange>, ReferenceHit>()
        // IntelliJ's ReferencesSearch misses our Aiken references when queried with LocalSearchScope directly,
        // so for Aiken we search within the root/useScope and apply the local restriction after resolve validation.
        val queryScope = AikenUseScopeProvider.effectiveForElement(resolvedTarget).intersectWith(rootScope)
        val rawReferences = collectAikenReferences(resolvedTarget, queryScope, searchInCommentsAndStrings)

        for (reference in rawReferences) {
            val file = reference.element.containingFile ?: continue
            val virtualFile = file.virtualFile ?: continue
            val range = absoluteRange(reference)
            if (!rootScope.contains(virtualFile)) continue
            if (!localScope.containsRange(file, range)) continue
            if (!referenceMatchesTarget(reference, resolvedTarget)) continue
            if (virtualFile == declarationFile && range == declarationRange) continue
            hits.putIfAbsent(
                virtualFile to range,
                ReferenceHit(reference = reference, file = file, virtualFile = virtualFile, range = range)
            )
        }

        return hits.values.toList()
    }

    private fun referenceMatchesTarget(reference: PsiReference, resolvedTarget: PsiElement): Boolean {
        val resolved = reference.resolve() ?: return false
        return isSameTarget(resolved, resolvedTarget)
    }

    private fun absoluteRange(reference: PsiReference): TextRange =
        reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)

    fun isSameTarget(left: PsiElement, right: PsiElement): Boolean {
        val leftFile = left.containingFile?.virtualFile
        val rightFile = right.containingFile?.virtualFile
        return leftFile == rightFile && left.textRange == right.textRange
    }
}
