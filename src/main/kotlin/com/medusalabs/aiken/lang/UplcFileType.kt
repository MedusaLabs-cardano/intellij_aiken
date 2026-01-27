package com.medusalabs.aiken.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object UplcFileType : LanguageFileType(UplcLanguage) {
    override fun getName(): String = "UPLC"
    override fun getDescription(): String = "Untyped Plutus Core file"
    override fun getDefaultExtension(): String = "uplc"
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/uplc.png", UplcFileType::class.java)
}
