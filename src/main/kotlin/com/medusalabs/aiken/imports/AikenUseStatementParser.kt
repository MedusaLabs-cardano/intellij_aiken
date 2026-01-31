package com.medusalabs.aiken.imports

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes

object AikenUseStatementParser {
    data class UseItem(
        val name: String,
        val nameRange: TextRange,
        val alias: String? = null,
        val aliasRange: TextRange? = null
    )

    data class UseStatement(
        val modulePath: String,
        val modulePathRange: TextRange?,
        val items: List<UseItem>,
        val statementRange: TextRange
    )

    fun parse(text: CharSequence): List<UseStatement> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val result = ArrayList<UseStatement>()
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            if (tokenType == AikenTokenTypes.KEYWORD &&
                text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString() == "use"
            ) {
                val useStart = lexer.tokenStart
                lexer.advance()

                // Parse module path until ".{" or "{" or end-of-line.
                skipWhitespace(lexer, text)
                val modulePathStart = lexer.tokenStart
                val modulePathBuilder = StringBuilder()
                var modulePathEnd = modulePathStart
                var foundImportListStart: Boolean
                while (lexer.tokenType != null) {
                    val t = lexer.tokenType
                    val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

                    if (t == TokenType.WHITE_SPACE) {
                        if (tokenText.contains('\n')) break
                        lexer.advance()
                        continue
                    }

                    if (t == AikenTokenTypes.COMMENT) {
                        break
                    }

                    // Module alias import: `use foo/bar as baz` (no imported items) -> stop.
                    if (t == AikenTokenTypes.KEYWORD && tokenText == "as") {
                        break
                    }

                    // Delimiter before import list: `use foo/bar.{...}`.
                    if (t == AikenTokenTypes.OPERATOR && tokenText == ".") {
                        val dotOffset = lexer.tokenStart
                        lexer.advance()
                        skipWhitespace(lexer, text)
                        if (lexer.tokenType == AikenTokenTypes.LBRACE) {
                            modulePathEnd = dotOffset
                            foundImportListStart = true
                            break
                        }
                        modulePathBuilder.append(".")
                        modulePathEnd = dotOffset + 1
                        continue
                    }

                    if (t == AikenTokenTypes.LBRACE) {
                        modulePathEnd = lexer.tokenStart
                        foundImportListStart = true
                        break
                    }

                    modulePathBuilder.append(tokenText)
                    modulePathEnd = lexer.tokenEnd
                    lexer.advance()
                }
                foundImportListStart = lexer.tokenType == AikenTokenTypes.LBRACE

                val modulePath = modulePathBuilder.toString().trim()
                val modulePathRange =
                    run {
                        val start = modulePathStart
                        val end = modulePathEnd
                        if (start < end) TextRange(start, end) else null
                    }

                val items =
                    if (foundImportListStart && lexer.tokenType == AikenTokenTypes.LBRACE) {
                        parseImportItems(text, lexer)
                    } else {
                        emptyList()
                    }

                val statementEnd =
                    items.lastOrNull()?.aliasRange?.endOffset
                        ?: items.lastOrNull()?.nameRange?.endOffset
                        ?: (modulePathRange?.endOffset ?: (lexer.tokenEnd.takeIf { it > useStart } ?: useStart))

                result.add(
                    UseStatement(
                        modulePath = modulePath,
                        modulePathRange = modulePathRange,
                        items = items,
                        statementRange = TextRange(useStart, statementEnd)
                    )
                )

                continue
            }

            lexer.advance()
        }

        return result
    }

    private fun parseImportItems(text: CharSequence, lexer: com.intellij.lexer.Lexer): List<UseItem> {
        // Assumes current token is `{`.
        lexer.advance()

        val items = ArrayList<UseItem>()
        var braceDepth = 1

        while (lexer.tokenType != null && braceDepth > 0) {
            val t = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            when (t) {
                TokenType.WHITE_SPACE,
                AikenTokenTypes.PUNCT,
                AikenTokenTypes.OPERATOR -> {
                    if ((t == AikenTokenTypes.PUNCT || t == AikenTokenTypes.OPERATOR) && (tokenText == "," || tokenText == ".")) {
                        // separator
                    }
                    lexer.advance()
                }
                AikenTokenTypes.LBRACE -> {
                    braceDepth += 1
                    lexer.advance()
                }
                AikenTokenTypes.RBRACE -> {
                    braceDepth -= 1
                    lexer.advance()
                }
                AikenTokenTypes.IDENTIFIER,
                AikenTokenTypes.TYPE -> {
                    val name = tokenText
                    val nameRange = TextRange(lexer.tokenStart, lexer.tokenEnd)
                    lexer.advance()

                    skipWhitespace(lexer, text)
                    val (alias, aliasRange) = parseAlias(text, lexer)

                    items.add(UseItem(name = name, nameRange = nameRange, alias = alias, aliasRange = aliasRange))
                }
                else -> {
                    lexer.advance()
                }
            }
        }

        return items
    }

    private fun parseAlias(text: CharSequence, lexer: com.intellij.lexer.Lexer): Pair<String?, TextRange?> {
        val t = lexer.tokenType
        val tokenText = t?.let { text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString() }
        if (t == AikenTokenTypes.KEYWORD && tokenText == "as") {
            lexer.advance()
            skipWhitespace(lexer, text)
            val aliasTokenType = lexer.tokenType
            if (aliasTokenType == AikenTokenTypes.IDENTIFIER || aliasTokenType == AikenTokenTypes.TYPE) {
                val aliasText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                val range = TextRange(lexer.tokenStart, lexer.tokenEnd)
                lexer.advance()
                return aliasText to range
            }
        }
        return null to null
    }

    private fun skipWhitespace(lexer: com.intellij.lexer.Lexer, text: CharSequence) {
        while (lexer.tokenType == TokenType.WHITE_SPACE) {
            val ws = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (ws.contains('\n')) return
            lexer.advance()
        }
    }
}
