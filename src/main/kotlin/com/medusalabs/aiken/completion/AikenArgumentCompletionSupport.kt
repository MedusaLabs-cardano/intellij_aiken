package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.aikenFunctionSignatureIndexName
import com.medusalabs.aiken.index.aikenFunctionSignatureModuleKey
import com.medusalabs.aiken.index.aikenFunctionSignatureNameKey
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

object AikenArgumentCompletionSupport {
    fun hasArgumentContext(anchor: PsiElement?, offset: Int): Boolean {
        val file = anchor?.containingFile ?: return false
        return findCallContexts(file.text, offset).isNotEmpty()
    }

    fun hasArgumentContext(text: String, offset: Int = text.length): Boolean =
        findCallContexts(text, offset.coerceIn(0, text.length)).isNotEmpty()

    fun hasPipeContext(anchor: PsiElement?, offset: Int): Boolean {
        val file = anchor?.containingFile ?: return false
        return findPipeContext(file.text, offset) != null
    }

    fun hasPipeContext(text: String, offset: Int = text.length): Boolean =
        findPipeContext(text, offset.coerceIn(0, text.length)) != null

    fun argumentSpecificVariants(anchor: PsiElement?, offset: Int): List<LookupElement> {
        val file = anchor?.containingFile ?: return emptyList()
        val text = file.text
        for (call in findCallContexts(text, offset)) {
            val expectedType = expectedArgumentType(anchor, call) ?: continue
            return AikenTypeDirectedCompletionSupport.lookupsForExpectedType(anchor, expectedType, call.currentArgument.text)
        }
        return emptyList()
    }

    fun hasBlankArgumentWithUnconstrainedExpectedType(anchor: PsiElement?, offset: Int): Boolean {
        val file = anchor?.containingFile ?: return false
        val text = file.text
        for (call in findCallContexts(text, offset)) {
            if (call.currentArgument.text.isNotBlank()) continue
            val expectedType = expectedArgumentType(anchor, call) ?: continue
            return AikenTypedLookupFactory.isBareGenericTypeParameter(expectedType)
        }
        return false
    }

    fun pipeSpecificVariants(anchor: PsiElement?, offset: Int): List<LookupElement> {
        val file = anchor?.containingFile ?: return emptyList()
        val pipeContext = findPipeContext(file.text, offset) ?: return emptyList()
        val inferredInputType =
            AikenTypeDirectedCompletionSupport.inferExpressionType(anchor, pipeContext.leftExpression, pipeContext.pipeOffset)
                ?: return emptyList()
        return AikenTypeDirectedCompletionSupport.pipeFunctionLookupsForInputType(
            anchor = anchor,
            inputType = inferredInputType,
            qualifier = pipeContext.qualifier
        )
    }

    private fun expectedArgumentType(anchor: PsiElement, call: CallContext): String? {
        val constructorExpectedTypes =
            AikenConstructibleCompletionSupport.expectedParameterTypes(
                anchor = anchor,
                calleeName = call.calleeName,
                qualifier = call.qualifier,
                parameterIndex = call.argumentIndex + call.implicitArgumentShift
            )
        if (constructorExpectedTypes.size == 1) {
            return constructorExpectedTypes.first()
        }

        val file = anchor.containingFile ?: return null
        val signatures = collectSignatures(anchor, file.text, call)
        if (signatures.isEmpty()) return null

        val expectedIndex = call.argumentIndex + call.implicitArgumentShift
        if (expectedIndex < 0) return null

        val expectedTypes =
            signatures
                .asSequence()
                .mapNotNull { signature -> expectedParameterType(signature, expectedIndex) }
                .map(AikenTypeText::normalizeWhitespace)
                .filter { it.isNotEmpty() }
                .toSet()

        return if (expectedTypes.size == 1) expectedTypes.first() else null
    }

    private fun collectSignatures(anchor: PsiElement, fileText: String, call: CallContext): Set<String> {
        val sameFileSignatures = AikenFunctionSignatureExtractor.extract(fileText)
        val sameFileValidatorHandlerSignatures =
            AikenFunctionSignatureExtractor.extractValidatorHandlerEntries(fileText)
                .asSequence()
                .filter { entry ->
                    entry.validatorName == call.qualifier &&
                        entry.handlerName == call.calleeName
                }
                .map { it.signature }
                .toSet()
        val file = anchor.containingFile ?: return emptySet()
        val scope = AikenSearchScopes.forElement(anchor)
        val index = FileBasedIndex.getInstance()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val useModel = AikenUseStatementParser.parseModel(fileText)

        if (sameFileValidatorHandlerSignatures.isNotEmpty()) return sameFileValidatorHandlerSignatures

        val importedValidatorHandlerSignatures =
            call.qualifier
                ?.let { qualifierChain ->
                    AikenValidatorNamespaceSupport.importedValidatorHandlerSignatures(
                        anchorFile = file.virtualFile,
                        useModel = useModel,
                        qualifierChain = qualifierChain,
                        handlerName = call.calleeName
                    )
                }
                .orEmpty()
                .toSet()
        if (importedValidatorHandlerSignatures.isNotEmpty()) return importedValidatorHandlerSignatures

        if (call.qualifier == null) {
            val currentModuleIndexed =
                currentModulePath
                    ?.let { modulePath ->
                        index.getValues(
                            aikenFunctionSignatureIndexName,
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
            useModel
                .resolveCallableTargets(call.calleeName, call.qualifier)
        for (target in importedTargets) {
            val indexed =
                index.getValues(
                    aikenFunctionSignatureIndexName,
                    aikenFunctionSignatureModuleKey(target.modulePath, target.symbolName),
                    scope
                )
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            if (indexed.isNotEmpty()) return indexed
        }

        return index.getValues(aikenFunctionSignatureIndexName, aikenFunctionSignatureNameKey(call.calleeName), scope)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun expectedParameterType(signature: String, parameterIndex: Int): String? =
        AikenFunctionSignatureText.parameterTypeAt(signature, parameterIndex)

    private fun findCallContexts(text: String, offset: Int): List<CallContext> {
        val result = ArrayList<CallContext>()

        for (openParenOffset in findOpenParensForOffset(text, offset).asReversed()) {
            val callee = findSimpleCalleeBeforeParen(text, openParenOffset) ?: continue
            val closeParenOffset = AikenSyntaxText.findMatchingDelimiter(text, openParenOffset, '(', ')')
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
                    currentArgument = currentArgument(text, openParenOffset, offset)
                )
        }

        return result
    }

    private fun findSimpleCalleeBeforeParen(text: CharSequence, openParenOffset: Int): Callee? {
        var index = openParenOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return null

        val end = index + 1
        while (index >= 0 && AikenSyntaxText.isIdentifierChar(text[index])) index--
        val start = index + 1
        if (start >= end) return null

        val name = text.subSequence(start, end).toString()
        val qualifier = AikenSyntaxText.qualifiedChainBeforeOffset(text, start)
        if (qualifier != null) {
            val qualifierStart = (start - qualifier.length - 1).coerceAtLeast(0)
            return Callee(name, qualifier, qualifierStart)
        }

        return Callee(name, null, start)
    }

    private fun detectPipeImplicitArgumentCount(text: CharSequence, callableStartOffset: Int): Int {
        var index = callableStartOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 1) return 0
        return if (text[index] == '>' && text[index - 1] == '|') 1 else 0
    }

    private fun findPipeContext(text: String, offset: Int): PipeContext? {
        if (text.isEmpty() || offset <= 0) return null
        val currentExpressionStart =
            AikenTopLevelText.findExpressionStartBefore(
                text = text,
                endExclusive = offset,
                stopAtPipeOperator = false
            )
                ?: return null
        val pipeOffset = AikenSyntaxText.findLastTopLevelPipeOffset(text, offset, trackNesting = false) ?: return null
        if (pipeOffset < currentExpressionStart) return null

        val leftExpression = extractPipeLeftExpression(text, pipeOffset)?.trim().orEmpty()
        if (leftExpression.isEmpty()) return null

        val rightSegment =
            AikenCurrentExpressionSegment.fromRange(
                source = text,
                startOffset = (pipeOffset + 2).coerceAtMost(text.length),
                endExclusive = offset.coerceIn(0, text.length)
            )
        return PipeContext(
            leftExpression = leftExpression,
            qualifier = AikenSyntaxText.qualifierOfLeadingIdentifier(rightSegment.text),
            rightSegment = rightSegment,
            pipeOffset = pipeOffset
        )
    }

    private fun extractPipeLeftExpression(text: String, pipeOffset: Int): String? {
        val start =
            AikenTopLevelText.findExpressionStartBefore(
                text = text,
                endExclusive = pipeOffset,
                stopAtPipeOperator = true
            )
                ?: return null
        return text.substring(start, pipeOffset)
    }

    private fun splitTopLevelArguments(text: String, openParenOffset: Int): List<String>? {
        val closeParenOffset = AikenSyntaxText.findMatchingDelimiter(text, openParenOffset, '(', ')') ?: return null
        val segments = ArrayList<String>()
        for (range in AikenTopLevelText.splitRanges(text, ',', openParenOffset + 1, closeParenOffset)) {
            segments += text.substring(range.startOffset, range.endOffset)
        }
        return segments
    }

    private fun collectPlaceholderPositions(arguments: List<String>): List<Int> =
        arguments.mapIndexedNotNull { index, argument -> if (argument.trim() == "_") index else null }

    private fun computeArgumentIndex(text: CharSequence, openParenOffset: Int, offset: Int): Int? {
        if (openParenOffset !in 0 until text.length || text[openParenOffset] != '(') return null
        val limit = offset.coerceIn(0, text.length)
        return AikenTopLevelText.segmentIndexAt(
            text = text,
            delimiter = ',',
            start = openParenOffset + 1,
            endExclusive = limit,
            closingDelimiter = ')'
        )
    }

    private fun currentArgument(text: CharSequence, openParenOffset: Int, offset: Int): AikenCurrentExpressionSegment {
        if (openParenOffset !in 0 until text.length || text[openParenOffset] != '(') {
            return AikenCurrentExpressionSegment.fromText("")
        }
        return AikenCurrentExpressionSegment.fromDelimitedRange(
            text = text,
            delimiter = ',',
            start = openParenOffset + 1,
            endExclusive = offset.coerceIn(0, text.length),
            closingDelimiter = ')'
        )
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

    private data class CallContext(
        val calleeName: String,
        val qualifier: String?,
        val argumentIndex: Int,
        val implicitArgumentShift: Int,
        val currentArgument: AikenCurrentExpressionSegment
    )

    private data class Callee(
        val name: String,
        val qualifier: String?,
        val callableStartOffset: Int
    )

    private data class PipeContext(
        val leftExpression: String,
        val qualifier: String?,
        val rightSegment: AikenCurrentExpressionSegment,
        val pipeOffset: Int
    )
}
