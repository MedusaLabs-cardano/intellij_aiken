package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.util.text.matching.KeyboardLayoutUtil
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
                .withInsertHandler { insertionContext, _ ->
                    normalizeWrongLayoutPrefix(insertionContext, text)
                    AikenAutoPopupGuard.cancelPendingRequests()
                }

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

    private fun normalizeWrongLayoutPrefix(
        insertionContext: InsertionContext,
        insertedText: String
    ) {
        val document = insertionContext.document
        val chars = document.charsSequence
        val insertedStart = insertionContext.startOffset.coerceIn(0, chars.length)
        val insertedEnd = insertionContext.tailOffset.coerceIn(insertedStart, chars.length)
        if (insertedEnd <= insertedStart) return

        var prefixStart = insertedStart
        while (prefixStart > 0 && AikenSyntaxText.isIdentifierChar(chars[prefixStart - 1])) {
            prefixStart--
        }
        if (prefixStart == insertedStart) return

        val rawPrefix = chars.subSequence(prefixStart, insertedStart).toString()
        val mappedPrefix = mapByKeyboardLayout(rawPrefix)
        val prefixMatches =
            insertedText.startsWith(rawPrefix, ignoreCase = true) ||
                (mappedPrefix != null && insertedText.startsWith(mappedPrefix, ignoreCase = true))
        if (!prefixMatches) return

        val insertedRangeText = chars.subSequence(insertedStart, insertedEnd).toString()
        if (insertedRangeText != insertedText) return

        document.replaceString(prefixStart, insertedEnd, insertedText)
        insertionContext.commitDocument()
    }

    private fun mapByKeyboardLayout(prefix: String): String? {
        var changed = false
        val builder = StringBuilder(prefix.length)
        for (ch in prefix) {
            val mapped = KeyboardLayoutUtil.getAsciiForChar(ch)
            if (mapped != null) {
                builder.append(mapped)
                if (mapped != ch) changed = true
            } else {
                builder.append(ch)
            }
        }
        return builder.toString().takeIf { changed }
    }
}
