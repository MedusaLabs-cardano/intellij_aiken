package com.medusalabs.aiken.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.medusalabs.aiken.icons.AikenIcons
import javax.swing.Icon

class UplcColorSettingsPage : ColorSettingsPage {
    private val descriptors: Array<AttributesDescriptor> =
        arrayOf(
            AttributesDescriptor("Keyword", UplcSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Type", UplcSyntaxHighlighter.TYPE),
            AttributesDescriptor("Function", UplcSyntaxHighlighter.FUNCTION),
            AttributesDescriptor("Field", UplcSyntaxHighlighter.FIELD),
            AttributesDescriptor("Boolean", UplcSyntaxHighlighter.BOOLEAN),
            AttributesDescriptor("String", UplcSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", UplcSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comment", UplcSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Operator", UplcSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Punctuation", UplcSyntaxHighlighter.PUNCTUATION),
            AttributesDescriptor("Braces", UplcSyntaxHighlighter.BRACES),
            AttributesDescriptor("Parentheses", UplcSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Brackets", UplcSyntaxHighlighter.BRACKETS)
        )

    override fun getDisplayName(): String = "UPLC"
    override fun getIcon(): Icon = AikenIcons.UPLC
    override fun getHighlighter(): SyntaxHighlighter = UplcSyntaxHighlighter()
    override fun getDemoText(): String =
        """
        program 1.0.0
        con integer 42
        lam x -> x
        """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = emptyArray()
}
