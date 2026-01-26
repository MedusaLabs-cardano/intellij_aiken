package com.txpipe.aiken.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.txpipe.aiken.icons.AikenIcons
import javax.swing.Icon

class AikenColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Aiken"
    override fun getIcon(): Icon = AikenIcons.AIKEN
    override fun getHighlighter(): SyntaxHighlighter = AikenSyntaxHighlighter()
    override fun getDemoText(): String = """// Aiken example\nfn add(a: Int, b: Int) -> Int {\n  a + b\n}"""
    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = emptyArray()
    override fun getColorDescriptors(): Array<ColorDescriptor> = emptyArray()
}
