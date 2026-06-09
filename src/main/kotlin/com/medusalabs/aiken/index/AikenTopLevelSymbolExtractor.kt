package com.medusalabs.aiken.index

import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

enum class AikenTopLevelSymbolKind {
    FUNCTION,
    TYPE,
    CONST,
    CONSTRUCTOR
}

data class AikenTopLevelSymbolEntry(
    val name: String,
    val kind: AikenTopLevelSymbolKind,
    val offset: Int
)

object AikenTopLevelSymbolExtractor {
    fun extract(text: CharSequence): List<AikenTopLevelSymbolEntry> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val results = ArrayList<AikenTopLevelSymbolEntry>()
        var braceDepth = 0
        var expected: AikenTopLevelSymbolKind? = null
        var pendingTypeBody = false
        var typeBodyDepth: Int? = null

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            if (typeBodyDepth != null && braceDepth < typeBodyDepth) {
                typeBodyDepth = null
            }

            if (pendingTypeBody) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.LBRACE -> {
                        typeBodyDepth = braceDepth
                        pendingTypeBody = false
                        lexer.advance()
                        continue
                    }
                    else -> pendingTypeBody = false
                }
            }

            if (typeBodyDepth != null &&
                braceDepth == typeBodyDepth &&
                tokenType == AikenTokenTypes.TYPE &&
                isAtLogicalLineStart(text, lexer.tokenStart)
            ) {
                val name = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                if (name.isNotBlank()) {
                    results += AikenTopLevelSymbolEntry(name, AikenTopLevelSymbolKind.CONSTRUCTOR, lexer.tokenStart)
                }
            }

            if (expected != null) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.IDENTIFIER,
                    AikenTokenTypes.FUNCTION,
                    AikenTokenTypes.TYPE,
                    AikenTokenTypes.FIELD -> {
                        val name = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                        if (name.isNotBlank()) {
                            results += AikenTopLevelSymbolEntry(name, expected!!, lexer.tokenStart)
                        }
                        if (expected == AikenTopLevelSymbolKind.TYPE) {
                            pendingTypeBody = true
                        }
                        expected = null
                        lexer.advance()
                        continue
                    }
                    else -> expected = null
                }
            }

            if (tokenType == AikenTokenTypes.KEYWORD) {
                val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                expected =
                    when (word) {
                        "fn",
                        "test",
                        "bench",
                        "validator" -> AikenTopLevelSymbolKind.FUNCTION
                        "type" -> AikenTopLevelSymbolKind.TYPE
                        "const" -> if (braceDepth == 0) AikenTopLevelSymbolKind.CONST else null
                        else -> null
                    }
            }

            lexer.advance()
        }

        return results
    }

    private fun isAtLogicalLineStart(text: CharSequence, offset: Int): Boolean {
        var index = offset - 1
        while (index >= 0) {
            val ch = text[index]
            if (ch == '\n' || ch == '\r') return true
            if (ch != ' ' && ch != '\t') return false
            index--
        }
        return true
    }
}
