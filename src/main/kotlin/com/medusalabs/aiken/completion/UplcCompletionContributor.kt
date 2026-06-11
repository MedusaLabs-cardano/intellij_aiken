package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.index.uplcIdentifierIndexName
import com.medusalabs.aiken.lang.UplcLanguage

class UplcCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(UplcLanguage),
            IdentifierCompletionProvider(
                lexerFactory = { UplcLexing.createLexer() },
                kindByTokenType = mapOf(
                    UplcTokenTypes.IDENTIFIER to CompletionSymbolKind.IDENTIFIER,
                    UplcTokenTypes.TYPE to CompletionSymbolKind.TYPE,
                    UplcTokenTypes.FUNCTION to CompletionSymbolKind.FUNCTION,
                    UplcTokenTypes.FIELD to CompletionSymbolKind.FIELD
                ),
                stopTokenTypes = setOf(UplcTokenTypes.COMMENT, UplcTokenTypes.STRING)
            )
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(UplcLanguage),
            IndexedIdentifierCompletionProvider(
                indexId = uplcIdentifierIndexName,
                stopTokenTypes = setOf(UplcTokenTypes.COMMENT, UplcTokenTypes.STRING)
            )
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(UplcLanguage),
            KeywordCompletionProvider(
                keywords = UplcLexing.keywords + setOf("True", "False"),
                stopTokenTypes = setOf(UplcTokenTypes.COMMENT, UplcTokenTypes.STRING)
            )
        )
    }
}
