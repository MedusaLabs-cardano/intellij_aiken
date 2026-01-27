package com.medusalabs.aiken.usages

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes

class UplcFindUsagesProvider : FindUsagesProvider {
    companion object {
        private val IDENTIFIERS: TokenSet =
            TokenSet.create(
                UplcTokenTypes.IDENTIFIER,
                UplcTokenTypes.TYPE,
                UplcTokenTypes.FUNCTION,
                UplcTokenTypes.FIELD
            )
        private val COMMENTS: TokenSet = TokenSet.create(UplcTokenTypes.COMMENT)
        private val STRINGS: TokenSet = TokenSet.create(UplcTokenTypes.STRING)
    }

    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(UplcLexing.createLexer(), IDENTIFIERS, COMMENTS, STRINGS)

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        val elementType = psiElement.elementTypeOrNull() ?: return false
        return IDENTIFIERS.contains(elementType)
    }

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        when (element.elementTypeOrNull()) {
            UplcTokenTypes.TYPE -> "type"
            UplcTokenTypes.FUNCTION -> "function"
            UplcTokenTypes.FIELD -> "field"
            else -> "identifier"
        }

    override fun getDescriptiveName(element: PsiElement): String = element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text

    private fun PsiElement.elementTypeOrNull(): IElementType? = node?.elementType
}

