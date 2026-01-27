package com.medusalabs.aiken.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.intellij.ui.JBColor
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import java.awt.Color
import java.awt.Font

class UplcSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = UplcLexing.createLexer()

    companion object {
        val BRACES: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val BRACKETS: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val PARENTHESES: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)

        val KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val BOOLEAN: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_BOOLEAN", DefaultLanguageHighlighterColors.CONSTANT)
        val STRING: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val TYPE: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "UPLC_TYPE",
                TextAttributes(
                    JBColor(Color(0x0055AA), Color(0x80C0FF)),
                    null,
                    null,
                    null,
                    Font.PLAIN
                )
            )
        val FUNCTION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "UPLC_FUNCTION",
                TextAttributes(
                    JBColor(Color(0x8A6A00), Color(0xFFD75F)),
                    null,
                    null,
                    null,
                    Font.PLAIN
                )
            )
        val OPERATOR: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val PUNCTUATION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_PUNCTUATION", DefaultLanguageHighlighterColors.COMMA)
        val FIELD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("UPLC_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    }

    override fun getTokenHighlights(tokenType: IElementType) = when (tokenType) {
        UplcTokenTypes.LBRACE, UplcTokenTypes.RBRACE -> pack(BRACES)
        UplcTokenTypes.LPAREN, UplcTokenTypes.RPAREN -> pack(PARENTHESES)
        UplcTokenTypes.LBRACKET, UplcTokenTypes.RBRACKET -> pack(BRACKETS)
        UplcTokenTypes.KEYWORD -> pack(KEYWORD)
        UplcTokenTypes.BOOLEAN -> pack(BOOLEAN)
        UplcTokenTypes.STRING -> pack(STRING)
        UplcTokenTypes.NUMBER -> pack(NUMBER)
        UplcTokenTypes.COMMENT -> pack(COMMENT)
        UplcTokenTypes.TYPE -> pack(TYPE)
        UplcTokenTypes.FUNCTION -> pack(FUNCTION)
        UplcTokenTypes.OPERATOR -> pack(OPERATOR)
        UplcTokenTypes.PUNCT -> pack(PUNCTUATION)
        UplcTokenTypes.FIELD -> pack(FIELD)
        else -> emptyArray()
    }
}
