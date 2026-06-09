package com.medusalabs.aiken.completion

internal data class AikenExpectedTypeProfile(
    val primaryType: String,
    val compatibleTypes: Map<String, Int>,
    val aliasEntries: List<AikenTypeAliasEntry> = emptyList()
)

internal data class AikenTypeAliasEntry(
    val alias: String,
    val targetType: String
)
