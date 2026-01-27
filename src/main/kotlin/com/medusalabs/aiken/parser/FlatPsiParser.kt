package com.medusalabs.aiken.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * Minimal parser that produces a flat PSI tree: a single file root with all lexer tokens as leaves.
 *
 * This is enough to enable editor features like brace matching and folding without a full grammar.
 */
class FlatPsiParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }
}

