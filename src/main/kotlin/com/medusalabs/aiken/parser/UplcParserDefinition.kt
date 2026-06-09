package com.medusalabs.aiken.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.lang.UplcLanguage
import com.medusalabs.aiken.psi.UplcNamedElement
import com.medusalabs.aiken.psi.UplcPsiFile

val UPLC_FILE_ELEMENT_TYPE: IFileElementType = IFileElementType(UplcLanguage)
private val UPLC_WHITESPACE_TOKENS: TokenSet = TokenSet.create(TokenType.WHITE_SPACE, UplcTokenTypes.WHITESPACE)
private val UPLC_COMMENT_TOKENS: TokenSet = TokenSet.create(UplcTokenTypes.COMMENT)
private val UPLC_STRING_TOKENS: TokenSet = TokenSet.create(UplcTokenTypes.STRING)

class UplcParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = UplcLexing.createLexer()

    override fun createParser(project: Project?) = FlatPsiParser()

    override fun getFileNodeType(): IFileElementType = UPLC_FILE_ELEMENT_TYPE

    override fun getWhitespaceTokens(): TokenSet = UPLC_WHITESPACE_TOKENS

    override fun getCommentTokens(): TokenSet = UPLC_COMMENT_TOKENS

    override fun getStringLiteralElements(): TokenSet = UPLC_STRING_TOKENS

    override fun createElement(node: ASTNode): PsiElement =
        when (node.elementType) {
            UplcTokenTypes.IDENTIFIER,
            UplcTokenTypes.TYPE,
            UplcTokenTypes.FUNCTION,
            UplcTokenTypes.FIELD -> UplcNamedElement(node)
            else -> com.intellij.extapi.psi.ASTWrapperPsiElement(node)
        }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = UplcPsiFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY
}
