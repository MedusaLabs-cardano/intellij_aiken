package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_CONSTRUCTIBLE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_CONST_TYPE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_EXPORT_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_FUNCTION_SIGNATURE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME
import com.medusalabs.aiken.index.AikenConstTypeExtractor
import com.medusalabs.aiken.index.AikenConstructibleFieldEntry
import com.medusalabs.aiken.index.AikenConstructibleExtractor
import com.medusalabs.aiken.index.AikenPublicExportExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.aikenConstructibleResultTypeKey
import com.medusalabs.aiken.index.aikenConstTypeModuleKey
import com.medusalabs.aiken.index.aikenConstTypeTypeKey
import com.medusalabs.aiken.index.aikenFunctionSignatureReturnTypeKey
import com.medusalabs.aiken.index.aikenFunctionSignatureReturnType
import com.medusalabs.aiken.index.aikenFunctionSignatureModuleKey
import com.medusalabs.aiken.index.aikenTopLevelSymbolNameKey
import com.medusalabs.aiken.index.decodeAikenConstructibleReturnTypeIndexValues
import com.medusalabs.aiken.index.decodeAikenExportIndexValue
import com.medusalabs.aiken.index.decodeAikenConstTypeIndexValues
import com.medusalabs.aiken.index.decodeAikenConstructibleIndexValue
import com.medusalabs.aiken.index.decodeAikenFunctionReturnTypeIndexValues
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

data class AikenTypedCompletionCandidate(
    val name: String,
    val type: String,
    val kind: CompletionSymbolKind,
    val priority: Double
)

object AikenTypeDirectedCompletionSupport {
    private const val LIST_LITERAL_PRIORITY = 4840.0
    private const val EXTRA_TYPED_CANDIDATE_PRIORITY = 5000.0
    private const val TYPED_BINDING_PRIORITY = 4900.0
    private const val LOCAL_TYPED_CONST_PRIORITY = 4895.0
    private const val IMPORTED_TYPED_CONST_PRIORITY = 4890.0
    private const val LOCAL_TYPED_FUNCTION_PRIORITY = 4885.0
    private const val IMPORTED_TYPED_FUNCTION_PRIORITY = 4880.0
    private const val UNIMPORTED_CONST_PRIORITY = 4875.0
    private const val UNIMPORTED_FUNCTION_PRIORITY = 4850.0
    private const val QUALIFIED_PIPE_FUNCTION_PRIORITY = 4870.0
    private const val OPTION_SOME_PRIORITY = 4825.0
    private const val BUILT_IN_INVARIANT_PRIORITY = 4800.0
    private const val CONSTRUCTIBLE_PRIORITY = 4700.0

    fun lookupsForExpectedType(
        anchor: PsiElement,
        expectedType: String,
        currentValueText: String = "",
        extraCandidates: List<AikenTypedCompletionCandidate> = emptyList(),
        excludedNames: Set<String> = emptySet()
    ): List<LookupElement> {
        val effectiveContext = effectiveExpectedTypeContext(expectedType, currentValueText) ?: return emptyList()
        val normalizedExpectedType = normalizeTypeText(effectiveContext.expectedType)
        if (normalizedExpectedType.isEmpty()) return emptyList()
        if (effectiveContext.isSpread) {
            return spreadLookupsForExpectedType(anchor, normalizedExpectedType, excludedNames)
        }
        val equivalentExpectedTypes = equivalentTypeNames(anchor, normalizedExpectedType)

        val lookups = ArrayList<LookupElement>()
        val seen = LinkedHashSet<String>()
        val typedBindings = collectVisibleTypedBindings(anchor, equivalentExpectedTypes, excludedNames)

        for (candidate in extraCandidates) {
            if (candidate.name in excludedNames) continue
            if (!matchesExpectedTypes(candidate.type, equivalentExpectedTypes) || !seen.add(candidate.name)) continue
            lookups += createTypeDirectedLookup(candidate.name, candidate.kind, maxOf(candidate.priority, EXTRA_TYPED_CANDIDATE_PRIORITY), candidate.type)
        }

        for (binding in typedBindings) {
            if (!seen.add(binding.name)) continue
            lookups += createTypeDirectedLookup(binding.name, CompletionSymbolKind.IDENTIFIER, TYPED_BINDING_PRIORITY, binding.type)
        }

        for (constant in collectVisibleTypedConsts(anchor, equivalentExpectedTypes, excludedNames)) {
            if (!seen.add(constant.name)) continue
            lookups += createTypeDirectedLookup(constant.name, CompletionSymbolKind.IDENTIFIER, constant.priority, constant.type)
        }

        for (function in collectVisibleTypedFunctions(anchor, equivalentExpectedTypes, excludedNames)) {
            if (!seen.add(function.name)) continue
            lookups += createVisibleFunctionLookup(function, normalizedExpectedType)
        }

        for (constant in collectUnimportedConstsReturning(anchor, equivalentExpectedTypes, excludedNames, seen)) {
            if (!seen.add(constant.name)) continue
            lookups += createAutoImportedConstLookup(anchor, constant, normalizedExpectedType)
        }

        for (function in collectUnimportedFunctionsReturning(anchor, equivalentExpectedTypes, excludedNames, seen)) {
            if (!seen.add(function.name)) continue
            lookups += createAutoImportedFunctionLookup(anchor, function, normalizedExpectedType)
        }

        val listItemType = equivalentExpectedTypes.asSequence().mapNotNull(::unwrapListType).firstOrNull()
        if (listItemType != null && seen.add("[]")) {
            lookups += createListLiteralLookup(normalizedExpectedType)
        }

        val optionInnerType = equivalentExpectedTypes.asSequence().mapNotNull(::unwrapOptionType).firstOrNull()
        if (optionInnerType != null && seen.add("Some()")) {
            lookups += createOptionSomeLookup(normalizedExpectedType)
        }

        for ((name, kind) in builtInInvariantCandidates(equivalentExpectedTypes)) {
            if (!seen.add(name)) continue
            lookups += createTypeDirectedLookup(name, kind, BUILT_IN_INVARIANT_PRIORITY, normalizedExpectedType)
        }

        for (constructible in collectVisibleConstructibles(anchor, equivalentExpectedTypes, excludedNames)) {
            if (!seen.add(constructible.name)) continue
            lookups +=
                if (constructible.needsImport && !constructible.modulePath.isNullOrBlank()) {
                    AikenConstructibleCompletionSupport.createAutoImportedLookup(constructible.toCompletionInfo(), CONSTRUCTIBLE_PRIORITY, normalizedExpectedType)
                } else {
                    AikenConstructibleCompletionSupport.createVisibleLookup(constructible.toCompletionInfo(), CONSTRUCTIBLE_PRIORITY, normalizedExpectedType)
                }
        }

        for (constructible in collectUnimportedConstructiblesReturning(anchor, equivalentExpectedTypes, excludedNames, seen)) {
            if (!seen.add(constructible.name)) continue
            lookups += AikenConstructibleCompletionSupport.createAutoImportedLookup(constructible.toCompletionInfo(), CONSTRUCTIBLE_PRIORITY, normalizedExpectedType)
        }

        return lookups
    }

    fun spreadLookupsForExpectedType(
        anchor: PsiElement,
        expectedType: String,
        excludedNames: Set<String> = emptySet()
    ): List<LookupElement> {
        val normalizedExpectedType = normalizeTypeText(expectedType)
        if (normalizedExpectedType.isEmpty()) return emptyList()

        val equivalentExpectedTypes = equivalentTypeNames(anchor, normalizedExpectedType)
        val seen = LinkedHashSet<String>()
        val lookups = ArrayList<LookupElement>()

        for (binding in collectVisibleTypedBindings(anchor, equivalentExpectedTypes, excludedNames)) {
            if (!seen.add(binding.name)) continue
            lookups += createSpreadLookup(binding.name, CompletionSymbolKind.IDENTIFIER, TYPED_BINDING_PRIORITY, binding.type)
        }

        for (constant in collectVisibleTypedConsts(anchor, equivalentExpectedTypes, excludedNames)) {
            if (!seen.add(constant.name)) continue
            lookups += createSpreadLookup(constant.name, CompletionSymbolKind.IDENTIFIER, constant.priority, constant.type)
        }

        for (constant in collectUnimportedConstsReturning(anchor, equivalentExpectedTypes, excludedNames, seen)) {
            if (!seen.add(constant.name)) continue
            lookups += createAutoImportedSpreadConstLookup(anchor, constant, normalizedExpectedType)
        }

        return lookups
    }

    fun listLiteralItemLookups(
        anchor: PsiElement,
        fileText: String,
        offset: Int
    ): List<LookupElement> {
        val context = inferListLiteralContext(anchor, fileText, offset) ?: return emptyList()
        return lookupsForExpectedType(
            anchor = anchor,
            expectedType = context.expectedListType,
            currentValueText = "[${context.currentSegmentText}"
        )
    }

    fun pipeFunctionLookupsForInputType(
        anchor: PsiElement,
        inputType: String,
        qualifier: String? = null,
        excludedNames: Set<String> = emptySet()
    ): List<LookupElement> {
        val normalizedInputType = normalizeTypeText(inputType)
        if (normalizedInputType.isEmpty()) return emptyList()

        val equivalentInputTypes = equivalentTypeNames(anchor, normalizedInputType)
        val normalizedQualifier = qualifier?.trim().orEmpty().ifEmpty { null }
        val lookups = ArrayList<LookupElement>()
        val seen = LinkedHashSet<String>()

        if (normalizedQualifier != null) {
            for (function in collectQualifiedPipeFunctions(anchor, equivalentInputTypes, normalizedQualifier, excludedNames)) {
                if (!seen.add(function.lookupText)) continue
                lookups += createVisiblePipeFunctionLookup(function.lookupText, function.signature, function.priority)
            }
            return lookups
        }

        for (function in collectVisiblePipeFunctions(anchor, equivalentInputTypes, excludedNames)) {
            if (!seen.add(function.lookupText)) continue
            lookups += createVisiblePipeFunctionLookup(function.lookupText, function.signature, function.priority)
        }

        for (function in collectQualifiedPipeFunctions(anchor, equivalentInputTypes, null, excludedNames)) {
            if (!seen.add(function.lookupText)) continue
            lookups += createQualifiedPipeVisibleFunctionLookup(function.lookupText, function.matchName, function.signature, function.priority)
        }

        for (function in collectUnimportedPipeFunctions(anchor, equivalentInputTypes, excludedNames, seen)) {
            if (!seen.add(function.name)) continue
            lookups += createAutoImportedPipeFunctionLookup(anchor, function)
        }

        return lookups
    }

    fun inferExpressionType(
        anchor: PsiElement,
        expressionText: String,
        beforeOffset: Int = anchor.textRange.startOffset
    ): String? = inferExpressionTypeInternal(expressionText, anchor, beforeOffset, linkedSetOf())

    private fun inferExpressionTypeInternal(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        var trimmedExpression = normalizeExpressionForInference(expressionText)
        if (trimmedExpression.isBlank()) return null

        while (true) {
            val unwrapped = unwrapSingleParenthesizedExpression(trimmedExpression)
            if (unwrapped == null || unwrapped == trimmedExpression) break
            trimmedExpression = normalizeExpressionForInference(unwrapped)
        }

        findTopLevelPipeOffset(trimmedExpression)?.let { pipeOffset ->
            val rightExpression = trimmedExpression.substring((pipeOffset + 2).coerceAtMost(trimmedExpression.length)).trim()
            if (rightExpression.isNotEmpty()) {
                inferExpressionTypeInternal(rightExpression, anchor, beforeOffset, visitedDeclarationOffsets)?.let { return it }
            }
        }

        if (startsWithKeyword(trimmedExpression, "if")) {
            inferIfExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets)?.let { return it }
        }

        if (startsWithKeyword(trimmedExpression, "when")) {
            inferWhenExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets)?.let { return it }
        }

        if (trimmedExpression.startsWith("{")) {
            inferBlockExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets)?.let { return it }
        }

        return inferSimpleExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets)
    }

    private fun inferSimpleExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        if (expressionText.isBlank()) return null
        if (expressionText.startsWith("True") && isTokenBoundary(expressionText, "True".length)) return "Bool"
        if (expressionText.startsWith("False") && isTokenBoundary(expressionText, "False".length)) return "Bool"
        if (expressionText.startsWith("[")) {
            return inferListLiteralType(expressionText, 0, anchor, beforeOffset, visitedDeclarationOffsets)
        }

        val effectiveExpression = expressionText.removePrefix("..").trimStart()
        val headRange = readQualifiedIdentifierRange(effectiveExpression, 0) ?: return null
        val head = effectiveExpression.substring(headRange.first, headRange.last + 1)
        val nextIndex = skipWhitespace(effectiveExpression, headRange.last + 1)
        val nextChar = effectiveExpression.getOrNull(nextIndex)

        return when (nextChar) {
            '{' -> resolveConstructibleResultType(anchor, head) ?: head.substringAfterLast('.')
            '(' -> {
                resolveConstructibleResultType(anchor, head)
                    ?: resolveFunctionReturnType(anchor, head)
                    ?: resolveConstType(anchor, head)
                    ?: resolveVisibleBindingType(anchor, head, beforeOffset, visitedDeclarationOffsets)
            }
            else -> {
                resolveConstType(anchor, head)
                    ?: resolveVisibleBindingType(anchor, head, beforeOffset, visitedDeclarationOffsets)
                    ?: resolveConstructibleResultType(anchor, head)
                    ?: resolveFunctionReturnType(anchor, head)
            }
        }
    }

    private fun inferIfExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        val thenOpenBrace = findTopLevelChar(expressionText, '{', "if".length) ?: return null
        val thenCloseBrace = findMatchingDelimiter(expressionText, thenOpenBrace, '{', '}') ?: return null
        val thenBranchType =
            inferBlockBodyType(
                expressionText.substring(thenOpenBrace + 1, thenCloseBrace),
                anchor,
                beforeOffset,
                visitedDeclarationOffsets
            )

        val afterThen = skipWhitespace(expressionText, thenCloseBrace + 1)
        if (!startsWithKeyword(expressionText, "else", afterThen)) return thenBranchType

        val elseStart = skipWhitespace(expressionText, afterThen + "else".length)
        if (elseStart >= expressionText.length) return thenBranchType
        val elseExpression = expressionText.substring(elseStart).trim()
        val elseBranchType = inferExpressionTypeInternal(elseExpression, anchor, beforeOffset, visitedDeclarationOffsets)

        return mergeBranchTypes(
            listOf(
                InferredBranch(thenBranchType, expressionText.substring(thenOpenBrace + 1, thenCloseBrace)),
                InferredBranch(elseBranchType, elseExpression)
            )
        )
    }

    private fun inferWhenExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        val bodyOpenBrace = findTopLevelChar(expressionText, '{', "when".length) ?: return null
        val bodyCloseBrace = findMatchingDelimiter(expressionText, bodyOpenBrace, '{', '}') ?: return null
        val bodyText = expressionText.substring(bodyOpenBrace + 1, bodyCloseBrace)
        val branchExpressions = extractWhenBranchExpressions(bodyText)
        if (branchExpressions.isEmpty()) return null

        return mergeBranchTypes(
            branchExpressions.map { branchExpression ->
                InferredBranch(
                    inferExpressionTypeInternal(branchExpression, anchor, beforeOffset, visitedDeclarationOffsets),
                    branchExpression
                )
            }
        )
    }

    private fun inferBlockExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        val closeBrace = findMatchingDelimiter(expressionText, 0, '{', '}') ?: return null
        if (closeBrace != expressionText.lastIndex) return null
        return inferBlockBodyType(expressionText.substring(1, closeBrace), anchor, beforeOffset, visitedDeclarationOffsets)
    }

    private fun inferBlockBodyType(
        blockBodyText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        val lastExpression = lastTopLevelBlockExpression(blockBodyText) ?: return null
        return inferExpressionTypeInternal(lastExpression, anchor, beforeOffset, visitedDeclarationOffsets)
    }

    private fun lastTopLevelBlockExpression(blockBodyText: String): String? =
        topLevelSegments(blockBodyText).lastOrNull { it.isNotBlank() }

    private fun topLevelSegments(text: String): List<String> {
        val segments = ArrayList<String>()
        var segmentStart = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false
                    addTopLevelSegment(text, segmentStart, index, segments)
                    segmentStart = index + 1
                }
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        addTopLevelSegment(text, segmentStart, index, segments)
                        segmentStart = index + 1
                    }
                }
            }
            index++
        }

        addTopLevelSegment(text, segmentStart, text.length, segments)
        return segments
    }

    private fun addTopLevelSegment(
        text: String,
        start: Int,
        endExclusive: Int,
        segments: MutableList<String>
    ) {
        val segment = normalizeExpressionForInference(text.substring(start, endExclusive))
        if (segment.isNotBlank()) {
            segments += segment
        }
    }

    private fun extractWhenBranchExpressions(bodyText: String): List<String> {
        val branches = ArrayList<String>()
        var cursor = 0

        while (cursor < bodyText.length) {
            val arrowOffset = findTopLevelArrow(bodyText, cursor) ?: break
            val expressionStart = skipWhitespace(bodyText, arrowOffset + 2)
            if (expressionStart >= bodyText.length) break
            val expressionEnd = consumeExpressionEnd(bodyText, expressionStart)
            val branchExpression = normalizeExpressionForInference(bodyText.substring(expressionStart, expressionEnd))
            if (branchExpression.isNotBlank()) {
                branches += branchExpression
            }
            cursor = expressionEnd
        }

        return branches
    }

    private fun consumeExpressionEnd(text: String, startIndex: Int): Int {
        val start = skipWhitespace(text, startIndex)
        if (start >= text.length) return start

        if (startsWithKeyword(text, "if", start)) {
            return start + consumeIfExpressionLength(text.substring(start))
        }

        if (startsWithKeyword(text, "when", start)) {
            return start + consumeWhenExpressionLength(text.substring(start))
        }

        if (text[start] == '{') {
            return (findMatchingDelimiter(text, start, '{', '}') ?: (text.length - 1)) + 1
        }

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = start

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') return index
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
            }
            index++
        }

        return text.length
    }

    private fun consumeIfExpressionLength(text: String): Int {
        val thenOpenBrace = findTopLevelChar(text, '{', "if".length) ?: return consumeExpressionEnd(text, 0)
        val thenCloseBrace = findMatchingDelimiter(text, thenOpenBrace, '{', '}') ?: return text.length
        val afterThen = skipWhitespace(text, thenCloseBrace + 1)
        if (!startsWithKeyword(text, "else", afterThen)) return thenCloseBrace + 1
        val elseStart = skipWhitespace(text, afterThen + "else".length)
        return if (elseStart >= text.length) thenCloseBrace + 1 else consumeExpressionEnd(text, elseStart)
    }

    private fun consumeWhenExpressionLength(text: String): Int {
        val bodyOpenBrace = findTopLevelChar(text, '{', "when".length) ?: return text.length
        val bodyCloseBrace = findMatchingDelimiter(text, bodyOpenBrace, '{', '}') ?: return text.length
        return bodyCloseBrace + 1
    }

    private fun mergeBranchTypes(branches: List<InferredBranch>): String? {
        val concreteBranchTypes =
            branches.mapNotNull { branch ->
                branch.type?.let(::normalizeTypeText)?.takeIf { it.isNotEmpty() }
                    ?: branch.expression.takeIf(::isBottomLikeExpression)?.let { null }
            }
        if (concreteBranchTypes.isEmpty()) return null

        val primaryType = concreteBranchTypes.first()
        return if (concreteBranchTypes.all { typePatternsAreCompatible(it, primaryType) || typePatternsAreCompatible(primaryType, it) }) {
            primaryType
        } else {
            null
        }
    }

    private fun isBottomLikeExpression(expressionText: String): Boolean {
        val normalized = normalizeExpressionForInference(expressionText)
        return startsWithKeyword(normalized, "fail") || startsWithKeyword(normalized, "todo")
    }

    private fun normalizeExpressionForInference(expressionText: String): String {
        var normalized = expressionText.trim()
        while (normalized.endsWith(",")) {
            normalized = normalized.dropLast(1).trimEnd()
        }
        return normalized
    }

    private fun unwrapSingleParenthesizedExpression(expressionText: String): String? {
        if (!expressionText.startsWith('(')) return null
        val closeParen = findMatchingDelimiter(expressionText, 0, '(', ')') ?: return null
        if (closeParen != expressionText.lastIndex) return null
        val inner = expressionText.substring(1, closeParen)
        if (containsTopLevelComma(inner)) return null
        return inner
    }

    private fun containsTopLevelComma(text: String): Boolean {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ',' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return true
            }
            index++
        }

        return false
    }

    private fun findTopLevelPipeOffset(text: String): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var lastPipeOffset: Int? = null
        var index = 0

        while (index + 1 < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '|' -> if (text[index + 1] == '>' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    lastPipeOffset = index
                }
            }
            index++
        }

        return lastPipeOffset
    }

    private fun findTopLevelChar(text: String, target: Char, fromIndex: Int = 0): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = fromIndex.coerceAtLeast(0)

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> {
                    if (target == '{' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
                    braceDepth++
                }
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                else -> if (ch == target && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
            }
            index++
        }

        return null
    }

    private fun findTopLevelArrow(text: String, fromIndex: Int = 0): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = fromIndex.coerceAtLeast(0)

        while (index + 1 < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '-' -> if (text[index + 1] == '>' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return index
                }
            }
            index++
        }

        return null
    }

    private fun findMatchingDelimiter(
        text: String,
        openIndex: Int,
        openChar: Char,
        closeChar: Char
    ): Int? {
        if (openIndex !in text.indices || text[openIndex] != openChar) return null

        var depth = 0
        var inString = false
        var inLineComment = false
        var index = openIndex

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }

        return null
    }

    private fun startsWithKeyword(text: String, keyword: String, startIndex: Int = 0): Boolean {
        val start = startIndex.coerceAtLeast(0)
        if (start >= text.length) return false
        if (!text.regionMatches(start, keyword, 0, keyword.length)) return false
        val beforeOk = start == 0 || !isIdentifierChar(text[start - 1])
        val afterIndex = start + keyword.length
        val afterOk = afterIndex >= text.length || !isIdentifierChar(text[afterIndex])
        return beforeOk && afterOk
    }

    private fun effectiveExpectedTypeContext(expectedType: String, currentValueText: String): ExpectedTypeContext? {
        var effectiveType = normalizeTypeText(expectedType)
        var spreadContext = false

        for (wrapper in openValueWrappers(currentValueText)) {
            when (wrapper) {
                ValueWrapperFrame.OPTION_SOME -> {
                    effectiveType = unwrapOptionType(effectiveType) ?: return null
                }
                is ValueWrapperFrame.LIST_LITERAL -> {
                    val listType = unwrapListType(effectiveType) ?: return null
                    if (!wrapper.currentSegmentIsSpread) {
                        effectiveType = listType
                    } else {
                        spreadContext = true
                    }
                }
            }
        }

        return ExpectedTypeContext(effectiveType, spreadContext)
    }

    private fun inferListLiteralContext(
        anchor: PsiElement,
        text: String,
        offset: Int
    ): InferredListLiteralContext? {
        if (text.isEmpty()) return null

        val frames = ArrayDeque<ListScanFrame>()
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            if (index == offset.coerceAtLeast(0)) {
                val frame = frames.lastOrNull() ?: return null
                val expectedListType = frame.expectedListType
                val elementType =
                    frame.inferredElementType
                        ?: inferListElementTypeFromSegment(
                            text.substring(frame.currentSegmentStart, offset).trim(),
                            anchor,
                            offset,
                            linkedSetOf()
                        )
                        ?: expectedListType?.let(::unwrapListType)
                        ?: return null
                return InferredListLiteralContext(
                    expectedListType = expectedListType ?: "List<$elementType>",
                    currentSegmentText = text.substring(frame.currentSegmentStart, offset).trim()
                )
            }

            when (ch) {
                '[' -> {
                    val expectedListType =
                        frames.lastOrNull()?.let(::nestedExpectedListTypeFromParent)
                            ?: expectedListTypeFromEnclosingBindingAnnotation(text, index, anchor)
                    frames.addLast(ListScanFrame(index + 1, expectedListType = expectedListType))
                }
                ',' -> {
                    val frame = frames.lastOrNull()
                    if (frame != null) {
                        inferListElementTypeFromSegment(
                            text.substring(frame.currentSegmentStart, index).trim(),
                            anchor,
                            offset,
                            linkedSetOf()
                        )?.let { inferredType ->
                            frame.inferredElementType = frame.inferredElementType ?: inferredType
                        }
                        frame.currentSegmentStart = index + 1
                    }
                }
                ']' -> {
                    val frame = frames.removeLastOrNull()
                    if (frame == null) {
                        index++
                        continue
                    }
                    inferListElementTypeFromSegment(
                        text.substring(frame.currentSegmentStart, index).trim(),
                        anchor,
                        offset,
                        linkedSetOf()
                    )?.let { inferredType ->
                        frame.inferredElementType = frame.inferredElementType ?: inferredType
                    }
                }
            }
            index++
        }

        if (offset == text.length && frames.isNotEmpty()) {
            val frame = frames.last()
            val expectedListType = frame.expectedListType
            val elementType =
                frame.inferredElementType
                    ?: inferListElementTypeFromSegment(
                        text.substring(frame.currentSegmentStart, offset).trim(),
                        anchor,
                        offset,
                        linkedSetOf()
                    )
                    ?: expectedListType?.let(::unwrapListType)
                    ?: return null
            return InferredListLiteralContext(
                expectedListType = expectedListType ?: "List<$elementType>",
                currentSegmentText = text.substring(frame.currentSegmentStart, offset).trim()
            )
        }

        return null
    }

    private fun expectedListTypeFromEnclosingBindingAnnotation(
        text: String,
        listStartOffset: Int,
        anchor: PsiElement
    ): String? {
        var index = skipWhitespaceBackward(text, listStartOffset - 1)
        if (index < 0 || text[index] != '=') return null

        index = skipWhitespaceBackward(text, index - 1)
        if (index < 0) return null

        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        while (index >= 0) {
            when (text[index]) {
                '>' -> angleDepth++
                '<' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                ')' -> parenDepth++
                '(' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                ']' -> bracketDepth++
                '[' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '}' -> braceDepth++
                '{' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
            }
            index--
        }

        index = skipWhitespaceBackward(text, index - 1)
        if (index < 0 || !isIdentifierChar(text[index])) return null

        val nameEnd = index + 1
        while (index >= 0 && isIdentifierChar(text[index])) index--
        val nameStart = index + 1
        val bindingName = text.substring(nameStart, nameEnd)
        if (bindingName.isBlank()) return null

        val declaredType = parseBindingTypeAt(text, nameStart, bindingName, anchor) ?: return null
        val normalizedType = normalizeTypeText(declaredType)
        return normalizedType.takeIf { unwrapListType(it) != null }
    }

    private fun nestedExpectedListTypeFromParent(parent: ListScanFrame): String? {
        parent.inferredElementType
            ?.let(::normalizeTypeText)
            ?.takeIf { unwrapListType(it) != null }
            ?.let { return it }

        val parentElementType = parent.expectedListType?.let(::unwrapListType) ?: return null
        val normalizedElementType = normalizeTypeText(parentElementType)
        return normalizedElementType.takeIf { unwrapListType(it) != null }
    }

    private fun collectVisibleTypedBindings(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>
    ): List<TypedBinding> {
        val file = anchor.containingFile ?: return emptyList()
        val document = PsiDocumentManager.getInstance(anchor.project).getDocument(file) ?: return emptyList()
        val caretOffset = anchor.textRange.startOffset
        val seen = LinkedHashSet<String>()
        val result = ArrayList<TypedBinding>()

        for (binding in AikenLocalScopeAnalyzer.collectVisibleBindings(anchor)) {
            if (binding.name in excludedNames || !seen.add(binding.name)) continue
            val declarationOffset = binding.declarationOffset
            if (isInsideOwnBindingInitializer(file.text, declarationOffset, binding.name, caretOffset)) continue
            val declaredType = parseBindingTypeAt(file.text, declarationOffset, binding.name, anchor) ?: continue
            if (matchesExpectedTypes(declaredType, expectedTypes)) {
                result += TypedBinding(binding.name, declaredType)
            }
        }

        return result
    }

    private fun isInsideOwnBindingInitializer(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        caretOffset: Int
    ): Boolean {
        if (caretOffset <= declarationOffset || declarationOffset < 0 || declarationOffset >= text.length) return false

        val nameEnd = declarationOffset + bindingName.length
        if (nameEnd > text.length) return false

        var index = skipWhitespace(text, nameEnd)
        if (index >= text.length) return false

        if (text[index] == ':') {
            index++
            var angleDepth = 0
            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            while (index < text.length) {
                when (text[index]) {
                    '<' -> angleDepth++
                    '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                    '(' -> parenDepth++
                    ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    '[' -> bracketDepth++
                    ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    '{' -> braceDepth++
                    '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    '=' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                }
                index++
            }
        }

        index = skipWhitespace(text, index)
        if (index >= text.length || text[index] != '=') return false
        index++
        index = skipWhitespace(text, index)
        if (caretOffset <= index) return false

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var scan = index

        while (scan < caretOffset && scan < text.length) {
            val ch = text[scan]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                scan++
                continue
            }

            if (inString) {
                if (ch == '\\' && scan + 1 < text.length) {
                    scan += 2
                    continue
                }
                if (ch == '"') inString = false
                scan++
                continue
            }

            if (ch == '/' && scan + 1 < caretOffset && text[scan + 1] == '/') {
                inLineComment = true
                scan += 2
                continue
            }

            if (ch == '"') {
                inString = true
                scan++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return false
            }
            scan++
        }

        return true
    }

    private fun collectVisibleTypedConsts(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>
    ): List<TypedConstSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<TypedConstSuggestion>()

        for (entry in AikenConstTypeExtractor.extractDeclarations(file.text)) {
            if (entry.name in excludedNames || !seen.add(entry.name)) continue
            val constType = parseBindingTypeAt(file.text, entry.offset, entry.name, anchor) ?: continue
            if (matchesExpectedTypes(constType, expectedTypes)) {
                result += TypedConstSuggestion(entry.name, constType, LOCAL_TYPED_CONST_PRIORITY)
            }
        }

        val importedNames =
            useModel.importedNames().filter { importedName ->
                importedName.kind != com.medusalabs.aiken.imports.AikenImportedNameKind.MODULE_ALIAS
            }

        for (importedName in importedNames) {
            val constType = findImportedConstType(anchor, importedName.statement.modulePath, importedName.sourceName) ?: continue
            if (!matchesExpectedTypes(constType, expectedTypes)) continue
            if (importedName.exposedName in excludedNames || !seen.add(importedName.exposedName)) continue
            result += TypedConstSuggestion(importedName.exposedName, constType, IMPORTED_TYPED_CONST_PRIORITY)
        }

        return result
    }

    private fun collectVisibleTypedFunctions(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>
    ): List<VisibleFunctionSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<VisibleFunctionSuggestion>()

        for ((functionName, signature) in com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extract(file.text)) {
            if (functionName in excludedNames || !seen.add(functionName)) continue
            val returnType = aikenFunctionSignatureReturnType(signature) ?: continue
            if (!matchesExpectedTypes(returnType, expectedTypes)) continue
            result += VisibleFunctionSuggestion(functionName, signature, LOCAL_TYPED_FUNCTION_PRIORITY)
        }

        if (!DumbService.isDumb(anchor.project)) {
            val index = FileBasedIndex.getInstance()
            val scope = AikenSearchScopes.forElement(anchor)
            for (importedName in useModel.importedNames()) {
                if (importedName.exposedName in excludedNames || !seen.add(importedName.exposedName)) continue
                val signature =
                    index
                        .getValues(
                            AIKEN_FUNCTION_SIGNATURE_INDEX_NAME,
                            aikenFunctionSignatureModuleKey(importedName.statement.modulePath, importedName.sourceName),
                            scope
                        )
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                if (signature.isEmpty()) continue
                val returnType = aikenFunctionSignatureReturnType(signature) ?: continue
                if (!matchesExpectedTypes(returnType, expectedTypes)) continue
                result += VisibleFunctionSuggestion(importedName.exposedName, signature, IMPORTED_TYPED_FUNCTION_PRIORITY)
            }
        }

        return result
    }

    private fun collectVisiblePipeFunctions(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>
    ): List<PipeFunctionSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<PipeFunctionSuggestion>()

        for ((functionName, signature) in com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extract(file.text)) {
            if (functionName in excludedNames || !seen.add(functionName)) continue
            if (!matchesPipeInputTypes(signature, expectedTypes)) continue
            result += PipeFunctionSuggestion(functionName, functionName, signature, LOCAL_TYPED_FUNCTION_PRIORITY)
        }

        if (!DumbService.isDumb(anchor.project)) {
            val index = FileBasedIndex.getInstance()
            val scope = AikenSearchScopes.forElement(anchor)
            for (importedName in useModel.importedNames()) {
                if (importedName.kind == com.medusalabs.aiken.imports.AikenImportedNameKind.MODULE_ALIAS) continue
                if (importedName.exposedName in excludedNames || !seen.add(importedName.exposedName)) continue
                val signature =
                    index
                        .getValues(
                            AIKEN_FUNCTION_SIGNATURE_INDEX_NAME,
                            aikenFunctionSignatureModuleKey(importedName.statement.modulePath, importedName.sourceName),
                            scope
                        )
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                if (signature.isEmpty() || !matchesPipeInputTypes(signature, expectedTypes)) continue
                result += PipeFunctionSuggestion(importedName.exposedName, importedName.exposedName, signature, IMPORTED_TYPED_FUNCTION_PRIORITY)
            }
        }

        return result
    }

    private fun collectQualifiedPipeFunctions(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        qualifier: String?,
        excludedNames: Set<String>
    ): List<PipeFunctionSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val qualifiersToModules = LinkedHashMap<String, String>()

        for (statement in useModel.statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isBlank()) continue

            val exposedQualifier =
                statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: if (statement.items.isEmpty()) modulePath.substringAfterLast('/').trim() else null
            if (exposedQualifier.isNullOrBlank()) continue
            if (qualifier != null && exposedQualifier != qualifier) continue
            qualifiersToModules.putIfAbsent(exposedQualifier, modulePath)
        }

        val seen = LinkedHashSet<String>()
        val result = ArrayList<PipeFunctionSuggestion>()
        for ((exposedQualifier, modulePath) in qualifiersToModules) {
            val exportedSymbols = exportedSymbols(anchor, modulePath)
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                val moduleText = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
                for (entry in com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extractEntries(moduleText)) {
                    if (entry.name !in exportedSymbols || entry.name in excludedNames) continue
                    if (!matchesPipeInputTypes(entry.signature, expectedTypes)) continue
                    val lookupText = if (qualifier == null) "$exposedQualifier.${entry.name}" else entry.name
                    if (!seen.add(lookupText)) continue
                    result += PipeFunctionSuggestion(lookupText, entry.name, entry.signature, QUALIFIED_PIPE_FUNCTION_PRIORITY)
                }
            }
        }

        return result
    }

    private fun collectVisibleConstructibles(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>
    ): List<ConstructibleSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val directlyAvailableImportedNames =
            useModel.importedNames()
                .groupBy { it.statement.modulePath.trim() }
                .mapValues { (_, names) ->
                    names.mapTo(LinkedHashSet()) { it.exposedName.trim() }.filter { it.isNotBlank() }
                }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<ConstructibleSuggestion>()

        for (entry in AikenConstructibleExtractor.extract(file.text)) {
            if (entry.ownerName in excludedNames) continue
            if (matchesExpectedTypes(entry.resultTypeName, expectedTypes) && seen.add(entry.ownerName)) {
                result +=
                    ConstructibleSuggestion(
                        name = entry.ownerName,
                        resultType = entry.resultTypeName,
                        fields = entry.fields,
                        supportsNamedSyntax = entry.supportsNamedSyntax
                    )
            }
        }

        val importedModules =
            useModel.statements
                .mapTo(LinkedHashSet()) { it.modulePath }
                .filter { it.isNotBlank() && it != currentModulePath }
        if (importedModules.isEmpty()) return result

        for (modulePath in importedModules) {
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                for (entry in AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))) {
                    if (entry.ownerName in excludedNames) continue
                    if (matchesExpectedTypes(entry.resultTypeName, expectedTypes) && seen.add(entry.ownerName)) {
                        result +=
                            ConstructibleSuggestion(
                                name = entry.ownerName,
                                resultType = entry.resultTypeName,
                                fields = entry.fields,
                                supportsNamedSyntax = entry.supportsNamedSyntax,
                                modulePath = modulePath,
                                needsImport = entry.ownerName !in directlyAvailableImportedNames[modulePath].orEmpty()
                            )
                    }
                }
            }
        }

        if (DumbService.isDumb(anchor.project)) return result

        val index = FileBasedIndex.getInstance()
        val scope = AikenSearchScopes.forElement(anchor)
        for (modulePath in importedModules) {
            for (value in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, modulePath, scope)) {
                for (entry in decodeAikenConstructibleIndexValue(value)) {
                    if (entry.ownerName in excludedNames) continue
                    if (matchesExpectedTypes(entry.resultTypeName, expectedTypes) && seen.add(entry.ownerName)) {
                        result +=
                            ConstructibleSuggestion(
                                name = entry.ownerName,
                                resultType = entry.resultTypeName,
                                fields = entry.fields,
                                supportsNamedSyntax = entry.supportsNamedSyntax,
                                modulePath = modulePath,
                                needsImport = entry.ownerName !in directlyAvailableImportedNames[modulePath].orEmpty()
                            )
                    }
                }
            }
        }

        return result
    }

    private fun collectUnimportedFunctionsReturning(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>,
        alreadySeenNames: Set<String>
    ): List<UnimportedFunctionSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val project = anchor.project
        if (DumbService.isDumb(project)) return emptyList()

        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedSymbolsByModule =
            useModel.statements.associate { statement ->
                statement.modulePath.trim() to
                    statement.items
                        .mapTo(LinkedHashSet()) { item -> item.name.trim() }
                        .filter { it.isNotBlank() }
            }
        val index = FileBasedIndex.getInstance()
        val scope = AikenSearchScopes.forElement(anchor)
        val seenModulesAndNames = LinkedHashSet<Pair<String, String>>()
        val result = ArrayList<UnimportedFunctionSuggestion>()

        for (expectedType in expectedTypes) {
            for (encodedValue in index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, aikenFunctionSignatureReturnTypeKey(expectedType), scope)) {
                for (entry in decodeAikenFunctionReturnTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.functionName in excludedNames || entry.functionName in alreadySeenNames) continue
                    if (entry.functionName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.functionName)) continue
                    result += UnimportedFunctionSuggestion(entry.functionName, entry.modulePath, entry.signature)
                }
            }
        }

        return result
    }

    private fun collectUnimportedPipeFunctions(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>,
        alreadySeenNames: Set<String>
    ): List<UnimportedFunctionSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val root = com.medusalabs.aiken.project.AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedModulesWithQualifiedAccess =
            useModel.statements
                .filter { statement -> statement.items.isEmpty() || !statement.moduleAlias.isNullOrBlank() }
                .mapTo(LinkedHashSet()) { it.modulePath.trim() }
        val importedSymbolsByModule =
            useModel.statements.associate { statement ->
                statement.modulePath.trim() to
                    statement.items
                        .mapTo(LinkedHashSet()) { item -> item.name.trim() }
                        .filter { it.isNotBlank() }
            }
        val seenModulesAndNames = LinkedHashSet<Pair<String, String>>()
        val result = ArrayList<UnimportedFunctionSuggestion>()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath == currentModulePath || modulePath in importedModulesWithQualifiedAccess) continue

            val moduleText = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            val exportedSymbols = AikenPublicExportExtractor.extract(moduleText).toSet()
            for (entry in com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extractEntries(moduleText)) {
                if (entry.name !in exportedSymbols) continue
                if (entry.name in excludedNames || entry.name in alreadySeenNames) continue
                if (entry.name in importedSymbolsByModule[modulePath].orEmpty()) continue
                if (!matchesPipeInputTypes(entry.signature, expectedTypes)) continue
                if (!seenModulesAndNames.add(modulePath to entry.name)) continue
                result += UnimportedFunctionSuggestion(entry.name, modulePath, entry.signature)
            }
        }

        return result
    }

    private fun collectUnimportedConstsReturning(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>,
        alreadySeenNames: Set<String>
    ): List<UnimportedConstSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val project = anchor.project
        if (DumbService.isDumb(project)) return emptyList()

        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedSymbolsByModule =
            useModel.statements.associate { statement ->
                statement.modulePath.trim() to
                    statement.items
                        .mapTo(LinkedHashSet()) { item -> item.name.trim() }
                        .filter { it.isNotBlank() }
            }
        val index = FileBasedIndex.getInstance()
        val scope = AikenSearchScopes.forElement(anchor)
        val seenModulesAndNames = LinkedHashSet<Pair<String, String>>()
        val result = ArrayList<UnimportedConstSuggestion>()

        for (expectedType in expectedTypes) {
            for (encodedValue in index.getValues(AIKEN_CONST_TYPE_INDEX_NAME, aikenConstTypeTypeKey(expectedType), scope)) {
                for (entry in decodeAikenConstTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.constName in excludedNames || entry.constName in alreadySeenNames) continue
                    if (entry.constName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.constName)) continue
                    result += UnimportedConstSuggestion(entry.constName, entry.modulePath, entry.type)
                }
            }
        }

        val importedModules = importedSymbolsByModule.keys.filter { it.isNotBlank() && it != currentModulePath }
        for (modulePath in importedModules) {
            val exportedSymbols = exportedSymbols(anchor, modulePath)
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                for (entry in AikenConstTypeExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))) {
                    if (entry.name !in exportedSymbols) continue
                    if (entry.name in excludedNames || entry.name in alreadySeenNames) continue
                    if (entry.name in importedSymbolsByModule[modulePath].orEmpty()) continue
                    if (!matchesExpectedTypes(entry.type, expectedTypes)) continue
                    if (!seenModulesAndNames.add(modulePath to entry.name)) continue
                    result += UnimportedConstSuggestion(entry.name, modulePath, entry.type)
                }
            }
        }

        return result
    }

    private fun collectUnimportedConstructiblesReturning(
        anchor: PsiElement,
        expectedTypes: Set<String>,
        excludedNames: Set<String>,
        alreadySeenNames: Set<String>
    ): List<ConstructibleSuggestion> {
        val file = anchor.containingFile ?: return emptyList()
        val project = anchor.project
        if (DumbService.isDumb(project)) return emptyList()

        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedSymbolsByModule =
            useModel.statements.associate { statement ->
                statement.modulePath.trim() to
                    statement.items
                        .mapTo(LinkedHashSet()) { item -> item.name.trim() }
                        .filter { it.isNotBlank() }
            }
        val index = FileBasedIndex.getInstance()
        val scope = AikenSearchScopes.forElement(anchor)
        val seenModulesAndNames = LinkedHashSet<Pair<String, String>>()
        val result = ArrayList<ConstructibleSuggestion>()

        for (expectedType in expectedTypes) {
            for (encodedValue in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, aikenConstructibleResultTypeKey(expectedType), scope)) {
                for (entry in decodeAikenConstructibleReturnTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.ownerName in excludedNames || entry.ownerName in alreadySeenNames) continue
                    if (entry.ownerName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.ownerName)) continue
                    val detailedEntry =
                        AikenModuleFiles
                            .findFilesForModulePath(file.virtualFile, entry.modulePath)
                            .asSequence()
                            .flatMap { moduleFile ->
                                AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8)).asSequence()
                            }
                            .firstOrNull { constructible ->
                                constructible.ownerName == entry.ownerName &&
                                    normalizeTypeText(constructible.resultTypeName) == normalizeTypeText(entry.resultTypeName)
                            }
                    result +=
                        ConstructibleSuggestion(
                            name = entry.ownerName,
                            resultType = entry.resultTypeName,
                            fields = detailedEntry?.fields.orEmpty(),
                            supportsNamedSyntax = detailedEntry?.supportsNamedSyntax == true,
                            modulePath = entry.modulePath,
                            needsImport = true
                        )
                }
            }
        }

        return result
    }

    private fun exportedSymbols(anchor: PsiElement, modulePath: String): Set<String> {
        val file = anchor.containingFile ?: return emptySet()
        if (!DumbService.isDumb(anchor.project)) {
            val names = LinkedHashSet<String>()
            val index = FileBasedIndex.getInstance()
            val scope = AikenSearchScopes.forElement(anchor)
            for (value in index.getValues(AIKEN_EXPORT_INDEX_NAME, modulePath, scope)) {
                names += decodeAikenExportIndexValue(value)
            }
            if (names.isNotEmpty()) {
                return names
            }
        }

        for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
            val exports = AikenPublicExportExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))
            if (exports.isNotEmpty()) {
                return exports.toSet()
            }
        }

        return emptySet()
    }

    private fun findImportedConstType(
        anchor: PsiElement,
        modulePath: String,
        constName: String
    ): String? {
        if (!DumbService.isDumb(anchor.project)) {
            val index = FileBasedIndex.getInstance()
            val scope = AikenSearchScopes.forElement(anchor)
            index.getValues(AIKEN_CONST_TYPE_INDEX_NAME, aikenConstTypeModuleKey(modulePath, constName), scope)
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        for (moduleFile in AikenModuleFiles.findFilesForModulePath(anchor.containingFile?.virtualFile, modulePath)) {
            val entry = AikenConstTypeExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8)).firstOrNull { it.name == constName }
            if (entry != null) return entry.type
        }

        return null
    }

    private fun parseBindingTypeAt(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        anchor: PsiElement,
        visitedDeclarationOffsets: MutableSet<Int> = linkedSetOf()
    ): String? {
        if (!visitedDeclarationOffsets.add(declarationOffset)) return null
        val nameEnd = declarationOffset + bindingName.length
        if (nameEnd > text.length) return null

        var index = skipWhitespace(text, nameEnd)
        if (index >= text.length) return null

        if (text[index] == ':') {
            index++
            index = skipWhitespace(text, index)

            val typeStart = index
            var angleDepth = 0
            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0

            while (index < text.length) {
                when (text[index]) {
                    '<' -> angleDepth++
                    '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                    '(' -> parenDepth++
                    ')' -> {
                        if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                        parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    }
                    '[' -> bracketDepth++
                    ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    '{' -> {
                        if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                        braceDepth++
                    }
                    '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    ',', '=', '\n', '\r' -> {
                        if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                    }
                }
                index++
            }

            return text.substring(typeStart, index).trim().takeIf { it.isNotEmpty() }
        }

        if (text[index] != '=') return null
        index++
        index = skipWhitespace(text, index)
        if (index >= text.length) return null
        val expressionEnd = consumeExpressionEnd(text, index)
        val expressionText = text.substring(index, expressionEnd.coerceAtMost(text.length))
        return inferExpressionTypeInternal(expressionText, anchor, expressionEnd, visitedDeclarationOffsets)
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun skipWhitespaceBackward(text: String, start: Int): Int {
        var index = start
        while (index >= 0 && text[index].isWhitespace()) index--
        return index
    }

    private fun readQualifiedIdentifierRange(text: String, start: Int): IntRange? {
        var index = start
        if (index >= text.length || !isIdentifierChar(text[index])) return null
        index++
        while (index < text.length && (isIdentifierChar(text[index]) || text[index] == '.')) {
            index++
        }
        return start until index
    }

    private fun isTokenBoundary(text: String, index: Int): Boolean =
        index >= text.length || (!text[index].isLetterOrDigit() && text[index] != '_')

    private fun inferListLiteralType(
        text: String,
        listStart: Int,
        anchor: PsiElement,
        referenceOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        var index = listStart + 1
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 1
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var segmentStart = index

        fun currentSegment(): String = text.substring(segmentStart, index).trim()

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> {
                    bracketDepth--
                    if (bracketDepth == 0) {
                        inferListElementTypeFromSegment(currentSegment(), anchor, referenceOffset, visitedDeclarationOffsets)
                            ?.let { return "List<$it>" }
                        break
                    }
                }
                ',' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 1 && braceDepth == 0) {
                        inferListElementTypeFromSegment(currentSegment(), anchor, referenceOffset, visitedDeclarationOffsets)
                            ?.let { return "List<$it>" }
                        segmentStart = index + 1
                    }
                }
            }
            index++
        }

        return null
    }

    private fun inferListElementTypeFromSegment(
        segmentText: String,
        anchor: PsiElement,
        declarationOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        if (segmentText.isBlank()) return null
        val effectiveSegment = segmentText.removePrefix("..").trimStart()
        if (effectiveSegment.isBlank()) return null
        val resolvedType =
            inferExpressionTypeInternal(effectiveSegment, anchor, declarationOffset, visitedDeclarationOffsets)
        val normalizedType = normalizeTypeText(resolvedType.orEmpty())
        if (segmentText.trimStart().startsWith("..")) {
            return unwrapListType(normalizedType)
        }
        return normalizedType.takeIf { it.isNotEmpty() }
    }

    private fun resolveVisibleBindingType(
        anchor: PsiElement,
        symbolText: String,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        if (symbolText.contains('.')) return null
        val file = anchor.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(anchor.project).getDocument(file) ?: return null
        val declarationOffset =
            AikenLocalScopeAnalyzer.findDeclarationOffset(document, symbolText, (beforeOffset - 1).coerceAtLeast(0))
                ?: return null
        return parseBindingTypeAt(file.text, declarationOffset, symbolText, anchor, visitedDeclarationOffsets)
    }

    private fun resolveConstType(anchor: PsiElement, symbolText: String): String? {
        val file = anchor.containingFile ?: return null
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val qualifier = symbolText.substringBeforeLast('.', "")
        val name = symbolText.substringAfterLast('.')

        AikenConstTypeExtractor.extractDeclarations(file.text).firstOrNull { it.name == name }
            ?.let { return parseBindingTypeAt(file.text, it.offset, it.name, anchor) }

        val importedTargets = useModel.resolveSymbolTargets(name, qualifier.ifBlank { null })
        for (target in importedTargets) {
            findImportedConstType(anchor, target.modulePath, target.symbolName)?.let { return it }
        }

        return null
    }

    private fun resolveFunctionReturnType(anchor: PsiElement, symbolText: String): String? {
        val file = anchor.containingFile ?: return null
        val qualifier = symbolText.substringBeforeLast('.', "")
        val name = symbolText.substringAfterLast('.')
        val signatures = com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extract(file.text)
        if (qualifier.isBlank()) {
            aikenFunctionSignatureReturnType(signatures[name].orEmpty())?.let { return it }
        }

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedTargets = useModel.resolveCallableTargets(name, qualifier.ifBlank { null })
        if (importedTargets.isEmpty()) return null

        val index = FileBasedIndex.getInstance()
        val scope = AikenSearchScopes.forElement(anchor)
        for (target in importedTargets) {
            index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, aikenFunctionSignatureModuleKey(target.modulePath, target.symbolName), scope)
                .firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { signature ->
                    aikenFunctionSignatureReturnType(signature)?.let { return it }
                }
        }

        return null
    }

    private fun resolveConstructibleResultType(anchor: PsiElement, symbolText: String): String? {
        val file = anchor.containingFile ?: return null
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val qualifier = symbolText.substringBeforeLast('.', "")
        val name = symbolText.substringAfterLast('.')

        fun matchingResultType(modulePathFilter: Set<String>?): String? {
            AikenConstructibleExtractor.extract(file.text)
                .firstOrNull { entry -> entry.ownerName == name && (modulePathFilter == null) }
                ?.let { return it.resultTypeName }

            val importedModules =
                if (modulePathFilter != null) modulePathFilter
                else useModel.statements.mapTo(LinkedHashSet()) { it.modulePath.trim() }.filter { it.isNotBlank() }.toSet()

            for (modulePath in importedModules) {
                for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                    AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))
                        .firstOrNull { entry -> entry.ownerName == name }
                        ?.let { return it.resultTypeName }
                }
            }

            return null
        }

        if (qualifier.isBlank()) {
            return matchingResultType(null)
        }

        val modulePaths =
            useModel.resolveModuleTargets(qualifier).mapTo(LinkedHashSet()) { it.modulePath }.ifEmpty {
                linkedSetOf(qualifier)
            }
        return matchingResultType(modulePaths)
    }

    private fun unwrapListType(typeText: String): String? = unwrapSingleGenericType(typeText, "List")

    private fun unwrapOptionType(typeText: String): String? = unwrapSingleGenericType(typeText, "Option")

    private fun unwrapSingleGenericType(typeText: String, containerName: String): String? {
        val normalized = normalizeTypeText(typeText)
        if (!normalized.startsWith("$containerName<")) return null

        val openIndex = normalized.indexOf('<')
        if (openIndex <= 0) return null

        var depth = 0
        var innerStart = -1
        for (index in openIndex until normalized.length) {
            when (normalized[index]) {
                '<' -> {
                    if (depth == 0) innerStart = index + 1
                    depth++
                }
                '>' -> {
                    depth--
                    if (depth == 0) {
                        if (index != normalized.lastIndex || innerStart <= 0) return null
                        return normalized.substring(innerStart, index).trim().takeIf { it.isNotEmpty() }
                    }
                }
            }
        }

        return null
    }

    private fun builtInInvariantCandidates(expectedTypes: Set<String>): List<Pair<String, CompletionSymbolKind>> =
        when {
            "Bool" in expectedTypes -> listOf("True" to CompletionSymbolKind.KEYWORD, "False" to CompletionSymbolKind.KEYWORD)
            expectedTypes.any { unwrapOptionType(it) != null } -> listOf("None" to CompletionSymbolKind.KEYWORD)
            else -> emptyList()
        }

    private fun createListLiteralLookup(expectedType: String): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create("[]")
                .withIcon(AllIcons.Nodes.Type)
                .withTypeText(expectedType, true)
                .withInsertHandler { insertionContext, _ ->
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, "[]")
                    insertionContext.editor.caretModel.moveToOffset(insertedOffset + 1)
                    insertionContext.commitDocument()
                    AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                },
            LIST_LITERAL_PRIORITY
        )

    private fun createOptionSomeLookup(expectedType: String): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create("Some()")
                .withIcon(AllIcons.Nodes.Type)
                .withTypeText(expectedType, true)
                .withInsertHandler { insertionContext, _ ->
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, "Some()")
                    insertionContext.editor.caretModel.moveToOffset(insertedOffset + "Some(".length)
                    insertionContext.commitDocument()
                    AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                },
            OPTION_SOME_PRIORITY
        )

    private fun createAutoImportedFunctionLookup(
        anchor: PsiElement,
        suggestion: UnimportedFunctionSuggestion,
        expectedType: String
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(suggestion.name)
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText(expectedType, true)
                .withTailText(" from ${suggestion.modulePath}", true)
                .withInsertHandler { insertionContext, _ ->
                    val callTemplate = functionCallTemplate(suggestion)
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                    callTemplate.caretOffset?.let { caretOffset ->
                        insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                    }
                    insertionContext.commitDocument()
                    val previousLaterRunnable = insertionContext.laterRunnable
                    insertionContext.setLaterRunnable {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneUseImport(
                                insertionContext.document.charsSequence.toString(),
                                insertionContext.document,
                                suggestion.modulePath,
                                suggestion.name
                            )
                            insertionContext.commitDocument()
                        }
                        if (callTemplate.shouldTriggerAutoPopup) {
                            AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                        }
                    }
                },
            UNIMPORTED_FUNCTION_PRIORITY
        )

    private fun createAutoImportedPipeFunctionLookup(
        anchor: PsiElement,
        suggestion: UnimportedFunctionSuggestion
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(suggestion.name)
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText(aikenFunctionSignatureReturnType(suggestion.signature), true)
                .withTailText(" from ${suggestion.modulePath}", true)
                .withInsertHandler { insertionContext, _ ->
                    val callTemplate = pipeFunctionCallTemplate(suggestion.name, suggestion.signature)
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                    callTemplate.caretOffset?.let { caretOffset ->
                        insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                    }
                    insertionContext.commitDocument()
                    val previousLaterRunnable = insertionContext.laterRunnable
                    insertionContext.setLaterRunnable {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneUseImport(
                                insertionContext.document.charsSequence.toString(),
                                insertionContext.document,
                                suggestion.modulePath,
                                suggestion.name
                            )
                            insertionContext.commitDocument()
                        }
                        if (callTemplate.shouldTriggerAutoPopup) {
                            AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                        }
                    }
                },
            UNIMPORTED_FUNCTION_PRIORITY
        )

    private fun createVisibleFunctionLookup(
        suggestion: VisibleFunctionSuggestion,
        expectedType: String
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(suggestion.name)
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText(expectedType, true)
                .withInsertHandler { insertionContext, _ ->
                    val callTemplate = functionCallTemplate(UnimportedFunctionSuggestion(suggestion.name, "", suggestion.signature))
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                    callTemplate.caretOffset?.let { caretOffset ->
                        insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                    }
                    insertionContext.commitDocument()
                    if (callTemplate.shouldTriggerAutoPopup) {
                        AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                    }
                },
            suggestion.priority
        )

    private fun createVisiblePipeFunctionLookup(
        lookupText: String,
        signature: String,
        priority: Double
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(lookupText)
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText(aikenFunctionSignatureReturnType(signature), true)
                .withInsertHandler { insertionContext, _ ->
                    val callTemplate = pipeFunctionCallTemplate(lookupText, signature)
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                    callTemplate.caretOffset?.let { caretOffset ->
                        insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                    }
                    insertionContext.commitDocument()
                    if (callTemplate.shouldTriggerAutoPopup) {
                        AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                    }
                },
            priority
        )

    private fun createQualifiedPipeVisibleFunctionLookup(
        lookupText: String,
        matchingName: String,
        signature: String,
        priority: Double
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(lookupText)
                .withLookupStrings(setOf(matchingName))
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText(aikenFunctionSignatureReturnType(signature), true)
                .withInsertHandler { insertionContext, _ ->
                    val callTemplate = pipeFunctionCallTemplate(lookupText, signature)
                    val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                    callTemplate.caretOffset?.let { caretOffset ->
                        insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                    }
                    insertionContext.commitDocument()
                    if (callTemplate.shouldTriggerAutoPopup) {
                        AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
                    }
                },
            priority
        )

    private fun createAutoImportedConstLookup(
        anchor: PsiElement,
        suggestion: UnimportedConstSuggestion,
        expectedType: String
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(suggestion.name)
                .withIcon(AllIcons.Nodes.Variable)
                .withTypeText(expectedType, true)
                .withTailText(" from ${suggestion.modulePath}", true)
                .withInsertHandler { insertionContext, _ ->
                    replaceCurrentIdentifierPrefix(insertionContext, suggestion.name)
                    insertionContext.commitDocument()
                    val previousLaterRunnable = insertionContext.laterRunnable
                    insertionContext.setLaterRunnable {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneUseImport(
                                insertionContext.document.charsSequence.toString(),
                                insertionContext.document,
                                suggestion.modulePath,
                                suggestion.name
                            )
                            insertionContext.commitDocument()
                        }
                    }
                },
            UNIMPORTED_CONST_PRIORITY
        )

    private fun createAutoImportedSpreadConstLookup(
        anchor: PsiElement,
        suggestion: UnimportedConstSuggestion,
        expectedType: String
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(suggestion.name)
                .withIcon(AllIcons.Nodes.Variable)
                .withTypeText(expectedType, true)
                .withTailText(" from ${suggestion.modulePath}", true)
                .withInsertHandler { insertionContext, _ ->
                    normalizeSpreadInsertion(insertionContext, suggestion.name)
                    insertionContext.commitDocument()
                    val previousLaterRunnable = insertionContext.laterRunnable
                    insertionContext.setLaterRunnable {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneUseImport(
                                insertionContext.document.charsSequence.toString(),
                                insertionContext.document,
                                suggestion.modulePath,
                                suggestion.name
                            )
                            insertionContext.commitDocument()
                        }
                    }
                },
            UNIMPORTED_CONST_PRIORITY
        )

    private fun createTypeDirectedLookup(
        text: String,
        kind: CompletionSymbolKind,
        priority: Double,
        typeText: String
    ): LookupElement {
        val builder =
            LookupElementBuilder
                .create(text)
                .withIcon(iconFor(kind))
                .withTypeText(typeText, true)
                .withBoldness(kind == CompletionSymbolKind.KEYWORD)
                .withInsertHandler { insertionContext, _ ->
                    replaceCurrentIdentifierPrefix(insertionContext, text)
                    insertionContext.commitDocument()
                }

        return PrioritizedLookupElement.withPriority(builder, priority)
    }

    private fun createSpreadLookup(
        text: String,
        kind: CompletionSymbolKind,
        priority: Double,
        typeText: String
    ): LookupElement {
        val builder =
            LookupElementBuilder
                .create(text)
                .withIcon(iconFor(kind))
                .withTypeText(typeText, true)
                .withBoldness(kind == CompletionSymbolKind.KEYWORD)
                .withInsertHandler { insertionContext, _ ->
                    normalizeSpreadInsertion(insertionContext, text)
                    insertionContext.commitDocument()
                }

        return PrioritizedLookupElement.withPriority(builder, priority)
    }

    private fun openValueWrappers(text: String): List<ValueWrapperFrame> {
        val frames = ArrayDeque<Frame>()
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            if (text.startsWith("Some(", index) && isStandaloneSomeCall(text, index)) {
                frames.addLast(Frame.OptionSome)
                index += "Some(".length
                continue
            }

            when (ch) {
                '(' -> frames.addLast(Frame.OtherParen)
                ')' -> {
                    when (frames.lastOrNull()) {
                        Frame.OptionSome -> frames.removeLast()
                        Frame.OtherParen -> frames.removeLast()
                        else -> Unit
                    }
                }
                '[' -> frames.addLast(Frame.ListLiteral(index + 1))
                ']' -> {
                    when (frames.lastOrNull()) {
                        is Frame.ListLiteral -> frames.removeLast()
                        else -> Unit
                    }
                }
                '{' -> frames.addLast(Frame.OtherBrace)
                '}' -> if (frames.lastOrNull() == Frame.OtherBrace) frames.removeLast()
                ',' -> {
                    val top = frames.lastOrNull()
                    if (top is Frame.ListLiteral) {
                        top.currentSegmentStart = index + 1
                    }
                }
            }
            index++
        }

        val wrappers = ArrayDeque<ValueWrapperFrame>()
        for (frame in frames) {
            when (frame) {
                Frame.OptionSome -> wrappers.addLast(ValueWrapperFrame.OPTION_SOME)
                is Frame.ListLiteral -> wrappers.addLast(
                    ValueWrapperFrame.LIST_LITERAL(
                        currentSegmentIsSpread = text.substring(frame.currentSegmentStart).trimStart().startsWith("..")
                    )
                )
                else -> Unit
            }
        }

        return wrappers.toList()
    }

    private fun isStandaloneSomeCall(text: String, startIndex: Int): Boolean {
        if (startIndex > 0 && (text[startIndex - 1].isLetterOrDigit() || text[startIndex - 1] == '_')) {
            return false
        }
        val endIndex = startIndex + "Some".length
        return endIndex < text.length && text[endIndex] == '('
    }

    private fun replaceCurrentIdentifierPrefix(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        replacementText: String
    ): Int {
        val document = insertionContext.document
        val chars = document.charsSequence
        var replaceStart = insertionContext.startOffset.coerceIn(0, chars.length)
        while (replaceStart > 0 && isIdentifierChar(chars[replaceStart - 1])) {
            replaceStart--
        }
        document.replaceString(replaceStart, insertionContext.tailOffset, replacementText)
        return replaceStart
    }

    private fun normalizeSpreadInsertion(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        insertedText: String
    ) {
        val document = insertionContext.document
        val text = document.charsSequence
        val insertedStart = insertionContext.startOffset.coerceIn(0, text.length)
        val insertedEnd = insertionContext.tailOffset.coerceIn(insertedStart, text.length)
        if (insertedEnd <= insertedStart) return

        var normalizedStart = insertedStart
        while (normalizedStart > 0 && isIdentifierChar(text[normalizedStart - 1])) {
            normalizedStart--
        }
        if (normalizedStart < insertedStart) {
            document.replaceString(normalizedStart, insertedEnd, insertedText)
        } else {
            val insertedRangeEnd = (insertedStart + insertedText.length).coerceAtMost(document.textLength)
            if (insertedRangeEnd <= insertedStart || document.getText(TextRange(insertedStart, insertedRangeEnd)) != insertedText) {
                normalizedStart = replaceCurrentIdentifierPrefix(insertionContext, insertedText)
            }
        }

        val refreshedText = document.charsSequence
        val normalizedEnd = (normalizedStart + insertedText.length).coerceAtMost(document.textLength)
        val duplicateEnd = normalizedEnd + insertedText.length
        if (
            duplicateEnd <= document.textLength &&
            document.getText(TextRange(normalizedEnd, duplicateEnd)) == insertedText &&
            isCompletionBoundary(refreshedText.getOrNull(duplicateEnd))
        ) {
            document.deleteString(normalizedEnd, duplicateEnd)
            insertionContext.editor.caretModel.moveToOffset(normalizedEnd)
            return
        }

        var nextIndex = normalizedEnd
        while (nextIndex < refreshedText.length && refreshedText[nextIndex].isWhitespace()) {
            nextIndex++
        }
        val nextChar = refreshedText.getOrNull(nextIndex)
        if (nextChar != null && !isCompletionBoundary(nextChar)) {
            document.insertString(normalizedEnd, ", ")
        }
        insertionContext.editor.caretModel.moveToOffset(normalizedEnd)
    }

    private fun isCompletionBoundary(char: Char?): Boolean =
        char == null || char.isWhitespace() || char == ',' || char == ']' || char == ')' || char == '}'

    private fun functionCallTemplate(suggestion: UnimportedFunctionSuggestion): FunctionCallTemplate {
        val parameterNames = parseSignatureParameterNames(suggestion.signature)
        if (parameterNames.isEmpty()) {
            return FunctionCallTemplate(
                text = "${suggestion.name}()",
                caretOffset = null,
                shouldTriggerAutoPopup = false
            )
        }

        val text =
            buildString {
                append(suggestion.name)
                append('(')
                append(
                    parameterNames.joinToString(", ") { parameterName ->
                        "$parameterName: "
                    }
                )
                append(')')
            }

        return FunctionCallTemplate(
            text = text,
            caretOffset = suggestion.name.length + 1 + parameterNames.first().length + 2,
            shouldTriggerAutoPopup = true
        )
    }

    private fun pipeFunctionCallTemplate(lookupText: String, signature: String): FunctionCallTemplate {
        val remainingParameterNames = parseSignatureParameterNames(signature).drop(1)
        if (remainingParameterNames.isEmpty()) {
            return FunctionCallTemplate(
                text = lookupText,
                caretOffset = null,
                shouldTriggerAutoPopup = false
            )
        }

        val text =
            buildString {
                append(lookupText)
                append('(')
                append(
                    remainingParameterNames.joinToString(", ") { parameterName ->
                        "$parameterName: "
                    }
                )
                append(')')
            }

        return FunctionCallTemplate(
            text = text,
            caretOffset = lookupText.length + 1 + remainingParameterNames.first().length + 2,
            shouldTriggerAutoPopup = true
        )
    }

    private fun parseSignatureParameterNames(signature: String): List<String> {
        val openIndex = signature.indexOf('(')
        if (openIndex < 0) return emptyList()
        val closeIndex = signature.indexOf(')', openIndex + 1)
        if (closeIndex < 0 || closeIndex <= openIndex + 1) return emptyList()

        val parametersText = signature.substring(openIndex + 1, closeIndex)
        val parameters = ArrayList<String>()
        var segmentStart = 0
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        for (index in parametersText.indices) {
            when (parametersText[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                ',' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                        parameters += parametersText.substring(segmentStart, index)
                        segmentStart = index + 1
                    }
                }
            }
        }
        parameters += parametersText.substring(segmentStart)

        return parameters.mapNotNull { rawParameter ->
            val colonIndex = rawParameter.indexOf(':')
            if (colonIndex <= 0) return@mapNotNull null
            rawParameter.substring(0, colonIndex).trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun firstParameterType(signature: String): String? =
        parseSignatureParameters(signature).firstOrNull()?.type?.takeIf { it.isNotEmpty() }

    private fun matchesPipeInputTypes(signature: String, expectedTypes: Set<String>): Boolean {
        val firstParameterType = firstParameterType(signature) ?: return false
        return matchesExpectedTypes(firstParameterType, expectedTypes)
    }

    private fun insertStandaloneUseImport(
        currentText: String,
        document: com.intellij.openapi.editor.Document,
        modulePath: String,
        symbolName: String
    ) {
        val useLine = "use $modulePath.{$symbolName}"
        val insertionText = "$useLine\n"
        document.insertString(0, insertionText)
    }

    private fun lineEndOffset(text: String, anchor: Int): Int {
        val normalizedAnchor = anchor.coerceIn(0, text.length)
        val lineEnd = text.indexOf('\n', normalizedAnchor)
        return if (lineEnd >= 0) lineEnd else text.length
    }

    private fun iconFor(kind: CompletionSymbolKind) =
        when (kind) {
            CompletionSymbolKind.KEYWORD -> AllIcons.Nodes.Static
            CompletionSymbolKind.TYPE -> AllIcons.Nodes.Class
            CompletionSymbolKind.FUNCTION -> AllIcons.Nodes.Method
            CompletionSymbolKind.FIELD -> AllIcons.Nodes.Field
            CompletionSymbolKind.IDENTIFIER -> AllIcons.Nodes.Variable
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

    fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean {
        val normalizedCandidate = normalizeTypeText(candidateType)
        if (normalizedCandidate.isEmpty()) return false

        return expectedTypes.any { expectedType ->
            val normalizedExpected = normalizeTypeText(expectedType)
            normalizedExpected.isNotEmpty() &&
                typePatternsAreCompatible(normalizedCandidate, normalizedExpected)
        }
    }

    private fun typePatternsAreCompatible(leftType: String, rightType: String): Boolean =
        typePatternsAreCompatible(parseTypePattern(leftType), parseTypePattern(rightType))

    private fun typePatternsAreCompatible(left: TypePattern, right: TypePattern): Boolean =
        when {
            left is TypePattern.Wildcard || right is TypePattern.Wildcard -> true
            left is TypePattern.Named && right is TypePattern.Named -> {
                left.name == right.name &&
                    left.arguments.size == right.arguments.size &&
                    left.arguments.zip(right.arguments).all { (leftArgument, rightArgument) ->
                        typePatternsAreCompatible(leftArgument, rightArgument)
                    }
            }
            left is TypePattern.Raw && right is TypePattern.Raw -> left.text == right.text
            left is TypePattern.Named && right is TypePattern.Raw -> left.rendered == right.text
            left is TypePattern.Raw && right is TypePattern.Named -> left.text == right.rendered
            else -> false
        }

    private fun parseTypePattern(typeText: String): TypePattern {
        val normalized = normalizeTypeText(typeText)
        if (normalized.isEmpty()) return TypePattern.Raw("")
        if (isTypeVariable(normalized)) return TypePattern.Wildcard(normalized)

        val genericOpenIndex = normalized.indexOf('<')
        if (genericOpenIndex <= 0 || !normalized.endsWith(">")) {
            return TypePattern.Named(normalized, emptyList())
        }

        val head = normalized.substring(0, genericOpenIndex).trim()
        val inner = normalized.substring(genericOpenIndex + 1, normalized.length - 1)
        val arguments = splitTopLevelTypeArguments(inner) ?: return TypePattern.Raw(normalized)
        return TypePattern.Named(
            name = head,
            arguments = arguments.map(::parseTypePattern),
            rendered = normalized
        )
    }

    private fun splitTopLevelTypeArguments(text: String): List<String>? {
        val arguments = ArrayList<String>()
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var segmentStart = 0

        for (index in text.indices) {
            when (text[index]) {
                '<' -> angleDepth++
                '>' -> {
                    if (angleDepth == 0) return null
                    angleDepth--
                }
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ',' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        arguments += text.substring(segmentStart, index).trim()
                        segmentStart = index + 1
                    }
                }
            }
        }

        if (angleDepth != 0 || parenDepth != 0 || bracketDepth != 0 || braceDepth != 0) return null
        arguments += text.substring(segmentStart).trim()
        return arguments.filter { it.isNotEmpty() }
    }

    private fun isTypeVariable(typeText: String): Boolean =
        typeText.isNotEmpty() &&
            typeText.none { it == '<' || it == '>' || it == ',' || it == '(' || it == ')' || it == '[' || it == ']' || it == '{' || it == '}' || it == '.' || it == '/' || it.isWhitespace() } &&
            typeText.first().isLowerCase()

    private fun equivalentTypeNames(anchor: PsiElement, expectedType: String): Set<String> {
        val normalizedExpectedType = normalizeTypeText(expectedType)
        if (normalizedExpectedType.isEmpty()) return emptySet()

        val aliases = LinkedHashSet<TypeAliasEntry>()
        aliases += collectVisibleTypeAliases(anchor)
        val equivalents = linkedSetOf(normalizedExpectedType)
        val processedGlobalAliasSeeds = LinkedHashSet<String>()
        var changed = true

        while (changed) {
            changed = false
            for (equivalent in equivalents.toList()) {
                val seedTypeName = globalTypeLookupSeed(equivalent) ?: continue
                if (!processedGlobalAliasSeeds.add(seedTypeName)) continue
                if (aliases.addAll(collectGlobalTypeAliases(anchor, seedTypeName))) {
                    changed = true
                }
            }
            for (alias in aliases) {
                val aliasName = normalizeTypeText(alias.alias)
                val targetType = normalizeTypeText(alias.targetType)
                if (aliasName in equivalents && equivalents.add(targetType)) changed = true
                if (targetType in equivalents && equivalents.add(aliasName)) changed = true
            }
        }

        return equivalents
    }

    private fun collectGlobalTypeAliases(
        anchor: PsiElement,
        typeName: String
    ): List<TypeAliasEntry> {
        if (typeName.isBlank() || DumbService.isDumb(anchor.project)) return emptyList()

        val scope = AikenSearchScopes.forElement(anchor)
        val index = FileBasedIndex.getInstance()
        val seenFiles = LinkedHashSet<VirtualFile>()
        val aliases = LinkedHashSet<TypeAliasEntry>()

        try {
            index.processValues(
                AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME,
                aikenTopLevelSymbolNameKey(AikenTopLevelSymbolKind.TYPE, typeName),
                null,
                ValueProcessor<Int> { file, _ ->
                    if (!seenFiles.add(file)) return@ValueProcessor true
                    aliases += extractTypeAliases(file.contentsToByteArray().toString(Charsets.UTF_8))
                    true
                },
                scope
            )
        } catch (_: IndexNotReadyException) {
            return emptyList()
        }

        return aliases.toList()
    }

    private fun globalTypeLookupSeed(typeText: String): String? {
        val normalized = normalizeTypeText(typeText)
        if (normalized.isBlank()) return null
        val head = normalized.substringBefore('<').substringAfterLast('.').trim()
        return head.takeIf { it.isNotEmpty() && it.firstOrNull()?.isUpperCase() == true && it.none { ch -> ch.isWhitespace() } }
    }

    private fun collectVisibleTypeAliases(anchor: PsiElement): List<TypeAliasEntry> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val texts = LinkedHashSet<String>()
        texts += file.text

        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val importedModules =
            useModel.statements
                .mapTo(LinkedHashSet()) { it.modulePath }
                .filter { it.isNotBlank() && it != currentModulePath }

        for (modulePath in importedModules) {
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                texts += moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            }
        }

        return texts.asSequence().flatMap { extractTypeAliases(it).asSequence() }.distinct().toList()
    }

    private fun extractTypeAliases(text: String): List<TypeAliasEntry> {
        val results = ArrayList<TypeAliasEntry>()
        val aliasRegex =
            Regex(
                pattern = """(?m)^\s*(?:pub\s+)?(?:opaque\s+)?type\s+([A-Z][A-Za-z0-9_]*(?:<[^=\n]+>)?)\s*=\s*([^\n/]+?)\s*(?://.*)?$"""
            )
        for (match in aliasRegex.findAll(text)) {
            val alias = match.groupValues[1].trim()
            val targetType = match.groupValues[2].trim()
            if (alias.isNotEmpty() && targetType.isNotEmpty()) {
                results += TypeAliasEntry(alias, targetType)
            }
        }
        return results
    }

    private fun parseSignatureParameters(signature: String): List<SignatureParameter> {
        val openIndex = signature.indexOf('(')
        if (openIndex < 0) return emptyList()
        val closeIndex = signature.indexOf(')', openIndex + 1)
        if (closeIndex < 0 || closeIndex <= openIndex + 1) return emptyList()

        val parametersText = signature.substring(openIndex + 1, closeIndex)
        val parameters = ArrayList<String>()
        var segmentStart = 0
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        for (index in parametersText.indices) {
            when (parametersText[index]) {
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                ',' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                        parameters += parametersText.substring(segmentStart, index)
                        segmentStart = index + 1
                    }
                }
            }
        }
        parameters += parametersText.substring(segmentStart)

        return parameters.mapNotNull { rawParameter ->
            val trimmed = rawParameter.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0 || colonIndex >= trimmed.lastIndex) {
                SignatureParameter(name = null, type = trimmed.ifEmpty { null })
            } else {
                SignatureParameter(
                    name = trimmed.substring(0, colonIndex).trim().takeIf { it.isNotEmpty() },
                    type = trimmed.substring(colonIndex + 1).trim().takeIf { it.isNotEmpty() }
                )
            }
        }
    }

    private fun collectModuleFiles(root: VirtualFile): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()

        fun walk(directory: VirtualFile?) {
            if (directory == null || !directory.isValid || !directory.isDirectory) return
            for (child in directory.children) {
                when {
                    child.isDirectory -> walk(child)
                    child.extension == "ak" -> result += child
                }
            }
        }

        walk(root.findChild("lib"))
        walk(root.findChild("validators"))
        root.findFileByRelativePath("build/packages")
            ?.children
            ?.filter { it.isDirectory }
            ?.forEach { packageDir ->
                walk(packageDir.findChild("lib"))
                walk(packageDir.findChild("validators"))
            }

        return result
    }

    private data class TypedBinding(
        val name: String,
        val type: String
    )

    private data class ConstructibleSuggestion(
        val name: String,
        val resultType: String,
        val fields: List<AikenConstructibleFieldEntry> = emptyList(),
        val supportsNamedSyntax: Boolean = false,
        val modulePath: String? = null,
        val needsImport: Boolean = false
    )

    private fun ConstructibleSuggestion.toCompletionInfo(): AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo =
        AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo(
            name = name,
            resultType = resultType,
            fields = fields,
            supportsNamedSyntax = supportsNamedSyntax,
            modulePath = modulePath,
            needsImport = needsImport
        )

    private data class TypeAliasEntry(
        val alias: String,
        val targetType: String
    )

    private data class TypedConstSuggestion(
        val name: String,
        val type: String,
        val priority: Double
    )

    private data class UnimportedConstSuggestion(
        val name: String,
        val modulePath: String,
        val type: String
    )

    private data class VisibleFunctionSuggestion(
        val name: String,
        val signature: String,
        val priority: Double
    )

    private data class UnimportedFunctionSuggestion(
        val name: String,
        val modulePath: String,
        val signature: String
    )

    private data class PipeFunctionSuggestion(
        val lookupText: String,
        val matchName: String,
        val signature: String,
        val priority: Double
    )

    private data class FunctionCallTemplate(
        val text: String,
        val caretOffset: Int?,
        val shouldTriggerAutoPopup: Boolean
    )

    private data class ExpectedTypeContext(
        val expectedType: String,
        val isSpread: Boolean
    )

    private data class InferredBranch(
        val type: String?,
        val expression: String
    )

    private data class InferredListLiteralContext(
        val expectedListType: String,
        val currentSegmentText: String
    )

    private data class SignatureParameter(
        val name: String?,
        val type: String?
    )

    private sealed interface TypePattern {
        data class Named(
            val name: String,
            val arguments: List<TypePattern>,
            val rendered: String = if (arguments.isEmpty()) name else "$name<${arguments.joinToString(",") { it.asRenderedString() }}>"
        ) : TypePattern

        data class Wildcard(val name: String) : TypePattern

        data class Raw(val text: String) : TypePattern
    }

    private fun TypePattern.asRenderedString(): String =
        when (this) {
            is TypePattern.Named -> rendered
            is TypePattern.Wildcard -> name
            is TypePattern.Raw -> text
        }

    private sealed interface ValueWrapperFrame {
        data object OPTION_SOME : ValueWrapperFrame
        data class LIST_LITERAL(val currentSegmentIsSpread: Boolean) : ValueWrapperFrame
    }

    private sealed interface Frame {
        data object OptionSome : Frame
        data object OtherParen : Frame
        data object OtherBrace : Frame
        data class ListLiteral(var currentSegmentStart: Int) : Frame
    }

    private data class ListScanFrame(
        var currentSegmentStart: Int,
        var inferredElementType: String? = null,
        val expectedListType: String? = null
    )
}
