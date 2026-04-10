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

internal object CompletionItemFactory {
    fun create(
        text: String,
        kind: CompletionSymbolKind,
        rankingCategory: AikenOrdinaryCompletionCategory? = defaultRankingCategory(kind)
    ): LookupElement = create(text, kind, typeTextFor(kind), rankingCategory)

    fun create(
        text: String,
        kind: CompletionSymbolKind,
        typeText: String,
        rankingCategory: AikenOrdinaryCompletionCategory? = defaultRankingCategory(kind)
    ): LookupElement = buildLookup(text, kind, priority = null, typeText = typeText, rankingCategory = rankingCategory)

    fun create(
        text: String,
        kind: CompletionSymbolKind,
        priority: Double,
        rankingCategory: AikenOrdinaryCompletionCategory? = defaultRankingCategory(kind)
    ): LookupElement = create(text, kind, priority, typeTextFor(kind), rankingCategory)

    fun create(
        text: String,
        kind: CompletionSymbolKind,
        priority: Double,
        typeText: String,
        rankingCategory: AikenOrdinaryCompletionCategory? = defaultRankingCategory(kind)
    ): LookupElement = buildLookup(text, kind, priority = priority, typeText = typeText, rankingCategory = rankingCategory)

    private fun buildLookup(
        text: String,
        kind: CompletionSymbolKind,
        priority: Double?,
        typeText: String,
        rankingCategory: AikenOrdinaryCompletionCategory? = defaultRankingCategory(kind)
    ): LookupElement {
        val builder =
            LookupElementBuilder
                .create(text)
                .withIcon(iconFor(kind))
                .withTypeText(typeText, true)
                .withBoldness(kind == CompletionSymbolKind.KEYWORD)

        val lookup =
            priority?.let { explicitPriority ->
                PrioritizedLookupElement.withPriority(builder, explicitPriority)
            } ?: builder
        return if (rankingCategory != null) {
            AikenCompletionSorting.annotate(lookup, rankingCategory, kind)
        } else {
            lookup
        }
    }

    private fun defaultRankingCategory(kind: CompletionSymbolKind): AikenOrdinaryCompletionCategory? =
        when (kind) {
            CompletionSymbolKind.KEYWORD -> AikenOrdinaryCompletionCategory.KEYWORD
            else -> null
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
