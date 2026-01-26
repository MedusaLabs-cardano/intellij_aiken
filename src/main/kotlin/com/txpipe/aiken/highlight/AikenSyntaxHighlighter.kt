package com.txpipe.aiken.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.txpipe.aiken.highlight.lexer.AikenTokenTypes
import com.txpipe.aiken.highlight.lexer.KeywordHighlightingLexer
import java.awt.Color

class AikenSyntaxHighlighter : SyntaxHighlighterBase() {
    private val keywords = setOf(
        "if","else","when","is","fn","use","let","pub","type","opaque","const","todo","expect",
        "test","bench","trace","fail","validator","and","or","as","via","once"
    )

    private val tokenSet = KeywordHighlightingLexer.TokenSet(
        keyword = AikenTokenTypes.KEYWORD,
        identifier = AikenTokenTypes.IDENTIFIER,
        type = AikenTokenTypes.TYPE,
        boolean = AikenTokenTypes.BOOLEAN,
        number = AikenTokenTypes.NUMBER,
        string = AikenTokenTypes.STRING,
        comment = AikenTokenTypes.COMMENT,
        whitespace = AikenTokenTypes.WHITESPACE,
        operator = AikenTokenTypes.OPERATOR,
        punctuation = AikenTokenTypes.PUNCT,
        function = AikenTokenTypes.FUNCTION,
        field = AikenTokenTypes.FIELD,
        other = AikenTokenTypes.OTHER,
    )

    override fun getHighlightingLexer(): Lexer = KeywordHighlightingLexer(tokenSet, keywords)

    private val TYPE_BLUE: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(
            "AIKEN_TYPE_BLUE",
            TextAttributes(Color(0x80c0ff), null, null, null, 0) // light blue
        )
    private val FUNC_YELLOW: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(
            "AIKEN_FUNCTION_YELLOW",
            TextAttributes(Color(0xffd75f), null, null, null, 0) // warm yellow
        )

    override fun getTokenHighlights(tokenType: IElementType) = when (tokenType) {
        AikenTokenTypes.KEYWORD -> pack(DefaultLanguageHighlighterColors.KEYWORD)
        AikenTokenTypes.BOOLEAN -> pack(DefaultLanguageHighlighterColors.CONSTANT)
        AikenTokenTypes.STRING -> pack(DefaultLanguageHighlighterColors.STRING)
        AikenTokenTypes.NUMBER -> pack(DefaultLanguageHighlighterColors.NUMBER)
        AikenTokenTypes.COMMENT -> pack(DefaultLanguageHighlighterColors.LINE_COMMENT)
        AikenTokenTypes.TYPE -> pack(TYPE_BLUE)
        AikenTokenTypes.FUNCTION -> pack(FUNC_YELLOW)
        AikenTokenTypes.OPERATOR -> pack(DefaultLanguageHighlighterColors.OPERATION_SIGN)
        AikenTokenTypes.PUNCT -> pack(DefaultLanguageHighlighterColors.COMMA)
        AikenTokenTypes.FIELD -> pack(DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        else -> emptyArray()
    }
}
