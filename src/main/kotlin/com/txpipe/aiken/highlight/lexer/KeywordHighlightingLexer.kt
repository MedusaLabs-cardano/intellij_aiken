package com.txpipe.aiken.highlight.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.intellij.util.text.CharArrayUtil

/**
 * Tiny, regex-free lexer good enough for keyword/comment/string/number highlighting.
 */
class KeywordHighlightingLexer(
    private val languageTokenSet: TokenSet,
    private val keywords: Set<String>,
    private val boolLiterals: Set<String> = setOf("True", "False"),
    private val lineCommentPrefix: String = "//"
) : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenStart = startOffset
        locateToken(tokenStart)
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        locateToken(tokenStart)
    }

    private fun locateToken(offset: Int) {
        if (offset >= endOffset) {
            tokenType = null
            return
        }

        val ch = buffer[offset]

        // Whitespace
        if (ch.isWhitespace()) {
            val next = CharArrayUtil.shiftForward(buffer, offset, endOffset, " \t\n\r")
            tokenStart = offset
            tokenEnd = next
            tokenType = languageTokenSet.whitespace
            return
        }

        // Line comment
        if (buffer.startsWith(lineCommentPrefix, offset)) {
            tokenStart = offset
            var i = offset + lineCommentPrefix.length
            while (i < endOffset && buffer[i] != '\n' && buffer[i] != '\r') i++
            tokenEnd = i
            tokenType = languageTokenSet.comment
            return
        }

        // String literal (")
        if (ch == '"') {
            tokenStart = offset
            var i = offset + 1
            while (i < endOffset) {
                val c = buffer[i]
                if (c == '\\' && i + 1 < endOffset) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    i++
                    break
                }
                i++
            }
            tokenEnd = i.coerceAtMost(endOffset)
            tokenType = languageTokenSet.string
            return
        }

        // Number (decimal, hex 0x, binary 0b, octal 0o)
        if (ch.isDigit()) {
            tokenStart = offset
            var i = offset
            if (ch == '0' && i + 1 < endOffset && buffer[i + 1].lowercaseChar() in listOf('x', 'b', 'o')) {
                i += 2
                while (i < endOffset && buffer[i].isLetterOrDigit()) i++
            } else {
                while (i < endOffset && (buffer[i].isDigit() || buffer[i] == '_' )) i++
                if (i < endOffset && buffer[i] == '.') {
                    i++
                    while (i < endOffset && buffer[i].isDigit()) i++
                }
            }
            tokenEnd = i
            tokenType = languageTokenSet.number
            return
        }

        // Identifier / keyword / type / function / field label
        if (ch.isLetter() || ch == '_') {
            tokenStart = offset
            var i = offset + 1
            while (i < endOffset && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++
            val word = buffer.subSequence(offset, i).toString()
            tokenEnd = i
            tokenType = when {
                keywords.contains(word) -> languageTokenSet.keyword
                boolLiterals.contains(word) -> languageTokenSet.boolean
                word.firstOrNull()?.isUpperCase() == true -> languageTokenSet.type
                looksLikeFunction(i) -> languageTokenSet.function
                looksLikeField(i) -> languageTokenSet.field
                else -> languageTokenSet.identifier
            }
            return
        }

        // Fallback: single char token
        tokenStart = offset
        tokenEnd = offset + 1
        tokenType = when (ch) {
            '-', '>', '<', '|', '=', '+', '*', '/', '%', '.' -> languageTokenSet.operator
            '(', ')', '{', '}', '[', ']', ',', ':' -> languageTokenSet.punctuation
            else -> languageTokenSet.other
        }
    }

    private fun looksLikeFunction(afterWordIndex: Int): Boolean {
        var i = afterWordIndex
        while (i < endOffset && buffer[i].isWhitespace()) i++
        return i < endOffset && buffer[i] == '('
    }

    private fun looksLikeField(afterWordIndex: Int): Boolean {
        var i = afterWordIndex
        while (i < endOffset && buffer[i].isWhitespace()) i++
        return i < endOffset && buffer[i] == ':'
    }

    data class TokenSet(
        val keyword: IElementType,
        val identifier: IElementType,
        val type: IElementType,
        val boolean: IElementType,
        val number: IElementType,
        val string: IElementType,
        val comment: IElementType,
        val whitespace: IElementType,
        val operator: IElementType,
        val punctuation: IElementType,
        val function: IElementType,
        val field: IElementType,
        val other: IElementType
    )
}
