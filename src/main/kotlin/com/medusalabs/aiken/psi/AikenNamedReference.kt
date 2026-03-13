package com.medusalabs.aiken.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.medusalabs.aiken.completion.AikenReferenceVariants
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.navigation.AikenDeclarationResolver

class AikenNamedReference(
    element: AikenNamedElement
) : PsiReferenceBase<AikenNamedElement>(element, TextRange(0, element.textLength), false) {
    override fun resolve(): PsiElement? =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, false)

    override fun getVariants(): Array<Any> = AikenReferenceVariants.forElement(element)

    override fun handleElementRename(newElementName: String): PsiElement {
        val namedElement = element as? PsiNamedElement ?: return super.handleElementRename(newElementName)
        return namedElement.setName(newElementName)
    }

    companion object {
        private val RESOLVER =
            object : ResolveCache.AbstractResolver<AikenNamedReference, PsiElement> {
                override fun resolve(reference: AikenNamedReference, incompleteCode: Boolean): PsiElement? =
                    AikenDeclarationResolver.resolve(reference.element)
            }

        fun create(element: AikenNamedElement): AikenNamedReference? {
            val file = element.containingFile ?: return null
            if (file.fileType != AikenFileType) return null

            val resolved = AikenDeclarationResolver.resolve(element)
            if (resolved != null && isSameLocation(element, resolved)) {
                return null
            }

            return AikenNamedReference(element)
        }

        private fun isSameLocation(left: PsiElement, right: PsiElement): Boolean {
            val leftFile = left.containingFile?.virtualFile
            val rightFile = right.containingFile?.virtualFile
            return leftFile == rightFile && left.textRange == right.textRange
        }
    }
}
