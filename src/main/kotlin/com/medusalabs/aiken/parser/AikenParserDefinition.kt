package com.medusalabs.aiken.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenLanguage
import com.medusalabs.aiken.psi.AikenPsiFile

class AikenParserDefinition : ParserDefinition {
    companion object {
        val FILE: IFileElementType = IFileElementType(AikenLanguage)
        private val WHITESPACE: TokenSet = TokenSet.create(TokenType.WHITE_SPACE, AikenTokenTypes.WHITESPACE)
        private val COMMENTS: TokenSet = TokenSet.create(AikenTokenTypes.COMMENT)
        private val STRINGS: TokenSet = TokenSet.create(AikenTokenTypes.STRING)
    }

    override fun createLexer(project: Project?): Lexer = AikenLexing.createLexer()

    override fun createParser(project: Project?) = FlatPsiParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getWhitespaceTokens(): TokenSet = WHITESPACE

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = AikenPsiFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY
}
