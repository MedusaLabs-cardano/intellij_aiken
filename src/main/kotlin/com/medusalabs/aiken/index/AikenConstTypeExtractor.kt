package com.medusalabs.aiken.index

import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

data class AikenConstTypeEntry(
    val name: String,
    val type: String,
    val offset: Int,
    val exported: Boolean
)

data class AikenConstDeclaration(
    val name: String,
    val offset: Int,
    val nameEnd: Int,
    val exported: Boolean
)

object AikenConstTypeExtractor {
    fun extract(text: CharSequence): List<AikenConstTypeEntry> {
        val results = ArrayList<AikenConstTypeEntry>()
        for (declaration in extractDeclarations(text)) {
            extractConstType(text, declaration.nameEnd)?.let { constType ->
                if (declaration.name.isNotBlank()) {
                    results += AikenConstTypeEntry(declaration.name, normalizeWhitespace(constType), declaration.offset, declaration.exported)
                }
            }
        }
        return results
    }

    fun extractDeclarations(text: CharSequence): List<AikenConstDeclaration> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val results = ArrayList<AikenConstDeclaration>()
        var braceDepth = 0
        var expectingConstName = false
        var sawPub = false
        var awaitingDeclarationKeyword = false

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            if (expectingConstName) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.IDENTIFIER,
                    AikenTokenTypes.FUNCTION,
                    AikenTokenTypes.FIELD,
                    AikenTokenTypes.TYPE -> {
                        val nameStart = lexer.tokenStart
                        val nameEnd = lexer.tokenEnd
                        val name = text.subSequence(nameStart, nameEnd).toString()
                        if (name.isNotBlank()) {
                            results += AikenConstDeclaration(name, nameStart, nameEnd, sawPub)
                        }
                        expectingConstName = false
                        sawPub = false
                        awaitingDeclarationKeyword = false
                        lexer.advance()
                        continue
                    }
                    else -> {
                        expectingConstName = false
                        sawPub = false
                        awaitingDeclarationKeyword = false
                    }
                }
            }

            if (tokenType == AikenTokenTypes.KEYWORD) {
                val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                when {
                    braceDepth == 0 && word == "pub" -> {
                        sawPub = true
                        awaitingDeclarationKeyword = true
                        expectingConstName = false
                    }
                    braceDepth == 0 && sawPub && awaitingDeclarationKeyword && word == "const" -> {
                        expectingConstName = true
                        awaitingDeclarationKeyword = false
                    }
                    braceDepth == 0 && word == "const" -> {
                        expectingConstName = true
                        sawPub = false
                        awaitingDeclarationKeyword = false
                    }
                    braceDepth == 0 -> {
                        expectingConstName = false
                        sawPub = false
                        awaitingDeclarationKeyword = false
                    }
                }
            }

            lexer.advance()
        }

        return results
    }

    private fun extractConstType(text: CharSequence, afterNameOffset: Int): String? {
        var index = skipWhitespace(text, afterNameOffset)
        if (index >= text.length || text[index] != ':') return null
        index++
        index = skipWhitespace(text, index)

        val typeStart = index
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        while (index < text.length) {
            when (text[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '=' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) break
                '\n', '\r' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) break
            }
            index++
        }

        return text.subSequence(typeStart, index).toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun skipWhitespace(text: CharSequence, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun normalizeWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    builder.append(' ')
                    lastWasSpace = true
                }
            } else {
                builder.append(ch)
                lastWasSpace = false
            }
        }
        return builder.toString().trim()
    }
}
