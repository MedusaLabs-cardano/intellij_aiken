package com.medusalabs.aiken.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider
import com.medusalabs.aiken.lang.UplcFileType
import com.medusalabs.aiken.lang.UplcLanguage

class UplcPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, UplcLanguage) {
    override fun getFileType() = UplcFileType
    override fun toString(): String = "UPLC File"
}

