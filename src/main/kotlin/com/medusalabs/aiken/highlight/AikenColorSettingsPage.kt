package com.medusalabs.aiken.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.medusalabs.aiken.icons.AikenIcons
import javax.swing.Icon

class AikenColorSettingsPage : ColorSettingsPage {
    private val descriptors: Array<AttributesDescriptor> =
        arrayOf(
            AttributesDescriptor("Keyword", AikenSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Type", AikenSyntaxHighlighter.TYPE),
            AttributesDescriptor("Function", AikenSyntaxHighlighter.FUNCTION),
            AttributesDescriptor("Field", AikenSyntaxHighlighter.FIELD),
            AttributesDescriptor("Boolean", AikenSyntaxHighlighter.BOOLEAN),
            AttributesDescriptor("String", AikenSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", AikenSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comment", AikenSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Operator", AikenSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Punctuation", AikenSyntaxHighlighter.PUNCTUATION),
            AttributesDescriptor("Braces", AikenSyntaxHighlighter.BRACES),
            AttributesDescriptor("Parentheses", AikenSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Brackets", AikenSyntaxHighlighter.BRACKETS)
        )

    override fun getDisplayName(): String = "Aiken"
    override fun getIcon(): Icon = AikenIcons.AIKEN
    override fun getHighlighter(): SyntaxHighlighter = AikenSyntaxHighlighter()
    override fun getDemoText(): String =
        """
        // Aiken example
        pub fn add(a: Int, b: Int) -> Int {
          a + b
        }
        """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = emptyArray()
}
