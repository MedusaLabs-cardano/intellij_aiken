package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_FUNCTION_SIGNATURE_INDEX_NAME
import com.medusalabs.aiken.index.aikenFunctionSignatureModuleKey
import com.medusalabs.aiken.index.aikenFunctionSignatureNameKey
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

object AikenArgumentCompletionSupport {
    fun argumentSpecificVariants(anchor: PsiElement?, offset: Int): List<LookupElement> {
        val file = anchor?.containingFile ?: return emptyList()
        val text = file.text
        for (call in findCallContexts(text, offset)) {
            val expectedType = expectedArgumentType(anchor, call) ?: continue
            return AikenTypeDirectedCompletionSupport.lookupsForExpectedType(anchor, expectedType, call.currentArgumentText)
        }
        return emptyList()
    }

    private fun expectedArgumentType(anchor: PsiElement, call: CallContext): String? {
        val file = anchor.containingFile ?: return null
        val signatures = collectSignatures(anchor, file.text, call)
        if (signatures.isEmpty()) return null

        val expectedIndex = call.argumentIndex + call.implicitArgumentShift
        if (expectedIndex < 0) return null

        val expectedTypes =
            signatures
                .asSequence()
                .mapNotNull { signature -> expectedParameterType(signature, expectedIndex) }
                .map(::normalizeTypeText)
                .filter { it.isNotEmpty() }
                .toSet()

        return if (expectedTypes.size == 1) expectedTypes.first() else null
    }

    private fun collectSignatures(anchor: PsiElement, fileText: String, call: CallContext): Set<String> {
        val sameFileSignatures = AikenFunctionSignatureExtractor.extract(fileText)
        val file = anchor.containingFile ?: return emptySet()
        val scope = AikenSearchScopes.forElement(anchor)
        val index = FileBasedIndex.getInstance()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)

        if (call.qualifier == null) {
            val currentModuleIndexed =
                currentModulePath
                    ?.let { modulePath ->
                        index.getValues(
                            AIKEN_FUNCTION_SIGNATURE_INDEX_NAME,
                            aikenFunctionSignatureModuleKey(modulePath, call.calleeName),
                            scope
                        )
                    }
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            if (currentModuleIndexed.isNotEmpty()) return currentModuleIndexed

            sameFileSignatures[call.calleeName]?.let { return setOf(it) }
        }

        val importedTargets =
            AikenUseStatementParser.parseModel(fileText)
                .resolveCallableTargets(call.calleeName, call.qualifier)
        for (target in importedTargets) {
            val indexed =
                index.getValues(
                    AIKEN_FUNCTION_SIGNATURE_INDEX_NAME,
                    aikenFunctionSignatureModuleKey(target.modulePath, target.symbolName),
                    scope
                )
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            if (indexed.isNotEmpty()) return indexed
        }

        return index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, aikenFunctionSignatureNameKey(call.calleeName), scope)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun expectedParameterType(signature: String, parameterIndex: Int): String? {
        val ranges = computeParameterRanges(signature)
        if (parameterIndex !in ranges.indices) return null
        val range = ranges[parameterIndex]
        val parameterText = signature.substring(range.first, range.last + 1)
        val colonIndex = topLevelColonIndex(parameterText)
        if (colonIndex < 0) return null
        return parameterText.substring(colonIndex + 1).trim().takeIf { it.isNotEmpty() }
    }

    private fun computeParameterRanges(signature: String): List<IntRange> {
        val openIndex = signature.indexOf('(')
        if (openIndex < 0) return emptyList()
        val closeIndex = findMatchingParen(signature, openIndex) ?: return emptyList()

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0
        var segmentStart = openIndex + 1
        val ranges = ArrayList<IntRange>()

        var index = openIndex + 1
        while (index < closeIndex) {
            when (signature[index]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0) {
                        trimmedRange(signature, segmentStart, index)?.let(ranges::add)
                        segmentStart = index + 1
                    }
                }
            }
            index++
        }
        trimmedRange(signature, segmentStart, closeIndex)?.let(ranges::add)
        return ranges
    }

    private fun trimmedRange(text: String, start: Int, endExclusive: Int): IntRange? {
        var startIndex = start
        var endIndex = endExclusive
        while (startIndex < endIndex && text[startIndex].isWhitespace()) startIndex++
        while (endIndex > startIndex && text[endIndex - 1].isWhitespace()) endIndex--
        return if (startIndex < endIndex) startIndex until endIndex else null
    }

    private fun topLevelColonIndex(text: String): Int {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0
        for (index in text.indices) {
            when (text[index]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                ':' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0) return index
            }
        }
        return -1
    }

    private fun findCallContexts(text: String, offset: Int): List<CallContext> {
        val result = ArrayList<CallContext>()

        for (openParenOffset in findOpenParensForOffset(text, offset).asReversed()) {
            val callee = findSimpleCalleeBeforeParen(text, openParenOffset) ?: continue
            val closeParenOffset = findMatchingParen(text, openParenOffset)
            if (closeParenOffset != null && offset > closeParenOffset) continue

            val argumentIndex = computeArgumentIndex(text, openParenOffset, offset) ?: continue
            val arguments = splitTopLevelArguments(text, openParenOffset).orEmpty()
            val directPlaceholderPositions = collectPlaceholderPositions(arguments)
            val implicitShift =
                if (directPlaceholderPositions.isEmpty() && detectPipeImplicitArgumentCount(text, callee.callableStartOffset) > 0) 1
                else 0

            result +=
                CallContext(
                    calleeName = callee.name,
                    qualifier = callee.qualifier,
                    argumentIndex = argumentIndex,
                    implicitArgumentShift = implicitShift,
                    currentArgumentText = currentArgumentText(text, openParenOffset, offset).orEmpty()
                )
        }

        return result
    }

    private fun findSimpleCalleeBeforeParen(text: CharSequence, openParenOffset: Int): Callee? {
        var index = openParenOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return null

        val end = index + 1
        while (index >= 0 && isIdentifierChar(text[index])) index--
        val start = index + 1
        if (start >= end) return null

        val name = text.subSequence(start, end).toString()
        var qualifier: String? = null
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index >= 0 && text[index] == '.') {
            index--
            while (index >= 0 && text[index].isWhitespace()) index--
            val qualifierEnd = index + 1
            while (index >= 0 && isIdentifierChar(text[index])) index--
            val qualifierStart = index + 1
            if (qualifierStart < qualifierEnd) {
                qualifier = text.subSequence(qualifierStart, qualifierEnd).toString()
                return Callee(name, qualifier, qualifierStart)
            }
        }

        return Callee(name, qualifier, start)
    }

    private fun detectPipeImplicitArgumentCount(text: CharSequence, callableStartOffset: Int): Int {
        var index = callableStartOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 1) return 0
        return if (text[index] == '>' && text[index - 1] == '|') 1 else 0
    }

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun splitTopLevelArguments(text: String, openParenOffset: Int): List<String>? {
        val closeParenOffset = findMatchingParen(text, openParenOffset) ?: return null
        val segments = ArrayList<String>()
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var segmentStart = openParenOffset + 1
        var index = openParenOffset + 1

        while (index < closeParenOffset) {
            when (text[index]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        segments += text.substring(segmentStart, index)
                        segmentStart = index + 1
                    }
                }
            }
            index++
        }
        segments += text.substring(segmentStart, closeParenOffset)
        return segments
    }

    private fun collectPlaceholderPositions(arguments: List<String>): List<Int> =
        arguments.mapIndexedNotNull { index, argument -> if (argument.trim() == "_") index else null }

    private fun computeArgumentIndex(text: CharSequence, openParenOffset: Int, offset: Int): Int? {
        if (openParenOffset !in 0 until text.length || text[openParenOffset] != '(') return null

        var index = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var cursor = openParenOffset + 1
        val limit = offset.coerceIn(0, text.length)

        while (cursor < limit) {
            when (text[cursor]) {
                '(' -> parenDepth++
                ')' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return null
                    if (parenDepth > 0) parenDepth--
                }
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) index++
            }
            cursor++
        }
        return index
    }

    private fun currentArgumentText(text: CharSequence, openParenOffset: Int, offset: Int): String? {
        if (openParenOffset !in 0 until text.length || text[openParenOffset] != '(') return null

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var segmentStart = openParenOffset + 1
        var cursor = openParenOffset + 1
        val limit = offset.coerceIn(0, text.length)

        while (cursor < limit) {
            when (text[cursor]) {
                '(' -> parenDepth++
                ')' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        return text.subSequence(segmentStart, cursor).toString()
                    }
                    if (parenDepth > 0) parenDepth--
                }
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        segmentStart = cursor + 1
                    }
                }
            }
            cursor++
        }

        return text.subSequence(segmentStart, limit).toString()
    }

    private fun findOpenParensForOffset(text: CharSequence, offset: Int): List<Int> {
        val stack = ArrayDeque<Int>()
        var index = 0
        val limit = offset.coerceIn(0, text.length)
        while (index < limit) {
            when (text[index]) {
                '(' -> stack.addLast(index)
                ')' -> if (stack.isNotEmpty()) stack.removeLast()
            }
            index++
        }
        return stack.toList()
    }

    private fun findMatchingParen(text: CharSequence, openIndex: Int): Int? {
        if (openIndex !in 0 until text.length || text[openIndex] != '(') return null
        var depth = 0
        var index = openIndex
        while (index < text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun normalizeTypeText(text: String): String {
        val builder = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    builder.append(' ')
                    lastWasSpace = true
                }
            } else {
                builder.append(ch)
                lastWasSpace = false
            }
        }
        return builder.toString().trim()
    }

    private data class CallContext(
        val calleeName: String,
        val qualifier: String?,
        val argumentIndex: Int,
        val implicitArgumentShift: Int,
        val currentArgumentText: String
    )

    private data class Callee(
        val name: String,
        val qualifier: String?,
        val callableStartOffset: Int
    )
}
