package com.medusalabs.aiken.index

import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

object AikenPublicExportExtractor {
    private val exportKeywords = setOf("fn", "const", "type", "validator")

    fun extract(text: CharSequence): List<String> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val results = LinkedHashSet<String>()
        var braceDepth = 0
        var sawPub = false
        var awaitingDeclarationKeyword = false
        var expectingName = false

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType

            when (tokenType) {
                AikenTokenTypes.LBRACE -> {
                    braceDepth += 1
                    if (braceDepth > 0) {
                        sawPub = false
                        awaitingDeclarationKeyword = false
                        expectingName = false
                    }
                }
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            if (braceDepth == 0 && tokenType == AikenTokenTypes.KEYWORD) {
                val keyword = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                when {
                    keyword == "pub" -> {
                        sawPub = true
                        awaitingDeclarationKeyword = true
                        expectingName = false
                    }
                    sawPub && awaitingDeclarationKeyword && keyword == "opaque" -> {}
                    sawPub && awaitingDeclarationKeyword && keyword in exportKeywords -> {
                        expectingName = true
                        awaitingDeclarationKeyword = false
                    }
                    else -> {
                        sawPub = false
                        awaitingDeclarationKeyword = false
                        expectingName = false
                    }
                }
                lexer.advance()
                continue
            }

            if (expectingName) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {}
                    AikenTokenTypes.IDENTIFIER,
                    AikenTokenTypes.FUNCTION,
                    AikenTokenTypes.TYPE -> {
                        val name = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                        if (name.isNotBlank()) {
                            results += name
                        }
                        sawPub = false
                        awaitingDeclarationKeyword = false
                        expectingName = false
                    }
                    else -> {
                        sawPub = false
                        awaitingDeclarationKeyword = false
                        expectingName = false
                    }
                }
                lexer.advance()
                continue
            }

            if (tokenType != TokenType.WHITE_SPACE &&
                tokenType != AikenTokenTypes.WHITESPACE &&
                tokenType != AikenTokenTypes.COMMENT
            ) {
                if (sawPub && !awaitingDeclarationKeyword) {
                    sawPub = false
                }
            }

            lexer.advance()
        }

        return results.toList()
    }
}
