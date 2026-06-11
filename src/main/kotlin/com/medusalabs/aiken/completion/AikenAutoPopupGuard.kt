package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal object AikenAutoPopupGuard {
    private val suppressedExactPrefixKey = Key.create<String>("aiken.autopopup.suppressed.exact.prefix")

    fun cancelPendingRequests() = Unit

    fun suppressActiveLookupOnExactMatch(
        project: Project,
        editor: Editor
    ): Boolean {
        val activeLookup = LookupManager.getActiveLookup(editor) ?: return false
        val prefix = currentIdentifierPrefix(editor)
        if (prefix.isBlank()) return false

        val hasExactMatch =
            activeLookup.items.any { item ->
                item.allLookupStrings.any { it == prefix }
            }
        if (!hasExactMatch) return false

        cancelPendingRequests()
        hideActiveLookupNow(project, editor)
        return true
    }

    fun suppressSemanticAutoPopupOnExactMatch(
        project: Project,
        editor: Editor,
        file: PsiFile
    ): Boolean {
        val resolution = AikenCompletionScenarioResolver.resolve(file, editor.caretModel.offset)
        val prefix = AikenSyntaxText.identifierPrefix(file.text, editor.caretModel.offset)
        if (prefix.isBlank()) return false

        val anchor = resolution.anchor ?: fallbackAnchor(file, editor.caretModel.offset) ?: return false
        val matches =
            when (val scenario = resolution.scenario) {
                AikenCompletionScenario.UseModule,
                AikenCompletionScenario.UseSymbol -> false
                is AikenCompletionScenario.QualifiedAccess ->
                    AikenReferenceVariants.qualifiedVariants(
                        element = anchor,
                        qualifier = scenario.qualifier,
                        allowBareTypes = resolution.policy.bareTypesAllowed,
                        offsetExclusive = editor.caretModel.offset
                    ).hasExactMatch(prefix)
                AikenCompletionScenario.NoSuggestions,
                AikenCompletionScenario.TypeReference,
                AikenCompletionScenario.RecordFieldName,
                is AikenCompletionScenario.RecordFieldValue,
                AikenCompletionScenario.RecordSpread,
                AikenCompletionScenario.ListItem,
                AikenCompletionScenario.PipeTarget,
                AikenCompletionScenario.FunctionArgument,
                AikenCompletionScenario.OrdinaryExpression -> {
                    val ordinaryMatches =
                        AikenReferenceVariants.forElement(
                            element = anchor,
                            caretOffsetOverride = editor.caretModel.offset,
                            allowBareTypesOverride = resolution.policy.bareTypesAllowed
                        )
                            .filterIsInstance<com.intellij.codeInsight.lookup.LookupElement>()
                            .hasExactMatch(prefix)
                    ordinaryMatches ||
                        AikenReferenceVariants.unimportedExportsMatching(
                            element = anchor,
                            nameMatches = { it == prefix },
                            allowBareTypes = resolution.policy.bareTypesAllowed
                        ).hasExactMatch(prefix) ||
                        AikenReferenceVariants.unimportedModulesMatching(anchor) { it == prefix }.hasExactMatch(prefix)
                }
            }

        if (!matches) return false

        editor.putUserData(suppressedExactPrefixKey, prefix)
        cancelPendingRequests()
        hideActiveLookupNow(project, editor)
        return true
    }

    fun suppressBlankOrdinaryExpressionAutoPopup(
        editor: Editor,
        file: PsiFile
    ): Boolean {
        val offset = editor.caretModel.offset
        val prefix = AikenSyntaxText.identifierPrefix(file.text, offset)
        if (prefix.isNotBlank()) return false

        val resolution = AikenCompletionScenarioResolver.resolve(file, offset)
        if (resolution.scenario != AikenCompletionScenario.OrdinaryExpression) return false

        val anchor = resolution.anchor ?: fallbackAnchor(file, offset) ?: return false
        if (AikenTypeDirectedCompletionSupport.hasBindingInitializerExpectedTypeContext(anchor, file.text, offset)) {
            return false
        }

        return true
    }

    fun suppressBlankUnconstrainedArgumentAutoPopup(
        editor: Editor,
        file: PsiFile
    ): Boolean {
        val offset = editor.caretModel.offset
        val prefix = AikenSyntaxText.identifierPrefix(file.text, offset)
        if (prefix.isNotBlank()) return false

        val resolution = AikenCompletionScenarioResolver.resolve(file, offset)
        if (resolution.scenario != AikenCompletionScenario.FunctionArgument) return false

        val anchor = resolution.anchor ?: fallbackAnchor(file, offset) ?: return false
        return AikenArgumentCompletionSupport.hasBlankArgumentWithUnconstrainedExpectedType(anchor, offset)
    }

    fun consumeSuppressedExactPrefix(
        editor: Editor,
        prefix: String
    ): Boolean {
        val suppressedPrefix = editor.getUserData(suppressedExactPrefixKey) ?: return false
        if (suppressedPrefix != prefix) return false
        editor.putUserData(suppressedExactPrefixKey, null)
        return true
    }

    private fun currentIdentifierPrefix(editor: Editor): String {
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence.toString()
        return AikenSyntaxText.identifierPrefix(text, offset)
    }

    private fun fallbackAnchor(
        file: PsiFile,
        offset: Int
    ): PsiElement? {
        val safeOffset = offset.coerceIn(0, file.textLength)
        val candidateOffsets =
            linkedSetOf(
                (safeOffset - 1).coerceAtLeast(0),
                safeOffset.coerceAtMost(file.textLength),
                (safeOffset + 1).coerceAtMost(file.textLength)
            )
        return candidateOffsets.mapNotNull(file::findElementAt).firstOrNull()
    }

    private fun List<com.intellij.codeInsight.lookup.LookupElement>.hasExactMatch(prefix: String): Boolean =
        any { lookup -> lookup.allLookupStrings.any { candidate -> candidate == prefix } }

    private fun hideActiveLookupNow(
        project: Project,
        editor: Editor
    ) {
        val application = ApplicationManager.getApplication()
        val hide = {
            if (!editor.isDisposed) {
                LookupManager.getActiveLookup(editor)?.let { LookupManager.hideActiveLookup(project) }
            }
        }
        if (application.isDispatchThread) {
            hide()
        } else {
            application.invokeAndWait(hide)
        }
    }
}
