package com.medusalabs.aiken.highlight.lexer

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType

object AikenLexing {
    val keywords: Set<String> =
        setOf(
            "if",
            "else",
            "when",
            "is",
            "fn",
            "use",
            "let",
            "pub",
            "type",
            "opaque",
            "const",
            "todo",
            "expect",
            "test",
            "bench",
            "trace",
            "fail",
            "validator",
            "and",
            "or",
            "as",
            "via",
            "once"
        )

    val tokenSet: KeywordHighlightingLexer.TokenSet =
        KeywordHighlightingLexer.TokenSet(
            lbrace = AikenTokenTypes.LBRACE,
            rbrace = AikenTokenTypes.RBRACE,
            lparen = AikenTokenTypes.LPAREN,
            rparen = AikenTokenTypes.RPAREN,
            lbracket = AikenTokenTypes.LBRACKET,
            rbracket = AikenTokenTypes.RBRACKET,
            keyword = AikenTokenTypes.KEYWORD,
            identifier = AikenTokenTypes.IDENTIFIER,
            type = AikenTokenTypes.TYPE,
            boolean = AikenTokenTypes.BOOLEAN,
            number = AikenTokenTypes.NUMBER,
            string = AikenTokenTypes.STRING,
            comment = AikenTokenTypes.COMMENT,
            whitespace = TokenType.WHITE_SPACE,
            operator = AikenTokenTypes.OPERATOR,
            punctuation = AikenTokenTypes.PUNCT,
            function = AikenTokenTypes.FUNCTION,
            field = AikenTokenTypes.FIELD,
            other = AikenTokenTypes.OTHER
        )

    fun createLexer(): Lexer = KeywordHighlightingLexer(tokenSet, keywords)
}
