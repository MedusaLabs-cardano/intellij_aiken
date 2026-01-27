package com.medusalabs.aiken.highlight.lexer

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType

object UplcLexing {
    val keywords: Set<String> = setOf("program", "con", "builtin", "delay", "force", "error", "lam")

    val tokenSet: KeywordHighlightingLexer.TokenSet =
        KeywordHighlightingLexer.TokenSet(
            lbrace = UplcTokenTypes.LBRACE,
            rbrace = UplcTokenTypes.RBRACE,
            lparen = UplcTokenTypes.LPAREN,
            rparen = UplcTokenTypes.RPAREN,
            lbracket = UplcTokenTypes.LBRACKET,
            rbracket = UplcTokenTypes.RBRACKET,
            keyword = UplcTokenTypes.KEYWORD,
            identifier = UplcTokenTypes.IDENTIFIER,
            type = UplcTokenTypes.TYPE,
            boolean = UplcTokenTypes.BOOLEAN,
            number = UplcTokenTypes.NUMBER,
            string = UplcTokenTypes.STRING,
            comment = UplcTokenTypes.COMMENT,
            whitespace = TokenType.WHITE_SPACE,
            operator = UplcTokenTypes.OPERATOR,
            punctuation = UplcTokenTypes.PUNCT,
            function = UplcTokenTypes.FUNCTION,
            field = UplcTokenTypes.FIELD,
            other = UplcTokenTypes.OTHER
        )

    fun createLexer(): Lexer = KeywordHighlightingLexer(tokenSet, keywords)
}
