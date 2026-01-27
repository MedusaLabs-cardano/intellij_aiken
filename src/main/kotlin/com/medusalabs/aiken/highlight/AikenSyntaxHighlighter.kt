package com.medusalabs.aiken.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.intellij.ui.JBColor
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import java.awt.Color
import java.awt.Font

class AikenSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = AikenLexing.createLexer()

    companion object {
        val BRACES: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val BRACKETS: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val PARENTHESES: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)

        val KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val BOOLEAN: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_BOOLEAN", DefaultLanguageHighlighterColors.CONSTANT)
        val STRING: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val TYPE: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "AIKEN_TYPE",
                TextAttributes(
                    JBColor(Color(0x0055AA), Color(0x80C0FF)), // dark: light blue, light: darker blue
                    null,
                    null,
                    null,
                    Font.PLAIN
                )
            )
        val FUNCTION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "AIKEN_FUNCTION",
                TextAttributes(
                    JBColor(Color(0x8A6A00), Color(0xFFD75F)), // dark: warm yellow, light: darker amber
                    null,
                    null,
                    null,
                    Font.PLAIN
                )
            )
        val OPERATOR: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val PUNCTUATION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_PUNCTUATION", DefaultLanguageHighlighterColors.COMMA)
        val FIELD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("AIKEN_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    }

    override fun getTokenHighlights(tokenType: IElementType) = when (tokenType) {
        AikenTokenTypes.LBRACE, AikenTokenTypes.RBRACE -> pack(BRACES)
        AikenTokenTypes.LPAREN, AikenTokenTypes.RPAREN -> pack(PARENTHESES)
        AikenTokenTypes.LBRACKET, AikenTokenTypes.RBRACKET -> pack(BRACKETS)
        AikenTokenTypes.KEYWORD -> pack(KEYWORD)
        AikenTokenTypes.BOOLEAN -> pack(BOOLEAN)
        AikenTokenTypes.STRING -> pack(STRING)
        AikenTokenTypes.NUMBER -> pack(NUMBER)
        AikenTokenTypes.COMMENT -> pack(COMMENT)
        AikenTokenTypes.TYPE -> pack(TYPE)
        AikenTokenTypes.FUNCTION -> pack(FUNCTION)
        AikenTokenTypes.OPERATOR -> pack(OPERATOR)
        AikenTokenTypes.PUNCT -> pack(PUNCTUATION)
        AikenTokenTypes.FIELD -> pack(FIELD)
        else -> emptyArray()
    }
}
