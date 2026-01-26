package com.txpipe.aiken.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.txpipe.aiken.highlight.lexer.KeywordHighlightingLexer
import com.txpipe.aiken.highlight.lexer.UplcTokenTypes
import java.awt.Color

class UplcSyntaxHighlighter : SyntaxHighlighterBase() {
    private val keywords = setOf("program","con","builtin","delay","force","error","lam")
    private val tokenSet = KeywordHighlightingLexer.TokenSet(
        keyword = UplcTokenTypes.KEYWORD,
        identifier = UplcTokenTypes.IDENTIFIER,
        type = UplcTokenTypes.TYPE,
        boolean = UplcTokenTypes.BOOLEAN,
        number = UplcTokenTypes.NUMBER,
        string = UplcTokenTypes.STRING,
        comment = UplcTokenTypes.COMMENT,
        whitespace = UplcTokenTypes.WHITESPACE,
        operator = UplcTokenTypes.OPERATOR,
        punctuation = UplcTokenTypes.PUNCT,
        function = UplcTokenTypes.FUNCTION,
        field = UplcTokenTypes.FIELD,
        other = UplcTokenTypes.OTHER,
    )

    override fun getHighlightingLexer(): Lexer = KeywordHighlightingLexer(tokenSet, keywords)

    private val TYPE_BLUE: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(
            "UPLC_TYPE_BLUE",
            TextAttributes(Color(0x80c0ff), null, null, null, 0)
        )
    private val FUNC_YELLOW: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(
            "UPLC_FUNCTION_YELLOW",
            TextAttributes(Color(0xffd75f), null, null, null, 0)
        )

    override fun getTokenHighlights(tokenType: IElementType) = when (tokenType) {
        UplcTokenTypes.KEYWORD -> pack(DefaultLanguageHighlighterColors.KEYWORD)
        UplcTokenTypes.BOOLEAN -> pack(DefaultLanguageHighlighterColors.CONSTANT)
        UplcTokenTypes.STRING -> pack(DefaultLanguageHighlighterColors.STRING)
        UplcTokenTypes.NUMBER -> pack(DefaultLanguageHighlighterColors.NUMBER)
        UplcTokenTypes.COMMENT -> pack(DefaultLanguageHighlighterColors.LINE_COMMENT)
        UplcTokenTypes.TYPE -> pack(TYPE_BLUE)
        UplcTokenTypes.FUNCTION -> pack(FUNC_YELLOW)
        UplcTokenTypes.OPERATOR -> pack(DefaultLanguageHighlighterColors.OPERATION_SIGN)
        UplcTokenTypes.PUNCT -> pack(DefaultLanguageHighlighterColors.COMMA)
        UplcTokenTypes.FIELD -> pack(DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        else -> emptyArray()
    }
}
