package com.medusalabs.aiken.usages

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

class AikenFindUsagesProvider : FindUsagesProvider {
    companion object {
        private val IDENTIFIERS: TokenSet =
            TokenSet.create(
                AikenTokenTypes.IDENTIFIER,
                AikenTokenTypes.TYPE,
                AikenTokenTypes.FUNCTION,
                AikenTokenTypes.FIELD
            )
        private val COMMENTS: TokenSet = TokenSet.create(AikenTokenTypes.COMMENT)
        private val STRINGS: TokenSet = TokenSet.create(AikenTokenTypes.STRING)
    }

    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(AikenLexing.createLexer(), IDENTIFIERS, COMMENTS, STRINGS)

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        val elementType = psiElement.elementTypeOrNull()
        if (elementType != null && IDENTIFIERS.contains(elementType)) return true
        val parentType = psiElement.parent?.elementTypeOrNull() ?: return false
        return IDENTIFIERS.contains(parentType)
    }

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        when (element.elementTypeOrNull()) {
            AikenTokenTypes.TYPE -> "type"
            AikenTokenTypes.FUNCTION -> "function"
            AikenTokenTypes.FIELD -> "field"
            else -> "identifier"
        }

    override fun getDescriptiveName(element: PsiElement): String = element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text

    private fun PsiElement.elementTypeOrNull(): IElementType? = node?.elementType
}
