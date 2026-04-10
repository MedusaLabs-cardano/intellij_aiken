package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenLanguage

class AikenCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AikenLanguage),
            AikenUseCompletionProvider()
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AikenLanguage),
            AikenSemanticCompletionProvider()
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AikenLanguage),
            KeywordCompletionProvider(
                keywords = AikenLexing.keywords + setOf("True", "False"),
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING),
                priority = null,
                visibilityResolver = { parameters ->
                    AikenCompletionScenarioPolicies.forFile(parameters.originalFile, parameters.offset).keywordVisibility
                }
            )
        )
    }
}
