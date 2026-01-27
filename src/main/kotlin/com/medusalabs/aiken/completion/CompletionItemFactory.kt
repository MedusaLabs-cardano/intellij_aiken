package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import javax.swing.Icon

enum class CompletionSymbolKind {
    KEYWORD,
    TYPE,
    FUNCTION,
    FIELD,
    IDENTIFIER
}

object CompletionItemFactory {
    fun create(
        text: String,
        kind: CompletionSymbolKind,
        priority: Double
    ): LookupElement {
        val builder =
            LookupElementBuilder
                .create(text)
                .withIcon(iconFor(kind))
                .withTypeText(typeTextFor(kind), true)
                .withBoldness(kind == CompletionSymbolKind.KEYWORD)

        return PrioritizedLookupElement.withPriority(builder, priority)
    }

    private fun typeTextFor(kind: CompletionSymbolKind): String =
        when (kind) {
            CompletionSymbolKind.KEYWORD -> "keyword"
            CompletionSymbolKind.TYPE -> "type"
            CompletionSymbolKind.FUNCTION -> "fn"
            CompletionSymbolKind.FIELD -> "field"
            CompletionSymbolKind.IDENTIFIER -> "var"
        }

    private fun iconFor(kind: CompletionSymbolKind): Icon =
        when (kind) {
            CompletionSymbolKind.KEYWORD -> AllIcons.Nodes.Static
            CompletionSymbolKind.TYPE -> AllIcons.Nodes.Class
            CompletionSymbolKind.FUNCTION -> AllIcons.Nodes.Method
            CompletionSymbolKind.FIELD -> AllIcons.Nodes.Field
            CompletionSymbolKind.IDENTIFIER -> AllIcons.Nodes.Variable
        }
}
