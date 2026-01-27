package com.medusalabs.aiken.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.AikenLanguage

class AikenPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AikenLanguage) {
    override fun getFileType() = AikenFileType
    override fun toString(): String = "Aiken File"
}

