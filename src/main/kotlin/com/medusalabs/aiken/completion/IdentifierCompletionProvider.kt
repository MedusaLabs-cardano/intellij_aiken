package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext

class IdentifierCompletionProvider(
    private val lexerFactory: () -> Lexer,
    private val kindByTokenType: Map<IElementType, CompletionSymbolKind>,
    private val stopTokenTypes: Set<IElementType>,
    private val basePriority: Double = 2000.0,
    private val keywordTokenType: IElementType? = null,
    private val declarationKindByKeyword: Map<String, CompletionSymbolKind> = emptyMap(),
    private val skipDeclarationKeywords: Set<String> = emptySet(),
    private val bindingKeywords: Set<String> = emptySet(),
    private val includeNonDeclarationIdentifiers: Boolean = true,
    private val nonDeclarationIdentifierPriority: Double = 900.0
) : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val elementType = parameters.position.node.elementType
        if (stopTokenTypes.contains(elementType)) return

        val text = parameters.originalFile.text
        val lexer = lexerFactory()
        lexer.start(text)

        val prefixMatcher = result.prefixMatcher
        val nameTokenTypes = kindByTokenType.keys
        val seen = HashSet<String>()
        val suppressedNames = HashSet<String>()
        var expectedKind: CompletionSymbolKind? = null
        var collectingBindings: Boolean = false
        var suppressNextDeclarationName: Boolean = false
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            if (tokenType != null && tokenType == keywordTokenType) {
                val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                collectingBindings = bindingKeywords.contains(word)
                suppressNextDeclarationName = !collectingBindings && skipDeclarationKeywords.contains(word)
                expectedKind = if (collectingBindings || suppressNextDeclarationName) null else declarationKindByKeyword[word]
                lexer.advance()
                continue
            }

            if (collectingBindings) {
                when {
                    tokenType == TokenType.WHITE_SPACE -> {}
                    text.substring(lexer.tokenStart, lexer.tokenEnd) == "=" -> collectingBindings = false
                    tokenType != null && kindByTokenType[tokenType] == CompletionSymbolKind.IDENTIFIER -> {
                        val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                        if (word.length >= 2 && seen.add(word) && prefixMatcher.prefixMatches(word)) {
                            result.addElement(
                                CompletionItemFactory.create(
                                    word,
                                    CompletionSymbolKind.IDENTIFIER,
                                    basePriority
                                )
                            )
                        }
                    }
                }
                lexer.advance()
                continue
            }

            if (suppressNextDeclarationName) {
                when {
                    tokenType == TokenType.WHITE_SPACE -> {}
                    tokenType != null && nameTokenTypes.contains(tokenType) -> {
                        val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                        if (word.length >= 2) {
                            suppressedNames.add(word)
                        }
                        suppressNextDeclarationName = false
                    }
                    else -> suppressNextDeclarationName = false
                }
                lexer.advance()
                continue
            }

            if (expectedKind != null) {
                when {
                    tokenType == TokenType.WHITE_SPACE -> {
                        lexer.advance()
                        continue
                    }

                    tokenType != null && nameTokenTypes.contains(tokenType) -> {
                        val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                        if (word.length >= 2 &&
                            !suppressedNames.contains(word) &&
                            seen.add(word) &&
                            prefixMatcher.prefixMatches(word)
                        ) {
                            result.addElement(
                                CompletionItemFactory.create(
                                    word,
                                    expectedKind,
                                    basePriority + kindPriority(expectedKind)
                                )
                            )
                        }
                        expectedKind = null
                        lexer.advance()
                        continue
                    }

                    else -> {
                        expectedKind = null
                    }
                }
            }

            val kind = if (tokenType != null) kindByTokenType[tokenType] else null
            if (kind != null && (kind != CompletionSymbolKind.IDENTIFIER || includeNonDeclarationIdentifiers)) {
                val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                if (word.length >= 2 &&
                    !suppressedNames.contains(word) &&
                    seen.add(word) &&
                    prefixMatcher.prefixMatches(word)
                ) {
                    val priority =
                        if (kind == CompletionSymbolKind.IDENTIFIER) nonDeclarationIdentifierPriority
                        else basePriority + kindPriority(kind)
                    result.addElement(CompletionItemFactory.create(word, kind, priority))
                }
            }
            lexer.advance()
        }
    }

    private fun kindPriority(kind: CompletionSymbolKind): Double =
        when (kind) {
            CompletionSymbolKind.TYPE -> 80.0
            CompletionSymbolKind.FUNCTION -> 60.0
            CompletionSymbolKind.FIELD -> 40.0
            CompletionSymbolKind.IDENTIFIER -> 0.0
            CompletionSymbolKind.KEYWORD -> 0.0
        }
}
