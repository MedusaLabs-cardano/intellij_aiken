package com.medusalabs.aiken.completion

internal sealed interface AikenReferenceInsertionFamily {
    data class AutoImportedSymbol(
        val modulePath: String,
        val symbolName: String
    ) : AikenReferenceInsertionFamily

    data class AutoImportedQualifiedMember(
        val modulePath: String,
        val symbolName: String,
        val canonicalQualifier: String,
        val triggerMemberAutoPopup: Boolean = false
    ) : AikenReferenceInsertionFamily

    data class AutoImportedModuleQualifier(
        val modulePath: String,
        val exposedModuleName: String
    ) : AikenReferenceInsertionFamily
}
