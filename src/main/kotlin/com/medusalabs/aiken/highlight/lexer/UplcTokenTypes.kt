package com.medusalabs.aiken.highlight.lexer

import com.intellij.psi.tree.IElementType
import com.medusalabs.aiken.lang.UplcLanguage

object UplcTokenTypes {
    val LBRACE = IElementType("UPLC_LBRACE", UplcLanguage)
    val RBRACE = IElementType("UPLC_RBRACE", UplcLanguage)
    val LPAREN = IElementType("UPLC_LPAREN", UplcLanguage)
    val RPAREN = IElementType("UPLC_RPAREN", UplcLanguage)
    val LBRACKET = IElementType("UPLC_LBRACKET", UplcLanguage)
    val RBRACKET = IElementType("UPLC_RBRACKET", UplcLanguage)

    val KEYWORD = IElementType("UPLC_KEYWORD", UplcLanguage)
    val IDENTIFIER = IElementType("UPLC_IDENTIFIER", UplcLanguage)
    val TYPE = IElementType("UPLC_TYPE", UplcLanguage)
    val BOOLEAN = IElementType("UPLC_BOOLEAN", UplcLanguage)
    val NUMBER = IElementType("UPLC_NUMBER", UplcLanguage)
    val STRING = IElementType("UPLC_STRING", UplcLanguage)
    val COMMENT = IElementType("UPLC_COMMENT", UplcLanguage)
    val WHITESPACE = IElementType("UPLC_WHITESPACE", UplcLanguage)
    val OPERATOR = IElementType("UPLC_OPERATOR", UplcLanguage)
    val PUNCT = IElementType("UPLC_PUNCT", UplcLanguage)
    val FUNCTION = IElementType("UPLC_FUNCTION", UplcLanguage)
    val FIELD = IElementType("UPLC_FIELD", UplcLanguage)
    val OTHER = IElementType("UPLC_OTHER", UplcLanguage)
}
