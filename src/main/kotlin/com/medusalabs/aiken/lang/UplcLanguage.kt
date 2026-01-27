package com.medusalabs.aiken.lang

import com.intellij.lang.Language
import java.io.ObjectStreamException

object UplcLanguage : Language("UPLC") {
    @Throws(ObjectStreamException::class)
    private fun readResolve(): Any = UplcLanguage
}
