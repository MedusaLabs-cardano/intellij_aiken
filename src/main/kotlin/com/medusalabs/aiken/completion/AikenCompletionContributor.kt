package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.index.AikenIdentifierIndex
import com.medusalabs.aiken.lang.AikenLanguage

class AikenCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AikenLanguage),
            IdentifierCompletionProvider(
                lexerFactory = { AikenLexing.createLexer() },
                kindByTokenType = mapOf(
                    AikenTokenTypes.IDENTIFIER to CompletionSymbolKind.IDENTIFIER,
                    AikenTokenTypes.TYPE to CompletionSymbolKind.TYPE,
                    AikenTokenTypes.FUNCTION to CompletionSymbolKind.FUNCTION,
                    AikenTokenTypes.FIELD to CompletionSymbolKind.FIELD
                ),
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING),
                keywordTokenType = AikenTokenTypes.KEYWORD,
                declarationKindByKeyword = mapOf(
                    "fn" to CompletionSymbolKind.FUNCTION,
                    "test" to CompletionSymbolKind.FUNCTION,
                    "bench" to CompletionSymbolKind.FUNCTION,
                    "validator" to CompletionSymbolKind.FUNCTION,
                    "type" to CompletionSymbolKind.TYPE
                ),
                bindingKeywords = setOf("let", "const", "expect"),
                includeNonDeclarationIdentifiers = false
            )
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AikenLanguage),
            IndexedIdentifierCompletionProvider(
                indexId = AikenIdentifierIndex.NAME,
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING)
            )
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AikenLanguage),
            KeywordCompletionProvider(
                keywords = AikenLexing.keywords + setOf("True", "False"),
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING)
            )
        )
    }
}
