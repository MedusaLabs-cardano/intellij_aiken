package com.medusalabs.aiken.usages

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes

private val UPLC_FIND_USAGES_IDENTIFIERS: TokenSet =
    TokenSet.create(
        UplcTokenTypes.IDENTIFIER,
        UplcTokenTypes.TYPE,
        UplcTokenTypes.FUNCTION,
        UplcTokenTypes.FIELD
    )
private val UPLC_FIND_USAGES_COMMENTS: TokenSet = TokenSet.create(UplcTokenTypes.COMMENT)
private val UPLC_FIND_USAGES_STRINGS: TokenSet = TokenSet.create(UplcTokenTypes.STRING)

class UplcFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(UplcLexing.createLexer(), UPLC_FIND_USAGES_IDENTIFIERS, UPLC_FIND_USAGES_COMMENTS, UPLC_FIND_USAGES_STRINGS)

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        val elementType = psiElement.elementTypeOrNull() ?: return false
        return UPLC_FIND_USAGES_IDENTIFIERS.contains(elementType)
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
