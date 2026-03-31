package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.lang.AikenFileType

class AikenSemanticCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val file = parameters.originalFile
        if (file.fileType != AikenFileType) return

        val offset = parameters.offset.coerceIn(0, file.textLength)
        if (insideUseStatement(file, offset)) return

        val anchor = findAnchorElement(file, offset)
        val prefixOffset =
            anchor
                ?.takeIf { isIdentifierLikeElement(it) }
                ?.textRange
                ?.endOffset
                ?.coerceAtLeast(offset)
                ?: offset
        val prefix = completionPrefix(file.text, prefixOffset)
        val qualifierContext = qualifiedAccessContext(file.text, offset)

        val constructibleInvocationSuggestions =
            AikenConstructibleCompletionSupport.invocationFormVariants(parameters, anchor, offset)
        val insideListLiteralContext = AikenCompletionContexts.insideListLiteralContext(file.text, offset)
        val insideRecordFieldContext = AikenRecordCompletionSupport.isRecordFieldContext(file.text, offset)
        val insideRecordFieldValue = AikenRecordCompletionSupport.isRecordFieldValueContext(file.text, offset)
        val currentRecordValueText =
            if (insideRecordFieldValue) {
                AikenRecordCompletionSupport.currentFieldValueText(file.text, offset).orEmpty()
            } else {
                ""
            }
        val keepOuterRecordTyping = currentRecordValueText.trimStart().startsWith("Some(")
        val insideNestedArgumentContext =
            insideRecordFieldValue &&
                AikenArgumentCompletionSupport.hasArgumentContext(currentRecordValueText) &&
                !keepOuterRecordTyping
        val recordSuggestions =
            if (insideNestedArgumentContext) {
                null
            } else {
                AikenRecordCompletionSupport.recordSpecificVariants(anchor, offset)
            }
        val argumentSuggestions =
            if (insideNestedArgumentContext || (!insideRecordFieldContext && recordSuggestions == null)) {
                AikenArgumentCompletionSupport.argumentSpecificVariants(anchor, offset)
            } else {
                emptyList()
            }
        val inferredListSuggestions =
            if (insideListLiteralContext && !insideRecordFieldContext && !insideRecordFieldValue && anchor != null) {
                AikenTypeDirectedCompletionSupport.listLiteralItemLookups(anchor, file.text, offset)
            } else {
                emptyList()
            }

        if (constructibleInvocationSuggestions.isNotEmpty()) {
            val invocationResult = result.withPrefixMatcher("")
            for (lookupElement in constructibleInvocationSuggestions) {
                invocationResult.addElement(lookupElement)
            }
            result.stopHere()
            return
        }

        if (inferredListSuggestions.isNotEmpty()) {
            val listResult = result.withPrefixMatcher("")
            for (lookupElement in inferredListSuggestions) {
                listResult.addElement(lookupElement)
            }
            result.stopHere()
            return
        }

        if (argumentSuggestions.isNotEmpty()) {
            val specialResult = result.withPrefixMatcher("")
            for (lookupElement in argumentSuggestions) {
                specialResult.addElement(lookupElement)
            }
        }

        recordSuggestions?.let { suggestions ->
            val specialResult =
                if (suggestions.mode == RecordCompletionMode.FIELD_VALUE || suggestions.mode == RecordCompletionMode.SPREAD) {
                    result.withPrefixMatcher("")
                } else {
                    result.withPrefixMatcher(prefix)
                }

            for (lookupElement in suggestions.lookups) {
                specialResult.addElement(lookupElement)
            }
        }

        if (recordSuggestions?.mode == RecordCompletionMode.FIELD_VALUE && recordSuggestions.lookups.isNotEmpty()) {
            result.stopHere()
            return
        }

        if (insideNestedArgumentContext) {
            result.stopHere()
            return
        }
        if (insideRecordFieldContext) {
            result.stopHere()
            return
        }

        val insidePipeContext = AikenArgumentCompletionSupport.hasPipeContext(anchor, offset)
        val pipeSuggestions =
            if (insidePipeContext && !AikenArgumentCompletionSupport.hasArgumentContext(anchor, offset)) {
                AikenArgumentCompletionSupport.pipeSpecificVariants(anchor, offset)
            } else {
                emptyList()
            }
        if (insidePipeContext) {
            val pipeResult = result.withPrefixMatcher(prefix)
            for (lookupElement in pipeSuggestions) {
                pipeResult.addElement(lookupElement)
            }
            result.stopHere()
            return
        }

        if (qualifierContext != null) {
            val semanticVariants =
                anchor
                    ?.let { currentAnchor ->
                        AikenReferenceVariants.qualifiedVariants(
                            element = currentAnchor,
                            qualifier = qualifierContext.qualifier,
                            allowBareTypes = !AikenCompletionContexts.isLikelyValueExpressionContext(file.text, offset)
                        )
                    }
                    .orEmpty()
            val semanticResult = result.withPrefixMatcher(prefix)
            for (lookupElement in semanticVariants) {
                semanticResult.addElement(lookupElement)
            }
            result.stopHere()
            return
        }

        val semanticVariants =
            anchor
                ?.let { variantsForAnchor(it, offset) }
                .orEmpty()
                .filterNot { lookup ->
                    recordSuggestions?.lookups?.any { it.lookupString == lookup.lookupString } == true ||
                        argumentSuggestions.any { it.lookupString == lookup.lookupString }
                }
        val semanticResult = result.withPrefixMatcher(prefix)
        val unimportedResult = result.withPrefixMatcher("")
        val unimportedSemanticVariants =
            anchor
                ?.let { currentAnchor ->
                    AikenReferenceVariants.unimportedExportsMatching(
                        currentAnchor,
                        nameMatches = { AikenCompletionPrefixMatching.matches(it, semanticResult.prefixMatcher, prefix) },
                        excludedNames = semanticVariants.mapTo(LinkedHashSet()) { it.lookupString }
                    )
                }
                .orEmpty()
        val unimportedModuleVariants =
            if (prefix.isBlank()) {
                emptyList()
            } else {
                anchor
                    ?.let { currentAnchor ->
                        AikenReferenceVariants.unimportedModulesMatching(currentAnchor) {
                            AikenCompletionPrefixMatching.matches(it, semanticResult.prefixMatcher, prefix)
                        }
                    }
                    .orEmpty()
            }

        if (semanticVariants.isEmpty() && unimportedSemanticVariants.isEmpty() && unimportedModuleVariants.isEmpty()) {
            if (insideListLiteralContext) {
                result.stopHere()
            }
            return
        }

        for (lookupElement in semanticVariants) {
            semanticResult.addElement(lookupElement)
        }
        for (lookupElement in unimportedSemanticVariants) {
            unimportedResult.addElement(lookupElement)
        }
        for (lookupElement in unimportedModuleVariants) {
            unimportedResult.addElement(lookupElement)
        }

        if (insideListLiteralContext) {
            result.stopHere()
        }
    }

    private fun variantsForAnchor(
        anchor: PsiElement,
        offset: Int
    ): List<LookupElement> {
        val referenceVariants =
            anchor.references
                .asSequence()
                .flatMap { reference -> reference.variants.asSequence() }
                .mapNotNull { it as? LookupElement }
                .distinctBy(LookupElement::getLookupString)
                .toList()
        val fallbackVariants =
            AikenReferenceVariants.forElement(anchor, offset)
                .mapNotNull { it as? LookupElement }
        return (referenceVariants + fallbackVariants)
            .distinctBy(LookupElement::getLookupString)
    }

    private fun insideUseStatement(file: PsiFile, offset: Int): Boolean {
        val caretOffset = offset.coerceAtLeast(0)
        val lookupOffset = (caretOffset - 1).coerceAtLeast(0)
        val model = AikenUseStatementParser.parseModel(file.text)
        return model.statements.any { statement ->
            caretOffset in statement.statementRange.startOffset..statement.statementRange.endOffset ||
                lookupOffset in statement.statementRange.startOffset..statement.statementRange.endOffset
        }
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
        if (text.isEmpty()) return ""

        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) {
            index--
        }
        return text.substring(index + 1, offset.coerceIn(0, text.length))
    }

    private fun qualifiedAccessContext(text: String, offset: Int): QualifiedAccessContext? {
        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) {
            index--
        }
        while (index >= 0 && text[index].isWhitespace()) {
            index--
        }
        if (index < 0 || text[index] != '.') return null
        index--
        while (index >= 0 && text[index].isWhitespace()) {
            index--
        }
        val end = index + 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) {
            index--
        }
        val start = index + 1
        if (start >= end) return null
        return QualifiedAccessContext(text.substring(start, end))
    }

    private data class QualifiedAccessContext(
        val qualifier: String
    )

}
