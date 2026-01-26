package com.txpipe.aiken.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AikenFileType : LanguageFileType(AikenLanguage) {
    override fun getName(): String = "Aiken"
    override fun getDescription(): String = "Aiken source file"
    override fun getDefaultExtension(): String = "ak"
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/aiken.png", AikenFileType::class.java)
}
