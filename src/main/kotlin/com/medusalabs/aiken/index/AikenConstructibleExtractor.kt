package com.medusalabs.aiken.index

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

enum class AikenConstructibleKind {
    TYPE,
    CONSTRUCTOR
}

data class AikenConstructibleFieldEntry(
    val name: String,
    val type: String,
    val offset: Int
)

data class AikenConstructibleEntry(
    val ownerName: String,
    val resultTypeName: String,
    val kind: AikenConstructibleKind,
    val offset: Int,
    val fields: List<AikenConstructibleFieldEntry>,
    val exported: Boolean
)

object AikenConstructibleExtractor {
    fun extract(text: CharSequence): List<AikenConstructibleEntry> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val results = ArrayList<AikenConstructibleEntry>()
        var braceDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var expectingTypeName = false
        var sawPub = false
        var awaitingDeclarationKeyword = false
        var pendingTypeBody: PendingTypeBody? = null
        var currentTypeBody: ActiveTypeBody? = null
        var pendingConstructible: PendingConstructible? = null
        var activeConstructible: ActiveConstructible? = null

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType ?: break

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            when (text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()) {
                "(" -> parenDepth += 1
                ")" -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                "[" -> bracketDepth += 1
                "]" -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                "<" -> angleDepth += 1
                ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }

            if (activeConstructible != null && braceDepth < activeConstructible.bodyDepth) {
                results += activeConstructible.toEntry()
                activeConstructible = null
            }

            if (currentTypeBody != null && braceDepth < currentTypeBody.bodyDepth) {
                if (currentTypeBody.directFields.isNotEmpty()) {
                    results +=
                        AikenConstructibleEntry(
                            ownerName = currentTypeBody.typeName,
                            resultTypeName = currentTypeBody.typeName,
                            kind = AikenConstructibleKind.TYPE,
                            offset = currentTypeBody.offset,
                            fields = currentTypeBody.directFields.toList(),
                            exported = currentTypeBody.exported
                        )
                }
                currentTypeBody = null
            }

            if (pendingTypeBody != null) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.LBRACE -> {
                        currentTypeBody =
                            ActiveTypeBody(
                                typeName = pendingTypeBody.typeName,
                                offset = pendingTypeBody.offset,
                                bodyDepth = braceDepth,
                                exported = pendingTypeBody.exported
                            )
                        pendingTypeBody = null
                        lexer.advance()
                        continue
                    }
                    else -> pendingTypeBody = null
                }
            }

            if (pendingConstructible != null) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.LBRACE -> {
                        activeConstructible =
                            ActiveConstructible(
                                ownerName = pendingConstructible.ownerName,
                                resultTypeName = pendingConstructible.resultTypeName,
                                offset = pendingConstructible.offset,
                                bodyDepth = braceDepth,
                                exported = pendingConstructible.exported
                            )
                        pendingConstructible = null
                        lexer.advance()
                        continue
                    }
                    else -> {
                        results +=
                            AikenConstructibleEntry(
                                ownerName = pendingConstructible.ownerName,
                                resultTypeName = pendingConstructible.resultTypeName,
                                kind = AikenConstructibleKind.CONSTRUCTOR,
                                offset = pendingConstructible.offset,
                                fields = emptyList(),
                                exported = pendingConstructible.exported
                            )
                        pendingConstructible = null
                    }
                }
            }

            if (expectingTypeName) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.TYPE -> {
                        val typeName = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                        if (typeName.isNotBlank()) {
                            pendingTypeBody = PendingTypeBody(typeName, lexer.tokenStart, exported = sawPub)
                        }
                        expectingTypeName = false
                        sawPub = false
                        awaitingDeclarationKeyword = false
                        lexer.advance()
                        continue
                    }
                    else -> {
                        expectingTypeName = false
                        sawPub = false
                        awaitingDeclarationKeyword = false
                    }
                }
            }

            if (activeConstructible != null &&
                braceDepth == activeConstructible.bodyDepth &&
                tokenType == AikenTokenTypes.FIELD
            ) {
                val parsed = parseField(text, lexer.tokenStart, lexer.tokenEnd)
                if (parsed != null) {
                    activeConstructible.fields += parsed.field
                    restartLexer(lexer, text, parsed.resumeOffset)
                    continue
                }
            }

            if (currentTypeBody != null &&
                activeConstructible == null &&
                braceDepth == currentTypeBody.bodyDepth &&
                parenDepth == 0 &&
                bracketDepth == 0 &&
                angleDepth == 0
            ) {
                when (tokenType) {
                    AikenTokenTypes.FIELD -> {
                        val parsed = parseField(text, lexer.tokenStart, lexer.tokenEnd)
                        if (parsed != null) {
                            currentTypeBody.directFields += parsed.field
                            restartLexer(lexer, text, parsed.resumeOffset)
                            continue
                        }
                    }
                    AikenTokenTypes.TYPE -> {
                        val ownerName = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                        if (ownerName.isNotBlank()) {
                            pendingConstructible =
                                PendingConstructible(
                                    ownerName = ownerName,
                                    resultTypeName = currentTypeBody.typeName,
                                    offset = lexer.tokenStart,
                                    exported = currentTypeBody.exported
                                )
                            lexer.advance()
                            continue
                        }
                    }
                }
            }

            if (tokenType == AikenTokenTypes.KEYWORD) {
                val keyword = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                when {
                    braceDepth == 0 && keyword == "pub" -> {
                        sawPub = true
                        awaitingDeclarationKeyword = true
                    }
                    braceDepth == 0 && sawPub && awaitingDeclarationKeyword && keyword == "opaque" -> {}
                    braceDepth == 0 && keyword == "type" -> {
                        expectingTypeName = true
                        awaitingDeclarationKeyword = false
                    }
                    braceDepth == 0 -> {
                        sawPub = false
                        awaitingDeclarationKeyword = false
                    }
                }
            }

            lexer.advance()
        }

        if (pendingConstructible != null) {
            results +=
                AikenConstructibleEntry(
                    ownerName = pendingConstructible.ownerName,
                    resultTypeName = pendingConstructible.resultTypeName,
                    kind = AikenConstructibleKind.CONSTRUCTOR,
                    offset = pendingConstructible.offset,
                    fields = emptyList(),
                    exported = pendingConstructible.exported
                )
        }

        if (activeConstructible != null) {
            results += activeConstructible.toEntry()
        }

        if (currentTypeBody != null && currentTypeBody.directFields.isNotEmpty()) {
            results +=
                AikenConstructibleEntry(
                    ownerName = currentTypeBody.typeName,
                    resultTypeName = currentTypeBody.typeName,
                    kind = AikenConstructibleKind.TYPE,
                    offset = currentTypeBody.offset,
                    fields = currentTypeBody.directFields.toList(),
                    exported = currentTypeBody.exported
                )
        }

        return results
    }

    private fun restartLexer(lexer: Lexer, text: CharSequence, offset: Int) {
        lexer.start(text, offset.coerceIn(0, text.length), text.length, 0)
    }

    private fun parseField(
        text: CharSequence,
        fieldStart: Int,
        fieldEnd: Int
    ): ParsedField? {
        val fieldName = text.subSequence(fieldStart, fieldEnd).toString().trim()
        if (fieldName.isEmpty()) return null

        var index = skipWhitespace(text, fieldEnd)
        if (index >= text.length || text[index] != ':') return null
        index++
        index = skipWhitespace(text, index)

        val typeStart = index
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    break
                }
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        break
                    }
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                }
                ',', '\n', '\r' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        break
                    }
                }
            }
            index++
        }

        val fieldType = normalizeTypeText(text.subSequence(typeStart, index).toString())
        if (fieldType.isEmpty()) return null

        return ParsedField(
            field = AikenConstructibleFieldEntry(fieldName, fieldType, fieldStart),
            resumeOffset = index
        )
    }

    private fun skipWhitespace(text: CharSequence, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun normalizeTypeText(text: String): String {
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

    private data class PendingTypeBody(
        val typeName: String,
        val offset: Int,
        val exported: Boolean
    )

    private data class ActiveTypeBody(
        val typeName: String,
        val offset: Int,
        val bodyDepth: Int,
        val exported: Boolean,
        val directFields: MutableList<AikenConstructibleFieldEntry> = ArrayList()
    )

    private data class PendingConstructible(
        val ownerName: String,
        val resultTypeName: String,
        val offset: Int,
        val exported: Boolean
    )

    private data class ActiveConstructible(
        val ownerName: String,
        val resultTypeName: String,
        val offset: Int,
        val bodyDepth: Int,
        val exported: Boolean,
        val fields: MutableList<AikenConstructibleFieldEntry> = ArrayList()
    ) {
        fun toEntry(): AikenConstructibleEntry =
            AikenConstructibleEntry(
                ownerName = ownerName,
                resultTypeName = resultTypeName,
                kind = AikenConstructibleKind.CONSTRUCTOR,
                offset = offset,
                fields = fields.toList(),
                exported = exported
            )
    }

    private data class ParsedField(
        val field: AikenConstructibleFieldEntry,
        val resumeOffset: Int
    )
}
