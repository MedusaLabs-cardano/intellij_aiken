package com.medusalabs.aiken.completion

import com.intellij.openapi.util.TextRange

internal data class AikenFunctionSignatureParameter(
    val name: String?,
    val type: String?
)

internal object AikenFunctionSignatureText {
    fun parameterTypeAt(signature: String, parameterIndex: Int): String? =
        parameters(signature).getOrNull(parameterIndex)?.type?.takeIf { it.isNotEmpty() }

    fun returnType(signature: String): String? {
        val closeParenIndex = signature.lastIndexOf(')')
        if (closeParenIndex < 0 || closeParenIndex >= signature.lastIndex) return null
        val suffix = signature.substring(closeParenIndex + 1).trim()
        if (!suffix.startsWith("->")) return null
        return suffix.removePrefix("->").trim().takeIf { it.isNotEmpty() }
    }

    fun functionType(signature: String, returnTypeFallback: String? = null): String? {
        val parameterTypes =
            parameters(signature).mapNotNull { parameter ->
                parameter.type?.takeIf { it.isNotEmpty() } ?: parameter.name?.takeIf { it.isNotEmpty() }
            }

        val returnType =
            returnType(signature)
                ?: returnTypeFallback?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null

        return "fn(${parameterTypes.joinToString(", ")}) -> $returnType"
    }

    fun parameters(signature: String): List<AikenFunctionSignatureParameter> {
        val openIndex = signature.indexOf('(')
        if (openIndex < 0) return emptyList()
        val closeIndex = AikenSyntaxText.findMatchingDelimiter(signature, openIndex, '(', ')') ?: return emptyList()
        if (closeIndex <= openIndex + 1) return emptyList()

        return AikenTopLevelText
            .splitRanges(signature, ',', openIndex + 1, closeIndex, trackAngles = true)
            .mapNotNull { range -> trimmedRange(signature, range)?.let { parseParameter(signature.substring(it.first, it.last + 1)) } }
    }

    private fun parseParameter(parameterText: String): AikenFunctionSignatureParameter? {
        val trimmed = parameterText.trim()
        if (trimmed.isEmpty()) return null

        val colonIndex = AikenTopLevelText.indexOf(trimmed, ':', trackAngles = true)
        return if (colonIndex <= 0 || colonIndex >= trimmed.lastIndex) {
            AikenFunctionSignatureParameter(name = null, type = trimmed.ifEmpty { null })
        } else {
            AikenFunctionSignatureParameter(
                name = trimmed.substring(0, colonIndex).trim().takeIf { it.isNotEmpty() },
                type = trimmed.substring(colonIndex + 1).trim().takeIf { it.isNotEmpty() }
            )
        }
    }

    private fun trimmedRange(text: String, range: TextRange): IntRange? {
        var startIndex = range.startOffset
        var endIndex = range.endOffset
        while (startIndex < endIndex && text[startIndex].isWhitespace()) startIndex++
        while (endIndex > startIndex && text[endIndex - 1].isWhitespace()) endIndex--
        return if (startIndex < endIndex) startIndex until endIndex else null
    }
}
