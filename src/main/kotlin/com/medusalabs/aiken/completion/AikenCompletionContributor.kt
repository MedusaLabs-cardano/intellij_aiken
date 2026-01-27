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
            KeywordCompletionProvider(
                keywords = AikenLexing.keywords + setOf("True", "False"),
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING)
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
            IdentifierCompletionProvider(
                lexerFactory = { AikenLexing.createLexer() },
                identifierTokenTypes = setOf(
                    AikenTokenTypes.IDENTIFIER,
                    AikenTokenTypes.TYPE,
                    AikenTokenTypes.FUNCTION,
                    AikenTokenTypes.FIELD
                ),
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING)
            )
        )
    }
}
