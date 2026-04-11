package com.medusalabs.aiken.completion

import com.medusalabs.aiken.index.AikenConstructibleFieldEntry
import java.util.Collections
import java.util.WeakHashMap

internal enum class AikenTypedCandidateOrigin {
    EXTRA,
    LOCAL,
    IMPORTED,
    UNIMPORTED,
    QUALIFIED,
    BUILTIN
}

internal enum class AikenTypedCandidateAutoImportMode {
    NONE,
    SYMBOL
}

internal enum class AikenTypedCandidateSource {
    EXTRA,
    RECORD_SIBLING_FIELD,
    BINDING,
    CONST,
    FUNCTION,
    PIPE_FUNCTION,
    LIST_LITERAL,
    OPTION_SOME,
    BUILTIN_INVARIANT,
    CONSTRUCTIBLE
}

internal sealed interface AikenTypedExpectedTypeCandidate {
    val dedupeKey: String
    val origin: AikenTypedCandidateOrigin
    val modulePath: String?
    val autoImportMode: AikenTypedCandidateAutoImportMode
    val source: AikenTypedCandidateSource
    val matchDistance: Int

    data class Identifier(
        val name: String,
        val type: String,
        val kind: CompletionSymbolKind,
        override val origin: AikenTypedCandidateOrigin,
        override val source: AikenTypedCandidateSource,
        override val modulePath: String? = null,
        override val autoImportMode: AikenTypedCandidateAutoImportMode = AikenTypedCandidateAutoImportMode.NONE,
        override val matchDistance: Int = 0
    ) : AikenTypedExpectedTypeCandidate {
        override val dedupeKey: String =
            listOf("identifier", source.name, origin.name, modulePath.orEmpty(), kind.name, name).joinToString(":")
    }

    data class Function(
        val name: String,
        val signature: String,
        override val origin: AikenTypedCandidateOrigin,
        override val source: AikenTypedCandidateSource = AikenTypedCandidateSource.FUNCTION,
        override val modulePath: String? = null,
        override val autoImportMode: AikenTypedCandidateAutoImportMode = AikenTypedCandidateAutoImportMode.NONE,
        override val matchDistance: Int = 0
    ) : AikenTypedExpectedTypeCandidate {
        override val dedupeKey: String =
            listOf("function", source.name, origin.name, modulePath.orEmpty(), name).joinToString(":")
    }

    data class PipeFunction(
        val lookupText: String,
        val matchName: String,
        val signature: String,
        override val origin: AikenTypedCandidateOrigin,
        override val source: AikenTypedCandidateSource = AikenTypedCandidateSource.PIPE_FUNCTION,
        override val modulePath: String? = null,
        override val autoImportMode: AikenTypedCandidateAutoImportMode = AikenTypedCandidateAutoImportMode.NONE,
        override val matchDistance: Int = 0
    ) : AikenTypedExpectedTypeCandidate {
        override val dedupeKey: String =
            listOf("pipe", source.name, origin.name, modulePath.orEmpty(), lookupText, matchName).joinToString(":")
    }

    data object ListLiteral : AikenTypedExpectedTypeCandidate {
        override val dedupeKey: String = "[]"
        override val origin: AikenTypedCandidateOrigin = AikenTypedCandidateOrigin.BUILTIN
        override val source: AikenTypedCandidateSource = AikenTypedCandidateSource.LIST_LITERAL
        override val modulePath: String? = null
        override val autoImportMode: AikenTypedCandidateAutoImportMode = AikenTypedCandidateAutoImportMode.NONE
        override val matchDistance: Int = 0
    }

    data object OptionSome : AikenTypedExpectedTypeCandidate {
        override val dedupeKey: String = "Some()"
        override val origin: AikenTypedCandidateOrigin = AikenTypedCandidateOrigin.BUILTIN
        override val source: AikenTypedCandidateSource = AikenTypedCandidateSource.OPTION_SOME
        override val modulePath: String? = null
        override val autoImportMode: AikenTypedCandidateAutoImportMode = AikenTypedCandidateAutoImportMode.NONE
        override val matchDistance: Int = 0
    }

    data class Constructible(
        val name: String,
        val resultType: String,
        val fields: List<AikenConstructibleFieldEntry> = emptyList(),
        val supportsNamedSyntax: Boolean = false,
        override val origin: AikenTypedCandidateOrigin,
        override val source: AikenTypedCandidateSource = AikenTypedCandidateSource.CONSTRUCTIBLE,
        override val modulePath: String? = null,
        override val autoImportMode: AikenTypedCandidateAutoImportMode = AikenTypedCandidateAutoImportMode.NONE,
        override val matchDistance: Int = 0
    ) : AikenTypedExpectedTypeCandidate {
        override val dedupeKey: String =
            listOf("constructible", source.name, origin.name, modulePath.orEmpty(), name).joinToString(":")
    }
}

private val typedCandidateScopeDistances =
    Collections.synchronizedMap(WeakHashMap<AikenTypedExpectedTypeCandidate, Int>())

internal fun <T : AikenTypedExpectedTypeCandidate> T.withScopeDistance(scopeDistance: Int): T {
    if (scopeDistance != Int.MAX_VALUE) {
        typedCandidateScopeDistances[this] = scopeDistance
    }
    return this
}

internal fun AikenTypedExpectedTypeCandidate.scopeDistance(): Int =
    typedCandidateScopeDistances[this] ?: Int.MAX_VALUE
