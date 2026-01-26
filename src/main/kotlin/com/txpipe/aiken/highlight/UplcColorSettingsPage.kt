package com.txpipe.aiken.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.txpipe.aiken.icons.AikenIcons
import javax.swing.Icon

class UplcColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "UPLC"
    override fun getIcon(): Icon = AikenIcons.UPLC
    override fun getHighlighter(): SyntaxHighlighter = UplcSyntaxHighlighter()
    override fun getDemoText(): String = """program 1.0.0\ncon integer 42\nlam x -> x"""
    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = emptyArray()
    override fun getColorDescriptors(): Array<ColorDescriptor> = emptyArray()
}
