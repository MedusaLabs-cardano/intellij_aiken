package com.medusalabs.aiken.completion

internal data class AikenConstructibleAutoImportTarget(
    val modulePath: String,
    val symbolName: String
)

internal sealed interface AikenConstructibleInsertionFamily {
    data class Root(
        val lookupText: String,
        val constructible: AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo,
        val autoImportTarget: AikenConstructibleAutoImportTarget? = null
    ) : AikenConstructibleInsertionFamily

    data object NamedForm : AikenConstructibleInsertionFamily

    data object PositionalForm : AikenConstructibleInsertionFamily
}
