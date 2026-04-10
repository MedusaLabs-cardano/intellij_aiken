package com.medusalabs.aiken.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal sealed interface AikenCompletionScenario {
    data object UseModule : AikenCompletionScenario
    data object UseSymbol : AikenCompletionScenario
    data object RecordFieldName : AikenCompletionScenario
    data class RecordFieldValue(
        val currentValueText: String
    ) : AikenCompletionScenario
    data object RecordSpread : AikenCompletionScenario
    data object ListItem : AikenCompletionScenario
    data object PipeTarget : AikenCompletionScenario
    data class QualifiedAccess(
        val qualifier: String
    ) : AikenCompletionScenario
    data object FunctionArgument : AikenCompletionScenario
    data object OrdinaryExpression : AikenCompletionScenario
}

internal data class AikenCompletionResolution(
    val scenario: AikenCompletionScenario,
    val anchor: PsiElement?,
    val prefix: String,
    val insideListLiteral: Boolean,
    val hasArgumentContext: Boolean,
    val qualifiedAccessQualifier: String?,
    val stopAfterArgumentSuggestions: Boolean,
    val policy: AikenCompletionScenarioPolicy
)

internal object AikenCompletionScenarioResolver {
    private val unresolvedPolicyPlaceholder =
        AikenCompletionScenarioPolicy(
            keywordVisibility = AikenKeywordVisibility.NONE,
            bareTypesAllowed = false,
            lexicalFallbackAllowed = false,
            typedCompletionStopsFurtherMerging = false
        )

    fun resolve(file: PsiFile, offset: Int): AikenCompletionResolution {
        val safeOffset = offset.coerceIn(0, file.textLength)
        val text = file.text
        val anchor = findAnchorElement(file, safeOffset)
        val prefixOffset =
            anchor
                ?.takeIf(::isIdentifierLikeElement)
                ?.textRange
                ?.endOffset
                ?.coerceAtLeast(safeOffset)
                ?: safeOffset
        val prefix = completionPrefix(text, prefixOffset)
        val useContext = AikenUseCompletionContext.detect(text, safeOffset)
        if (useContext != null) {
            val useScenario =
                when (useContext.mode) {
                    AikenUseCompletionMode.MODULE -> AikenCompletionScenario.UseModule
                    AikenUseCompletionMode.ENTITY -> AikenCompletionScenario.UseSymbol
                }
            return AikenCompletionResolution(
                    scenario = useScenario,
                    anchor = anchor,
                    prefix = prefix,
                    insideListLiteral = false,
                    hasArgumentContext = false,
                    qualifiedAccessQualifier = null,
                    stopAfterArgumentSuggestions = false,
                    policy = AikenCompletionScenarioPolicies.forScenario(text, safeOffset, useScenario)
                )
        }

        val insideListLiteral = AikenCompletionContexts.insideListLiteralContext(text, safeOffset)
        val isRecordFieldContext = AikenRecordCompletionSupport.isRecordFieldContext(text, safeOffset)
        val isRecordFieldName = AikenRecordCompletionSupport.isRecordFieldNameContext(text, safeOffset)
        val isRecordFieldValue = AikenRecordCompletionSupport.isRecordFieldValueContext(text, safeOffset)
        val currentRecordValueText =
            if (isRecordFieldValue) {
                AikenRecordCompletionSupport.currentFieldValueText(text, safeOffset).orEmpty()
            } else {
                ""
            }
        val keepOuterRecordTyping = currentRecordValueText.trimStart().startsWith("Some(")
        val nestedRecordArgumentContext =
            isRecordFieldValue &&
                AikenArgumentCompletionSupport.hasArgumentContext(currentRecordValueText) &&
                !keepOuterRecordTyping
        val hasArgumentContext = AikenArgumentCompletionSupport.hasArgumentContext(anchor, safeOffset)
        val insidePipeContext = AikenArgumentCompletionSupport.hasPipeContext(anchor, safeOffset)
        val qualifiedAccessQualifier = qualifiedAccessContext(text, safeOffset)?.qualifier

        val scenario =
            when {
                nestedRecordArgumentContext -> AikenCompletionScenario.FunctionArgument
                isRecordFieldValue -> AikenCompletionScenario.RecordFieldValue(currentRecordValueText)
                isRecordFieldName -> AikenCompletionScenario.RecordFieldName
                isRecordFieldContext -> AikenCompletionScenario.RecordSpread
                insideListLiteral -> AikenCompletionScenario.ListItem
                insidePipeContext && !hasArgumentContext -> AikenCompletionScenario.PipeTarget
                qualifiedAccessQualifier != null -> AikenCompletionScenario.QualifiedAccess(qualifiedAccessQualifier)
                hasArgumentContext -> AikenCompletionScenario.FunctionArgument
                else -> AikenCompletionScenario.OrdinaryExpression
            }

        return AikenCompletionResolution(
            scenario = scenario,
            anchor = anchor,
            prefix = prefix,
            insideListLiteral = insideListLiteral,
            hasArgumentContext = hasArgumentContext,
            qualifiedAccessQualifier = qualifiedAccessQualifier,
            stopAfterArgumentSuggestions = nestedRecordArgumentContext,
            policy = unresolvedPolicyPlaceholder
        ).copy(
            policy = AikenCompletionScenarioPolicies.forScenario(text, safeOffset, scenario)
        )
    }

    private fun findAnchorElement(file: PsiFile, offset: Int): PsiElement? {
        val candidateOffsets =
            linkedSetOf(
                (offset - 1).coerceAtLeast(0),
                offset.coerceAtMost(file.textLength),
                (offset + 1).coerceAtMost(file.textLength)
            )
        val leaves = candidateOffsets.mapNotNull(file::findElementAt)
        return leaves.firstOrNull(::isIdentifierLikeElement)
            ?: leaves.firstOrNull { it.text.isNotBlank() }
            ?: leaves.firstOrNull()
    }

    private fun isIdentifierLikeElement(element: PsiElement): Boolean =
        element.text.isNotEmpty() && element.text.all { it.isLetterOrDigit() || it == '_' }

    private fun completionPrefix(text: String, offset: Int): String {
        return AikenSyntaxText.identifierPrefix(text, offset)
    }

    private fun qualifiedAccessContext(text: String, offset: Int): QualifiedAccessContext? {
        return AikenSyntaxText.qualifierBeforeOffset(text, offset)?.let(::QualifiedAccessContext)
    }

    private data class QualifiedAccessContext(
        val qualifier: String
    )
}
