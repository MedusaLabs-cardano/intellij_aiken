package com.txpipe.aiken.highlight.lexer

import com.intellij.psi.tree.IElementType
import com.txpipe.aiken.lang.AikenLanguage

object AikenTokenTypes {
    val KEYWORD = IElementType("AIKEN_KEYWORD", AikenLanguage)
    val IDENTIFIER = IElementType("AIKEN_IDENTIFIER", AikenLanguage)
    val TYPE = IElementType("AIKEN_TYPE", AikenLanguage)
    val BOOLEAN = IElementType("AIKEN_BOOLEAN", AikenLanguage)
    val NUMBER = IElementType("AIKEN_NUMBER", AikenLanguage)
    val STRING = IElementType("AIKEN_STRING", AikenLanguage)
    val COMMENT = IElementType("AIKEN_COMMENT", AikenLanguage)
    val WHITESPACE = IElementType("AIKEN_WHITESPACE", AikenLanguage)
    val OPERATOR = IElementType("AIKEN_OPERATOR", AikenLanguage)
    val PUNCT = IElementType("AIKEN_PUNCT", AikenLanguage)
    val FUNCTION = IElementType("AIKEN_FUNCTION", AikenLanguage)
    val FIELD = IElementType("AIKEN_FIELD", AikenLanguage)
    val OTHER = IElementType("AIKEN_OTHER", AikenLanguage)
}
