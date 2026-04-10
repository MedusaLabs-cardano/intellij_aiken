package com.medusalabs.aiken.completion

internal sealed interface AikenTypedCandidateContext {
    data object None : AikenTypedCandidateContext

    data class RecordFieldValue(
        val siblingFields: List<SiblingField>
    ) : AikenTypedCandidateContext {
        data class SiblingField(
            val name: String,
            val type: String
        )
    }
}
