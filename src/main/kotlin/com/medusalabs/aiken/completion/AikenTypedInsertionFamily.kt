package com.medusalabs.aiken.completion

internal data class AikenTypedAutoImportTarget(
    val modulePath: String,
    val symbolName: String
)

internal sealed interface AikenTypedInsertionFamily {
    data class ReplaceIdentifier(
        val text: String,
        val autoImportTarget: AikenTypedAutoImportTarget? = null
    ) : AikenTypedInsertionFamily

    data class SpreadIdentifier(
        val text: String,
        val autoImportTarget: AikenTypedAutoImportTarget? = null
    ) : AikenTypedInsertionFamily

    data class FunctionCall(
        val name: String,
        val signature: String,
        val autoImportTarget: AikenTypedAutoImportTarget? = null
    ) : AikenTypedInsertionFamily

    data class PipeCall(
        val lookupText: String,
        val signature: String,
        val autoImportTarget: AikenTypedAutoImportTarget? = null
    ) : AikenTypedInsertionFamily

    data object ListLiteral : AikenTypedInsertionFamily

    data object OptionSome : AikenTypedInsertionFamily
}
