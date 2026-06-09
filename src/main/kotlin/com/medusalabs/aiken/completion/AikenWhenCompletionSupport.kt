package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons

internal object AikenWhenCompletionSupport {
    fun subjectTailLookup(text: String, offset: Int): LookupElement? {
        if (!isAfterWhenSubjectBeforeIs(text, offset)) return null

        return LookupElementBuilder
            .create("is")
            .withIcon(AllIcons.Nodes.Static)
            .withTailText(" { ... }", true)
            .withTypeText("when", true)
            .withInsertHandler { insertionContext, _ ->
                AikenAutoPopupGuard.cancelPendingRequests(insertionContext.project)

                val document = insertionContext.document
                val tailOffset = insertionContext.tailOffset
                val nextNonWhitespace =
                    document.charsSequence
                        .drop(tailOffset)
                        .indexOfFirst { !it.isWhitespace() }
                        .takeIf { it >= 0 }
                        ?.let { tailOffset + it }

                if (nextNonWhitespace != null && document.charsSequence.getOrNull(nextNonWhitespace) == '{') {
                    insertionContext.commitDocument()
                    return@withInsertHandler
                }

                val lineNumber = document.getLineNumber(tailOffset.coerceAtMost(document.textLength))
                val lineStart = document.getLineStartOffset(lineNumber)
                val indent =
                    document.charsSequence
                        .subSequence(lineStart, document.getLineEndOffset(lineNumber))
                        .takeWhile { it == ' ' || it == '\t' }
                        .toString()
                val innerIndent = "$indent  "
                val suffix = " {\n$innerIndent\n$indent}"

                document.insertString(tailOffset, suffix)
                insertionContext.editor.caretModel.moveToOffset(tailOffset + " {\n$innerIndent".length)
                insertionContext.commitDocument()
            }
    }

    private fun isAfterWhenSubjectBeforeIs(text: String, offset: Int): Boolean {
        if (text.isEmpty()) return false
        val safeOffset = offset.coerceIn(0, text.length)
        var index = safeOffset
        while (index > 0 && AikenSyntaxText.isIdentifierChar(text[index - 1])) index--

        val prefixBeforeIdentifier = text.substring(0, index)
        val lineStart =
            maxOf(
                prefixBeforeIdentifier.lastIndexOf('\n'),
                prefixBeforeIdentifier.lastIndexOf('\r'),
                prefixBeforeIdentifier.lastIndexOf('{'),
                prefixBeforeIdentifier.lastIndexOf('}')
            ) + 1
        val segment = prefixBeforeIdentifier.substring(lineStart)
        val whenMatch = Regex("""\bwhen\b""").findAll(segment).lastOrNull() ?: return false
        val afterWhen = segment.substring(whenMatch.range.last + 1)
        if (Regex("""\bis\b""").containsMatchIn(afterWhen)) return false

        val subjectText = afterWhen.trim()
        if (subjectText.isEmpty()) return false
        if (subjectText.endsWith("->")) return false
        return true
    }
}
