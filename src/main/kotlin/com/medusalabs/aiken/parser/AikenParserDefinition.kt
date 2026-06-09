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
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenLanguage
import com.medusalabs.aiken.psi.AikenNamedElement
import com.medusalabs.aiken.psi.AikenPsiFile

val AIKEN_FILE_ELEMENT_TYPE: IFileElementType = IFileElementType(AikenLanguage)
private val AIKEN_WHITESPACE_TOKENS: TokenSet = TokenSet.create(TokenType.WHITE_SPACE, AikenTokenTypes.WHITESPACE)
private val AIKEN_COMMENT_TOKENS: TokenSet = TokenSet.create(AikenTokenTypes.COMMENT)
private val AIKEN_STRING_TOKENS: TokenSet = TokenSet.create(AikenTokenTypes.STRING)

class AikenParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = AikenLexing.createLexer()

    override fun createParser(project: Project?) = FlatPsiParser()

    override fun getFileNodeType(): IFileElementType = AIKEN_FILE_ELEMENT_TYPE

    override fun getWhitespaceTokens(): TokenSet = AIKEN_WHITESPACE_TOKENS

    override fun getCommentTokens(): TokenSet = AIKEN_COMMENT_TOKENS

    override fun getStringLiteralElements(): TokenSet = AIKEN_STRING_TOKENS

    override fun createElement(node: ASTNode): PsiElement =
        when (node.elementType) {
            AikenTokenTypes.IDENTIFIER,
            AikenTokenTypes.TYPE,
            AikenTokenTypes.FUNCTION,
            AikenTokenTypes.FIELD -> AikenNamedElement(node)
            else -> com.intellij.extapi.psi.ASTWrapperPsiElement(node)
        }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = AikenPsiFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY
}
