package com.medusalabs.aiken.highlight.lexer

import com.intellij.psi.tree.IElementType
import com.medusalabs.aiken.lang.AikenLanguage

object AikenTokenTypes {
    val LBRACE = IElementType("AIKEN_LBRACE", AikenLanguage)
    val RBRACE = IElementType("AIKEN_RBRACE", AikenLanguage)
    val LPAREN = IElementType("AIKEN_LPAREN", AikenLanguage)
    val RPAREN = IElementType("AIKEN_RPAREN", AikenLanguage)
    val LBRACKET = IElementType("AIKEN_LBRACKET", AikenLanguage)
    val RBRACKET = IElementType("AIKEN_RBRACKET", AikenLanguage)

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
