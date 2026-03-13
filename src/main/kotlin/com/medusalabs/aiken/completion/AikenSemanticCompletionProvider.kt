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

        val anchor = findAnchorElement(file, offset) ?: return
        val semanticResult = result.withPrefixMatcher(completionPrefix(file.text, offset))
        for (lookupElement in variantsForAnchor(anchor)) {
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
