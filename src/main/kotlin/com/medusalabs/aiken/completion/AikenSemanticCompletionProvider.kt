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
        val prefix = completionPrefix(file.text, offset)
        val insideRecordFieldValue = AikenRecordCompletionSupport.isRecordFieldValueContext(file.text, offset)
        val recordSuggestions = AikenRecordCompletionSupport.recordSpecificVariants(anchor, offset)
        val argumentSuggestions =
            if (recordSuggestions == null) {
                AikenArgumentCompletionSupport.argumentSpecificVariants(anchor, offset)
            } else {
                emptyList()
            }

        recordSuggestions?.let { suggestions ->
            val specialResult =
                if (suggestions.mode == RecordCompletionMode.FIELD_VALUE) {
                    result.withPrefixMatcher("")
                } else {
                    result.withPrefixMatcher(prefix)
                }

            for (lookupElement in suggestions.lookups) {
                specialResult.addElement(lookupElement)
            }
        }

        if (argumentSuggestions.isNotEmpty()) {
            val specialResult = result.withPrefixMatcher("")
            for (lookupElement in argumentSuggestions) {
                specialResult.addElement(lookupElement)
            }
        }

        if (insideRecordFieldValue) {
            result.stopHere()
            return
        }
        if (argumentSuggestions.isNotEmpty()) {
            result.stopHere()
            return
        }

        val semanticVariants =
            anchor
                ?.let(::variantsForAnchor)
                .orEmpty()
                .filterNot { lookup ->
                    recordSuggestions?.lookups?.any { it.lookupString == lookup.lookupString } == true ||
                        argumentSuggestions.any { it.lookupString == lookup.lookupString }
                }
        val unimportedSemanticVariants =
            anchor
                ?.let { currentAnchor ->
                    AikenReferenceVariants.unimportedExportsForPrefix(
                        currentAnchor,
                        prefix,
                        excludedNames = semanticVariants.mapTo(LinkedHashSet()) { it.lookupString }
                    )
                }
                .orEmpty()

        if (semanticVariants.isEmpty() && unimportedSemanticVariants.isEmpty()) return

        val semanticResult = result.withPrefixMatcher(prefix)
        for (lookupElement in semanticVariants) {
            semanticResult.addElement(lookupElement)
        }
        for (lookupElement in unimportedSemanticVariants) {
            semanticResult.addElement(lookupElement)
        }
    }

    private fun variantsForAnchor(anchor: PsiElement): List<LookupElement> {
        val referenceVariants =
            anchor.references
                .asSequence()
                .flatMap { reference -> reference.variants.asSequence() }
                .mapNotNull { it as? LookupElement }
                .distinctBy(LookupElement::getLookupString)
                .toList()
        if (referenceVariants.isNotEmpty()) return referenceVariants

        return AikenReferenceVariants.forElement(anchor)
            .mapNotNull { it as? LookupElement }
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
        val candidateOffsets = linkedSetOf((offset - 1).coerceAtLeast(0), offset.coerceAtMost(file.textLength))
        for (candidateOffset in candidateOffsets) {
            val leaf = file.findElementAt(candidateOffset) ?: continue
            return leaf
        }
        return null
    }

    private fun completionPrefix(text: String, offset: Int): String {
        if (text.isEmpty()) return ""

        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) {
            index--
        }
        return text.substring(index + 1, offset.coerceIn(0, text.length))
    }
}
