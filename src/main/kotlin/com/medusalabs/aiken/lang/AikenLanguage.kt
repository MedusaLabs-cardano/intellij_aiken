package com.medusalabs.aiken.lang

import com.intellij.lang.Language
import java.io.ObjectStreamException

object AikenLanguage : Language("Aiken") {
    @Throws(ObjectStreamException::class)
    private fun readResolve(): Any = AikenLanguage
}
