package com.medusalabs.aiken.braces

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes

class UplcPairedBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> =
        arrayOf(
            BracePair(UplcTokenTypes.LBRACE, UplcTokenTypes.RBRACE, true),
            BracePair(UplcTokenTypes.LPAREN, UplcTokenTypes.RPAREN, true),
            BracePair(UplcTokenTypes.LBRACKET, UplcTokenTypes.RBRACKET, true)
        )

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
