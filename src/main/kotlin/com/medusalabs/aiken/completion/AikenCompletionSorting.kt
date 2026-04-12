package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.codeInsight.lookup.WeighingContext
import com.intellij.openapi.util.Key

internal enum class AikenOrdinaryCompletionCategory {
    OTHER,
    UNIMPORTED_MODULE,
    UNIMPORTED_SYMBOL,
    KEYWORD,
    SAME_FILE,
    IMPORTED_MODULE,
    IMPORTED_SYMBOL,
    LOCAL
}

internal enum class AikenTypedCompletionCategory {
    OTHER,
    EXTRA,
    LOCAL_BINDING,
    LOCAL_CONST,
    IMPORTED_CONST,
    LOCAL_FUNCTION,
    IMPORTED_FUNCTION,
    QUALIFIED_FUNCTION,
    UNIMPORTED_CONST,
    UNIMPORTED_FUNCTION,
    LIST_LITERAL,
    OPTION_SOME,
    BUILTIN_INVARIANT,
    CONSTRUCTIBLE
}

private data class AikenTypedCompletionRanking(
    val category: AikenTypedCompletionCategory,
    val scopeDistance: Int,
    val matchDistance: Int
) : Comparable<AikenTypedCompletionRanking> {
    private val categoryWeight: Int
        get() =
            when (category) {
                AikenTypedCompletionCategory.EXTRA -> 0
                AikenTypedCompletionCategory.LOCAL_BINDING -> 100
                AikenTypedCompletionCategory.LOCAL_CONST -> 200
                AikenTypedCompletionCategory.LOCAL_FUNCTION -> 300
                AikenTypedCompletionCategory.IMPORTED_CONST -> 400
                AikenTypedCompletionCategory.IMPORTED_FUNCTION -> 500
                AikenTypedCompletionCategory.QUALIFIED_FUNCTION -> 550
                AikenTypedCompletionCategory.UNIMPORTED_CONST -> 600
                AikenTypedCompletionCategory.UNIMPORTED_FUNCTION -> 700
                AikenTypedCompletionCategory.LIST_LITERAL -> 900
                AikenTypedCompletionCategory.OPTION_SOME -> 1000
                AikenTypedCompletionCategory.BUILTIN_INVARIANT -> 1100
                AikenTypedCompletionCategory.CONSTRUCTIBLE -> 1200
                AikenTypedCompletionCategory.OTHER -> 10_000
            }

    override fun compareTo(other: AikenTypedCompletionRanking): Int =
        compareValuesBy(
            this,
            other,
            AikenTypedCompletionRanking::categoryWeight,
            AikenTypedCompletionRanking::scopeDistance,
            AikenTypedCompletionRanking::matchDistance
        )
}

internal enum class AikenConstructibleFormCompletionCategory {
    NAMED,
    POSITIONAL,
    OTHER
}

internal enum class AikenUseCompletionCategory {
    PUB_ENTITY,
    REVERSE_EXACT,
    REVERSE_FUZZY,
    MODULE,
    OTHER
}

private data class AikenOrdinaryCompletionRanking(
    val category: AikenOrdinaryCompletionCategory,
    val kind: CompletionSymbolKind
) : Comparable<AikenOrdinaryCompletionRanking> {
    private val categoryWeight: Int
        get() =
            when (category) {
                AikenOrdinaryCompletionCategory.LOCAL -> 0
                AikenOrdinaryCompletionCategory.IMPORTED_SYMBOL -> 100
                AikenOrdinaryCompletionCategory.IMPORTED_MODULE -> 150
                AikenOrdinaryCompletionCategory.SAME_FILE -> 200
                AikenOrdinaryCompletionCategory.KEYWORD -> 300
                AikenOrdinaryCompletionCategory.UNIMPORTED_SYMBOL -> 400
                AikenOrdinaryCompletionCategory.UNIMPORTED_MODULE -> 500
                AikenOrdinaryCompletionCategory.OTHER -> 1000
            }

    private val kindWeight: Int
        get() =
            when (kind) {
                CompletionSymbolKind.IDENTIFIER -> 0
                CompletionSymbolKind.FUNCTION -> 10
                CompletionSymbolKind.TYPE -> 20
                CompletionSymbolKind.FIELD -> 30
                CompletionSymbolKind.KEYWORD -> 40
            }

    override fun compareTo(other: AikenOrdinaryCompletionRanking): Int =
        compareValuesBy(this, other, AikenOrdinaryCompletionRanking::categoryWeight, AikenOrdinaryCompletionRanking::kindWeight)
}

internal object AikenCompletionSorting {
    private val rankingKey = Key.create<AikenOrdinaryCompletionRanking>("aiken.completion.ordinary.ranking")
    private val typedRankingKey = Key.create<AikenTypedCompletionRanking>("aiken.completion.typed.ranking")
    private val constructibleFormRankingKey =
        Key.create<AikenConstructibleFormCompletionCategory>("aiken.completion.constructible.form.ranking")
    private val useRankingKey = Key.create<AikenUseCompletionCategory>("aiken.completion.use.ranking")
    private val fallbackRanking =
        AikenOrdinaryCompletionRanking(
            category = AikenOrdinaryCompletionCategory.OTHER,
            kind = CompletionSymbolKind.IDENTIFIER
        )
    private val fallbackTypedRanking =
        AikenTypedCompletionRanking(
            category = AikenTypedCompletionCategory.OTHER,
            scopeDistance = Int.MAX_VALUE,
            matchDistance = Int.MAX_VALUE
        )
    private val fallbackConstructibleFormRanking = AikenConstructibleFormCompletionCategory.OTHER
    private val fallbackUseRanking = AikenUseCompletionCategory.OTHER

    fun annotate(
        lookup: LookupElement,
        category: AikenOrdinaryCompletionCategory,
        kind: CompletionSymbolKind
    ): LookupElement {
        lookup.putUserData(rankingKey, AikenOrdinaryCompletionRanking(category, kind))
        return lookup
    }

    fun ordinaryKind(lookup: LookupElement): CompletionSymbolKind =
        lookup.getUserData(rankingKey)?.kind ?: inferKindFromPresentation(lookup) ?: fallbackRanking.kind

    fun withOrdinarySorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionResultSet =
        result.withRelevanceSorter(ordinarySorter(parameters, result))

    fun annotateTyped(
        lookup: LookupElement,
        category: AikenTypedCompletionCategory,
        matchDistance: Int = 0,
        scopeDistance: Int = Int.MAX_VALUE
    ): LookupElement {
        lookup.putUserData(typedRankingKey, AikenTypedCompletionRanking(category, scopeDistance, matchDistance))
        return lookup
    }

    fun annotateTyped(
        lookup: LookupElement,
        category: AikenTypedCompletionCategory,
        matchDistance: Int
    ): LookupElement =
        annotateTyped(
            lookup = lookup,
            category = category,
            matchDistance = matchDistance,
            scopeDistance = Int.MAX_VALUE
        )

    fun withTypedSorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionResultSet =
        result.withRelevanceSorter(typedSorter(parameters, result))

    fun annotateConstructibleForm(
        lookup: LookupElement,
        category: AikenConstructibleFormCompletionCategory
    ): LookupElement {
        lookup.putUserData(constructibleFormRankingKey, category)
        return lookup
    }

    fun withConstructibleFormSorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionResultSet =
        result.withRelevanceSorter(constructibleFormSorter(parameters, result))

    fun annotateUse(
        lookup: LookupElement,
        category: AikenUseCompletionCategory
    ): LookupElement {
        lookup.putUserData(useRankingKey, category)
        return lookup
    }

    fun withUseSorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionResultSet =
        result.withRelevanceSorter(useSorter(parameters, result))

    private fun ordinarySorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionSorter {
        val defaultSorter = CompletionSorter.defaultSorter(parameters, result.prefixMatcher)
        return try {
            defaultSorter.weighBefore("priority", OrdinaryLookupWeigher)
        } catch (_: IllegalArgumentException) {
            defaultSorter.weigh(OrdinaryLookupWeigher)
        }
    }

    private fun inferKindFromPresentation(lookup: LookupElement): CompletionSymbolKind? {
        val presentation = LookupElementPresentation()
        lookup.renderElement(presentation)
        return when (presentation.typeText?.trim()) {
            "type" -> CompletionSymbolKind.TYPE
            "fn" -> CompletionSymbolKind.FUNCTION
            "field" -> CompletionSymbolKind.FIELD
            "var" -> CompletionSymbolKind.IDENTIFIER
            "keyword" -> CompletionSymbolKind.KEYWORD
            else -> null
        }
    }

    private fun typedSorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionSorter {
        val defaultSorter = CompletionSorter.defaultSorter(parameters, result.prefixMatcher)
        return try {
            defaultSorter.weighBefore("priority", TypedLookupWeigher)
        } catch (_: IllegalArgumentException) {
            defaultSorter.weigh(TypedLookupWeigher)
        }
    }

    private fun constructibleFormSorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionSorter {
        val defaultSorter = CompletionSorter.defaultSorter(parameters, result.prefixMatcher)
        return try {
            defaultSorter.weighBefore("priority", ConstructibleFormLookupWeigher)
        } catch (_: IllegalArgumentException) {
            defaultSorter.weigh(ConstructibleFormLookupWeigher)
        }
    }

    private fun useSorter(
        parameters: CompletionParameters,
        result: CompletionResultSet
    ): CompletionSorter {
        val defaultSorter = CompletionSorter.defaultSorter(parameters, result.prefixMatcher)
        return try {
            defaultSorter.weighBefore("priority", UseLookupWeigher)
        } catch (_: IllegalArgumentException) {
            defaultSorter.weigh(UseLookupWeigher)
        }
    }

    private object OrdinaryLookupWeigher : LookupElementWeigher("aiken.ordinary", false, false) {
        override fun weigh(
            element: LookupElement,
            context: WeighingContext
        ): Comparable<*> = element.getUserData(rankingKey) ?: fallbackRanking
    }

    private object TypedLookupWeigher : LookupElementWeigher("aiken.typed", false, false) {
        override fun weigh(
            element: LookupElement,
            context: WeighingContext
        ): Comparable<*> = element.getUserData(typedRankingKey) ?: fallbackTypedRanking
    }

    private object ConstructibleFormLookupWeigher : LookupElementWeigher("aiken.constructible.form", false, false) {
        override fun weigh(
            element: LookupElement,
            context: WeighingContext
        ): Comparable<*> = element.getUserData(constructibleFormRankingKey) ?: fallbackConstructibleFormRanking
    }

    private object UseLookupWeigher : LookupElementWeigher("aiken.use", false, false) {
        override fun weigh(
            element: LookupElement,
            context: WeighingContext
        ): Comparable<*> = element.getUserData(useRankingKey) ?: fallbackUseRanking
    }
}
