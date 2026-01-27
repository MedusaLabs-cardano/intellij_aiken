package com.medusalabs.aiken.braces

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

class AikenPairedBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> =
        arrayOf(
            BracePair(AikenTokenTypes.LBRACE, AikenTokenTypes.RBRACE, true),
            BracePair(AikenTokenTypes.LPAREN, AikenTokenTypes.RPAREN, true),
            BracePair(AikenTokenTypes.LBRACKET, AikenTokenTypes.RBRACKET, true)
        )

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
