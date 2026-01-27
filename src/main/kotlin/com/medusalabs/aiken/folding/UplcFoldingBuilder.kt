package com.medusalabs.aiken.folding

/**
 * Basic UPLC folding for balanced expressions.
 */
class UplcFoldingBuilder : BalancedPairFoldingBuilder(
    pairs = listOf('(' to ')', '[' to ']'),
    lineCommentPrefix = "//",
    stringDelimiter = '"'
)

