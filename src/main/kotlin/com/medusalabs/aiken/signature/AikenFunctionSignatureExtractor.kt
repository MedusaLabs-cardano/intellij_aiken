package com.medusalabs.aiken.signature

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

object AikenFunctionSignatureExtractor {
    private val declarationKeywords: Set<String> = setOf("fn", "test", "bench", "validator")

    fun extract(text: CharSequence): Map<String, String> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val result = HashMap<String, String>()
        var expectingFunctionName = false
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType

            if (tokenType == AikenTokenTypes.KEYWORD) {
                val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                expectingFunctionName = declarationKeywords.contains(word)
                lexer.advance()
                continue
            }

            if (expectingFunctionName) {
                when {
                    tokenType == TokenType.WHITE_SPACE -> {}
                    tokenType == AikenTokenTypes.IDENTIFIER || tokenType == AikenTokenTypes.FUNCTION -> {
                        val nameStart = lexer.tokenStart
                        val nameEnd = lexer.tokenEnd
                        val name = text.subSequence(nameStart, nameEnd).toString()
                        extractSignature(text, nameStart, nameEnd, lexer)?.let { signature ->
                            if (name.length >= 2) {
                                result[name] = signature
                            }
                        }
                        expectingFunctionName = false
                    }
                    else -> expectingFunctionName = false
                }
                lexer.advance()
                continue
            }

            lexer.advance()
        }

        return result
    }

    private fun extractSignature(
        text: CharSequence,
        nameStart: Int,
        nameEnd: Int,
        lexer: Lexer
    ): String? {
        if (lexer.tokenType == AikenTokenTypes.STRING || lexer.tokenType == AikenTokenTypes.COMMENT) return null

        val name = text.subSequence(nameStart, nameEnd).toString()

        var i = nameEnd
        i = skipWhitespace(text, i)

        // Optional generic parameters: <a, b>
        if (i < text.length && text[i] == '<') {
            i = skipAngleBrackets(text, i) ?: return null
            i = skipWhitespace(text, i)
        }

        if (i >= text.length || text[i] != '(') return null
        val lparen = i
        val rparen = findMatchingParen(text, lparen) ?: return null

        val params = text.subSequence(lparen, rparen + 1).toString()

        var j = rparen + 1
        j = skipWhitespace(text, j)

        val returnType =
            if (j + 1 < text.length && text[j] == '-' && text[j + 1] == '>') {
                j += 2
                j = skipWhitespace(text, j)
                val start = j
                while (j < text.length) {
                    val ch = text[j]
                    if (ch == '{' || ch == '=' || ch == '\n' || ch == '\r') break
                    j++
                }
                text.subSequence(start, j).toString().trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }

        val signature =
            buildString {
                append(name)
                append(params)
                if (returnType != null) {
                    append(" -> ")
                    append(returnType)
                }
            }

        return normalizeWhitespace(signature)
    }

    private fun skipWhitespace(text: CharSequence, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun skipAngleBrackets(text: CharSequence, start: Int): Int? {
        var i = start
        if (i >= text.length || text[i] != '<') return null
        var depth = 0
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
                '\n', '\r', '{', '}' -> return null
            }
            i++
        }
        return null
    }

    private fun findMatchingParen(text: CharSequence, openIndex: Int): Int? {
        if (openIndex >= text.length || text[openIndex] != '(') return null

        var inString = false
        var inLineComment = false
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < text.length && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }

        return null
    }

    private fun normalizeWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }
}

