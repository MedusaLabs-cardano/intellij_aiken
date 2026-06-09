package com.medusalabs.aiken.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.lang.AikenFileType

enum class AikenKeywordVisibility {
    ALL,
    EXPRESSION_ONLY,
    NONE
}

internal data class AikenCompletionScenarioPolicy(
    val keywordVisibility: AikenKeywordVisibility,
    val bareTypesAllowed: Boolean,
    val typeOnlySuggestions: Boolean,
    val lexicalFallbackAllowed: Boolean,
    val typedCompletionStopsFurtherMerging: Boolean
)

internal object AikenCompletionScenarioPolicies {
    fun forFile(file: PsiFile, offset: Int): AikenCompletionScenarioPolicy {
        if (file.fileType != AikenFileType) {
            return fallbackPolicy(file.text, offset)
        }

        val resolution = AikenCompletionScenarioResolver.resolve(file, offset)
        return fromResolution(file.text, offset, resolution)
    }

    fun forElement(
        element: PsiElement,
        offset: Int = element.textRange.startOffset
    ): AikenCompletionScenarioPolicy {
        val file = element.containingFile ?: return fallbackPolicy("", 0)
        return forFile(file, offset)
    }

    fun fromResolution(
        text: String,
        offset: Int,
        resolution: AikenCompletionResolution
    ): AikenCompletionScenarioPolicy =
        forScenario(text, offset, resolution.scenario)

    fun forScenario(
        text: String,
        offset: Int,
        scenario: AikenCompletionScenario
    ): AikenCompletionScenarioPolicy {
        val likelyValueContext = AikenCompletionContexts.isLikelyValueExpressionContext(text, offset)
        val likelyTypeContext = AikenCompletionContexts.isLikelyTypeReferenceContext(text, offset)

        return when (scenario) {
            AikenCompletionScenario.NoSuggestions ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = false,
                    typeOnlySuggestions = false,
                    lexicalFallbackAllowed = false,
                    typedCompletionStopsFurtherMerging = true
                )

            AikenCompletionScenario.UseModule,
            AikenCompletionScenario.UseSymbol ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = false,
                    typeOnlySuggestions = false,
                    lexicalFallbackAllowed = false,
                    typedCompletionStopsFurtherMerging = true
                )

            AikenCompletionScenario.TypeReference ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = true,
                    typeOnlySuggestions = true,
                    lexicalFallbackAllowed = true,
                    typedCompletionStopsFurtherMerging = true
                )

            AikenCompletionScenario.RecordFieldName,
            is AikenCompletionScenario.RecordFieldValue,
            AikenCompletionScenario.RecordSpread ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = false,
                    typeOnlySuggestions = false,
                    lexicalFallbackAllowed = false,
                    typedCompletionStopsFurtherMerging = true
                )

            AikenCompletionScenario.ListItem ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = false,
                    typeOnlySuggestions = false,
                    lexicalFallbackAllowed = true,
                    typedCompletionStopsFurtherMerging = true
                )

            AikenCompletionScenario.PipeTarget ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = false,
                    typeOnlySuggestions = false,
                    lexicalFallbackAllowed = false,
                    typedCompletionStopsFurtherMerging = true
                )

            is AikenCompletionScenario.QualifiedAccess ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility = AikenKeywordVisibility.NONE,
                    bareTypesAllowed = likelyTypeContext || !likelyValueContext,
                    typeOnlySuggestions = likelyTypeContext && !likelyValueContext,
                    lexicalFallbackAllowed = false,
                    typedCompletionStopsFurtherMerging = true
                )

            AikenCompletionScenario.FunctionArgument ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility =
                        if (likelyValueContext) {
                            AikenKeywordVisibility.EXPRESSION_ONLY
                        } else {
                            AikenKeywordVisibility.ALL
                        },
                    bareTypesAllowed = likelyTypeContext || !likelyValueContext,
                    typeOnlySuggestions = likelyTypeContext && !likelyValueContext,
                    lexicalFallbackAllowed = true,
                    typedCompletionStopsFurtherMerging = false
                )

            AikenCompletionScenario.OrdinaryExpression ->
                AikenCompletionScenarioPolicy(
                    keywordVisibility =
                        if (likelyValueContext) {
                            AikenKeywordVisibility.EXPRESSION_ONLY
                        } else {
                            AikenKeywordVisibility.ALL
                        },
                    bareTypesAllowed = likelyTypeContext || !likelyValueContext,
                    typeOnlySuggestions = likelyTypeContext && !likelyValueContext,
                    lexicalFallbackAllowed = true,
                    typedCompletionStopsFurtherMerging = false
                )
        }
    }

    private fun fallbackPolicy(
        text: String,
        offset: Int
    ): AikenCompletionScenarioPolicy {
        val likelyValueContext = AikenCompletionContexts.isLikelyValueExpressionContext(text, offset)
        return AikenCompletionScenarioPolicy(
            keywordVisibility =
                if (likelyValueContext) {
                    AikenKeywordVisibility.EXPRESSION_ONLY
                } else {
                    AikenKeywordVisibility.ALL
                },
            bareTypesAllowed = !likelyValueContext,
            typeOnlySuggestions = !likelyValueContext && AikenCompletionContexts.isLikelyTypeReferenceContext(text, offset),
            lexicalFallbackAllowed = true,
            typedCompletionStopsFurtherMerging = false
        )
    }
}
