package com.medusalabs.aiken.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes

/**
 * Minimal parser that produces a flat PSI tree: a single file root with all lexer tokens as leaves.
 *
 * This is enough to enable editor features like brace matching and folding without a full grammar.
 */
class FlatPsiParser : PsiParser {
    private val namedTokenTypes: TokenSet =
        TokenSet.create(
            AikenTokenTypes.IDENTIFIER,
            AikenTokenTypes.TYPE,
            AikenTokenTypes.FUNCTION,
            AikenTokenTypes.FIELD,
            UplcTokenTypes.IDENTIFIER,
            UplcTokenTypes.TYPE,
            UplcTokenTypes.FUNCTION,
            UplcTokenTypes.FIELD
        )

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            val tokenType = builder.tokenType
            if (tokenType != null && namedTokenTypes.contains(tokenType)) {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(tokenType)
            } else {
                builder.advanceLexer()
            }
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }
}
