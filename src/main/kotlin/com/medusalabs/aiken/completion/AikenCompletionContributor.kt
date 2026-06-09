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
        val inAikenFile = PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile().withLanguage(AikenLanguage))

        extend(
            CompletionType.BASIC,
            inAikenFile,
            AikenUseCompletionProvider()
        )

        extend(
            CompletionType.BASIC,
            inAikenFile,
            AikenSemanticCompletionProvider()
        )

        extend(
            CompletionType.BASIC,
            inAikenFile,
            KeywordCompletionProvider(
                keywords = AikenLexing.keywords,
                stopTokenTypes = setOf(AikenTokenTypes.COMMENT, AikenTokenTypes.STRING),
                priority = null,
                visibilityResolver = { parameters ->
                    AikenCompletionScenarioPolicies.forFile(parameters.originalFile, parameters.offset).keywordVisibility
                }
            )
        )
    }
}
