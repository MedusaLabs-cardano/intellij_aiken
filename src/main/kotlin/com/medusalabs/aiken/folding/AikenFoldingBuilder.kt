package com.medusalabs.aiken.folding

/**
 * Basic Aiken folding for `{ ... }` blocks as a fallback when LSP folding ranges aren't available.
 */
class AikenFoldingBuilder : BalancedPairFoldingBuilder(
    pairs = listOf('{' to '}'),
    lineCommentPrefix = "//",
    stringDelimiter = '"'
)

