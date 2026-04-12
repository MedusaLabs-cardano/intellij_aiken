package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_CONSTRUCTIBLE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_FUNCTION_SIGNATURE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME
import com.medusalabs.aiken.index.AikenConstTypeExtractor
import com.medusalabs.aiken.index.AikenConstructibleEntry
import com.medusalabs.aiken.index.AikenConstructibleExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.aikenConstructibleResultTypeKey
import com.medusalabs.aiken.index.aikenFunctionSignatureReturnTypeKey
import com.medusalabs.aiken.index.aikenFunctionSignatureReturnType
import com.medusalabs.aiken.index.aikenFunctionSignatureModuleKey
import com.medusalabs.aiken.index.aikenTopLevelSymbolNameKey
import com.medusalabs.aiken.index.decodeAikenConstructibleIndexValue
import com.medusalabs.aiken.index.decodeAikenConstructibleReturnTypeIndexValues
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

data class AikenTypedCompletionCandidate(
    val name: String,
    val type: String,
    val kind: CompletionSymbolKind
)

object AikenTypeDirectedCompletionSupport {
    private data class PatternInitializerContext(
        val patternText: String,
        val initializerStart: Int,
        val operatorText: String
    )

    private data class WhenPatternContext(
        val patternText: String,
        val scrutineeStart: Int,
        val scrutineeEnd: Int
    )

    private data class WhenPatternBodyContext(
        val scrutineeStart: Int,
        val scrutineeEnd: Int,
        val bodyOpenBrace: Int,
        val bodyCloseBrace: Int
    )

    private data class IfSoftCastContext(
        val subjectStart: Int,
        val subjectEnd: Int,
        val narrowedType: String,
        val thenOpenBrace: Int,
        val thenCloseBrace: Int
    )

    private data class BindingInitializerExpectedTypeContext(
        val expectedType: String,
        val currentValueText: String
    )

    private data class CallableReturnExpectedTypeContext(
        val expectedType: String,
        val currentValueText: String
    )

    private val inferredFunctionReturnGuard =
        ThreadLocal.withInitial<LinkedHashSet<String>> { linkedSetOf() }
    private val expressionTypeResolver =
        object : AikenExpressionTypeInference.Resolver {
            override fun inferListLiteralType(
                text: String,
                listStart: Int,
                anchor: PsiElement,
                referenceOffset: Int,
                visitedDeclarationOffsets: MutableSet<Int>
            ): String? =
                AikenTypeDirectedCompletionSupport.inferListLiteralType(text, listStart, anchor, referenceOffset, visitedDeclarationOffsets)

            override fun resolveVisibleBindingType(
                anchor: PsiElement,
                symbolText: String,
                beforeOffset: Int,
                visitedDeclarationOffsets: MutableSet<Int>
            ): String? =
                AikenTypeDirectedCompletionSupport.resolveVisibleBindingType(anchor, symbolText, beforeOffset, visitedDeclarationOffsets)

            override fun resolveConstType(anchor: PsiElement, symbolText: String): String? =
                AikenTypeDirectedCompletionSupport.resolveConstType(anchor, symbolText)

            override fun resolveFunctionReturnType(anchor: PsiElement, symbolText: String): String? =
                AikenTypeDirectedCompletionSupport.resolveFunctionReturnType(anchor, symbolText)

            override fun resolveFunctionValueType(anchor: PsiElement, symbolText: String): String? =
                AikenTypeDirectedCompletionSupport.resolveFunctionValueType(anchor, symbolText)

            override fun resolveConstructibleResultType(anchor: PsiElement, symbolText: String): String? =
                AikenTypeDirectedCompletionSupport.resolveConstructibleResultType(anchor, symbolText)

            override fun resolveFieldAccessType(anchor: PsiElement, containerType: String, fieldName: String): String? =
                AikenTypeDirectedCompletionSupport.resolveFieldAccessType(anchor, containerType, fieldName)

            override fun normalizeTypeText(text: String): String = AikenTypeDirectedCompletionSupport.normalizeTypeText(text)

            override fun typePatternsAreCompatible(leftType: String, rightType: String): Boolean =
                AikenTypeDirectedCompletionSupport.typePatternsAreCompatible(leftType, rightType)
        }
    private val typedCandidateResolver =
        object : AikenTypedCandidateResolver.Resolver {
            override fun parseBindingTypeAt(
                text: String,
                declarationOffset: Int,
                bindingName: String,
                anchor: PsiElement,
                visitedDeclarationOffsets: MutableSet<Int>
            ): String? =
                AikenTypeDirectedCompletionSupport.parseBindingTypeAt(text, declarationOffset, bindingName, anchor, visitedDeclarationOffsets)

            override fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean =
                AikenTypeDirectedCompletionSupport.matchesExpectedTypes(candidateType, expectedTypes)

            override fun expectedTypeDistance(
                anchor: PsiElement,
                candidateType: String,
                expectedType: AikenExpectedTypeProfile
            ): Int? = AikenTypeDirectedCompletionSupport.expectedTypeDistance(anchor, candidateType, expectedType)

            override fun normalizeTypeText(text: String): String = AikenTypeDirectedCompletionSupport.normalizeTypeText(text)

            override fun resolveSameFileConstType(
                text: String,
                declarationOffset: Int,
                constName: String,
                anchor: PsiElement
            ): String? =
                AikenTypeDirectedCompletionSupport.resolveSameFileConstType(text, declarationOffset, constName, anchor)

            override fun inferFunctionReturnType(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? = AikenTypeDirectedCompletionSupport.inferFunctionReturnType(anchor, functionName, modulePath)

            override fun resolveFunctionSignature(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? = AikenTypeDirectedCompletionSupport.resolveFunctionSignature(anchor, functionName, modulePath)
        }

    private data class ExpectedTypeDistanceCacheKey(
        val candidateType: String,
        val expectedType: AikenExpectedTypeProfile
    )

    private data class FunctionCacheKey(
        val functionName: String,
        val modulePath: String?
    )

    internal fun lookupsForExpectedType(
        anchor: PsiElement,
        expectedType: String,
        currentValueText: String = "",
        extraCandidates: List<AikenTypedCompletionCandidate> = emptyList(),
        context: AikenTypedCandidateContext = AikenTypedCandidateContext.None,
        excludedNames: Set<String> = emptySet(),
        pruneByPrefix: Boolean = false
    ): List<LookupElement> {
        val effectiveContext = effectiveExpectedTypeContext(expectedType, currentValueText) ?: return emptyList()
        val normalizedExpectedType = normalizeTypeText(effectiveContext.expectedType)
        if (normalizedExpectedType.isEmpty()) return emptyList()
        if (effectiveContext.isSpread) {
            return spreadLookupsForExpectedType(anchor, normalizedExpectedType, excludedNames, currentValueText)
        }
        val expectedTypeProfile = buildExpectedTypeProfile(anchor, normalizedExpectedType)
        val completionPrefix = if (pruneByPrefix) currentCompletionPrefix(currentValueText) else ""
        val cachedResolver = cachedTypedCandidateResolver()
        return AikenTypedCandidateResolver.collectCandidatesForExpectedType(
            anchor = anchor,
            expectedType = expectedTypeProfile,
            extraCandidates = extraCandidates,
            context = context,
            excludedNames = excludedNames,
            prefix = completionPrefix,
            resolver = cachedResolver
        ).map { candidate ->
            AikenTypedLookupFactory.createExpectedTypeLookup(candidate, normalizedExpectedType)
        }
    }

    fun bindingInitializerLookups(
        anchor: PsiElement,
        text: String,
        offset: Int
    ): List<LookupElement> {
        val context = inferBindingInitializerExpectedTypeContext(anchor, text, offset) ?: return emptyList()
        return lookupsForExpectedType(
            anchor = anchor,
            expectedType = context.expectedType,
            currentValueText = context.currentValueText,
            pruneByPrefix = true
        )
    }

    fun callableReturnLookups(
        anchor: PsiElement,
        text: String,
        offset: Int
    ): List<LookupElement> {
        val context = inferCallableReturnExpectedTypeContext(text, offset) ?: return emptyList()
        return lookupsForExpectedType(
            anchor = anchor,
            expectedType = context.expectedType,
            currentValueText = context.currentValueText,
            pruneByPrefix = true
        )
    }

    fun hasCallableReturnExpectedTypeContext(
        text: String,
        offset: Int
    ): Boolean = inferCallableReturnExpectedTypeContext(text, offset) != null

    fun spreadLookupsForExpectedType(
        anchor: PsiElement,
        expectedType: String,
        excludedNames: Set<String> = emptySet(),
        currentValueText: String = ""
    ): List<LookupElement> {
        val normalizedExpectedType = normalizeTypeText(expectedType)
        if (normalizedExpectedType.isEmpty()) return emptyList()

        val expectedTypeProfile = buildExpectedTypeProfile(anchor, normalizedExpectedType)
        val completionPrefix = currentCompletionPrefix(currentValueText)
        return AikenTypedCandidateResolver.collectSpreadCandidatesForExpectedType(
            anchor = anchor,
            expectedType = expectedTypeProfile,
            excludedNames = excludedNames,
            prefix = completionPrefix,
            resolver = cachedTypedCandidateResolver()
        ).map { candidate ->
            AikenTypedLookupFactory.createSpreadLookup(candidate, normalizedExpectedType)
        }
    }

    fun constructibleLookupsForExpectedType(
        anchor: PsiElement,
        expectedType: String,
        currentValueText: String = "",
        excludedNames: Set<String> = emptySet()
    ): List<LookupElement> {
        val effectiveContext = effectiveExpectedTypeContext(expectedType, currentValueText) ?: return emptyList()
        val normalizedExpectedType = normalizeTypeText(effectiveContext.expectedType)
        if (normalizedExpectedType.isEmpty() || effectiveContext.isSpread) return emptyList()

        val expectedTypeProfile = buildExpectedTypeProfile(anchor, normalizedExpectedType)
        val completionPrefix = currentCompletionPrefix(currentValueText)
        return AikenTypedCandidateResolver.collectCandidatesForExpectedType(
            anchor = anchor,
            expectedType = expectedTypeProfile,
            extraCandidates = emptyList(),
            context = AikenTypedCandidateContext.None,
            excludedNames = excludedNames,
            prefix = completionPrefix,
            resolver = cachedTypedCandidateResolver()
        )
            .asSequence()
            .filterIsInstance<AikenTypedExpectedTypeCandidate.Constructible>()
            .map { candidate -> AikenTypedLookupFactory.createExpectedTypeLookup(candidate, normalizedExpectedType) }
            .toList()
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
            currentValueText = "[${context.currentSegment.text}"
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

        val inputTypeProfile = buildExpectedTypeProfile(anchor, normalizedInputType)
        val cachedResolver = cachedTypedCandidateResolver()
        return AikenTypedCandidateResolver.collectPipeCandidatesForInputType(
            anchor = anchor,
            inputType = inputTypeProfile,
            qualifier = qualifier,
            excludedNames = excludedNames,
            resolver = cachedResolver
        ).map(AikenTypedLookupFactory::createPipeLookup)
    }

    private fun currentCompletionPrefix(currentValueText: String): String =
        AikenSyntaxText.identifierPrefix(currentValueText, currentValueText.length).trim()

    private fun cachedTypedCandidateResolver(): AikenTypedCandidateResolver.Resolver {
        val expectedTypeDistanceCache = HashMap<ExpectedTypeDistanceCacheKey, Int?>()
        val inferredFunctionReturnTypeCache = HashMap<FunctionCacheKey, String?>()
        val resolvedFunctionSignatureCache = HashMap<FunctionCacheKey, String?>()

        return object : AikenTypedCandidateResolver.Resolver by typedCandidateResolver {
            override fun expectedTypeDistance(
                anchor: PsiElement,
                candidateType: String,
                expectedType: AikenExpectedTypeProfile
            ): Int? {
                val normalizedCandidate = normalizeTypeText(candidateType)
                val cacheKey = ExpectedTypeDistanceCacheKey(normalizedCandidate, expectedType)
                return expectedTypeDistanceCache.getOrPut(cacheKey) {
                    typedCandidateResolver.expectedTypeDistance(anchor, normalizedCandidate, expectedType)
                }
            }

            override fun inferFunctionReturnType(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? {
                val cacheKey = FunctionCacheKey(functionName, modulePath)
                return inferredFunctionReturnTypeCache.getOrPut(cacheKey) {
                    typedCandidateResolver.inferFunctionReturnType(anchor, functionName, modulePath)
                }
            }

            override fun resolveFunctionSignature(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? {
                val cacheKey = FunctionCacheKey(functionName, modulePath)
                return resolvedFunctionSignatureCache.getOrPut(cacheKey) {
                    typedCandidateResolver.resolveFunctionSignature(anchor, functionName, modulePath)
                }
            }
        }
    }

    fun inferExpressionType(
        anchor: PsiElement,
        expressionText: String,
        beforeOffset: Int = anchor.textRange.startOffset
    ): String? =
        AikenExpressionTypeInference.inferExpressionType(
            anchor = anchor,
            expressionText = expressionText,
            beforeOffset = beforeOffset,
            resolver = expressionTypeResolver
        )

    private fun inferBindingInitializerExpectedTypeContext(
        anchor: PsiElement,
        text: String,
        offset: Int
    ): BindingInitializerExpectedTypeContext? {
        if (text.isEmpty()) return null
        val safeOffset = offset.coerceIn(0, text.length)
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var collectingBindingPattern = false
        var patternStart = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (!collectingBindingPattern) {
                if (tokenType == AikenTokenTypes.KEYWORD &&
                    AikenBindingInitializerScanner.startsBindingPattern(text, tokenText, lexer.tokenEnd)
                ) {
                    collectingBindingPattern = true
                    patternStart = lexer.tokenEnd
                }
                lexer.advance()
                continue
            }

            if (tokenType == AikenTokenTypes.OPERATOR && tokenText == "=") {
                val initializerTokenEnd = lexer.tokenEnd
                val initializerEnd = AikenExpressionTypeInference.consumeExpressionEnd(text, initializerTokenEnd)
                val insideInitializer = safeOffset in initializerTokenEnd..initializerEnd
                val patternText =
                    if (patternStart in 0..lexer.tokenStart) {
                        text.substring(patternStart, lexer.tokenStart)
                    } else {
                        ""
                    }
                collectingBindingPattern = false
                patternStart = -1

                if (insideInitializer) {
                    val expectedType = inferExpectedTypeFromBindingPatternText(anchor, patternText) ?: return null
                    val valueStart = skipWhitespaceForward(text, initializerTokenEnd)
                    val currentValueStart = valueStart.coerceAtMost(safeOffset)
                    val currentValueText =
                        text.substring(currentValueStart, safeOffset.coerceAtMost(text.length))
                    return BindingInitializerExpectedTypeContext(
                        expectedType = expectedType,
                        currentValueText = currentValueText
                    )
                }

                lexer.advance()
                continue
            }

            lexer.advance()
        }

        return null
    }

    private fun inferCallableReturnExpectedTypeContext(
        text: String,
        offset: Int
    ): CallableReturnExpectedTypeContext? {
        if (text.isEmpty()) return null
        val safeOffset = offset.coerceIn(0, text.length)
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var braceDepth = 0

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (braceDepth == 0 &&
                tokenType == AikenTokenTypes.KEYWORD &&
                (tokenText == "fn" || tokenText == "test" || tokenText == "bench")
            ) {
                val callable =
                    parseCallableBodyContext(
                        text = text,
                        declarationKeywordStart = lexer.tokenStart,
                        declarationKeyword = tokenText
                    )
                if (callable != null) {
                    if (safeOffset in (callable.bodyOpen + 1)..callable.bodyClose) {
                        if (!isStandaloneCallableReturnSlot(text, safeOffset, callable.bodyOpen, callable.bodyClose)) {
                            return null
                        }
                        val lineStart = findCurrentLineStart(text, safeOffset, callable.bodyOpen + 1)
                        val currentValueText = text.substring(lineStart.coerceAtMost(safeOffset), safeOffset)
                        return CallableReturnExpectedTypeContext(
                            expectedType = callable.returnType,
                            currentValueText = currentValueText
                        )
                    }
                    lexer.start(text, callable.resumeOffset.coerceAtMost(text.length), text.length, 0)
                    braceDepth = 0
                    continue
                }
            }

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            lexer.advance()
        }

        return null
    }

    private data class ParsedCallableBodyContext(
        val returnType: String,
        val bodyOpen: Int,
        val bodyClose: Int,
        val resumeOffset: Int
    )

    private fun parseCallableBodyContext(
        text: String,
        declarationKeywordStart: Int,
        declarationKeyword: String
    ): ParsedCallableBodyContext? {
        var index = skipWhitespaceForward(text, declarationKeywordStart + declarationKeyword.length)
        if (index >= text.length || !isIdentifierChar(text[index])) return null

        while (index < text.length && isIdentifierChar(text[index])) index++
        index = skipWhitespaceForward(text, index)

        if (index < text.length && text[index] == '<') {
            index = skipTopLevelAngles(text, index) ?: return null
            index = skipWhitespaceForward(text, index)
        }

        if (index >= text.length || text[index] != '(') return null
        val paramsClose = AikenSyntaxText.findMatchingDelimiter(text, index, '(', ')') ?: return null
        index = skipWhitespaceForward(text, paramsClose + 1)

        if (index + 1 >= text.length || text[index] != '-' || text[index + 1] != '>') return null
        index = skipWhitespaceForward(text, index + 2)
        val returnTypeStart = index
        while (index < text.length && text[index] != '{' && text[index] != '\n' && text[index] != '\r') {
            index++
        }
        val returnType = normalizeTypeText(text.substring(returnTypeStart, index))
        if (returnType.isBlank()) return null

        index = skipWhitespaceForward(text, index)
        if (index >= text.length || text[index] != '{') return null
        val bodyOpen = index
        val bodyClose = AikenSyntaxText.findMatchingDelimiter(text, bodyOpen, '{', '}') ?: return null

        return ParsedCallableBodyContext(
            returnType = returnType,
            bodyOpen = bodyOpen,
            bodyClose = bodyClose,
            resumeOffset = bodyClose + 1
        )
    }

    private fun isStandaloneCallableReturnSlot(
        text: String,
        offset: Int,
        bodyStartInclusive: Int,
        bodyEndInclusive: Int
    ): Boolean {
        val lineStart = findCurrentLineStart(text, offset, bodyStartInclusive)
        val linePrefix = text.substring(lineStart.coerceIn(bodyStartInclusive, offset), offset.coerceAtMost(text.length))
        val trimmed = linePrefix.trimStart()
        if (trimmed.isEmpty()) return true

        val forbiddenStarts =
            listOf(
                "let ",
                "expect ",
                "const ",
                "use ",
                "type ",
                "if ",
                "when ",
                "trace ",
                "fn ",
                "validator ",
                "test ",
                "bench "
            )
        if (forbiddenStarts.any { trimmed.startsWith(it) }) return false

        val previousLineEnd = (lineStart - 1).coerceAtLeast(bodyStartInclusive - 1)
        if (previousLineEnd < bodyStartInclusive) return true
        return previousLineEnd <= bodyEndInclusive
    }

    private fun findCurrentLineStart(
        text: String,
        offset: Int,
        floor: Int
    ): Int {
        var index = offset.coerceIn(0, text.length) - 1
        while (index >= floor) {
            val ch = text[index]
            if (ch == '\n' || ch == '\r') return index + 1
            index--
        }
        return floor
    }

    private fun skipTopLevelAngles(text: String, start: Int): Int? {
        var index = start
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
                '\n', '\r', '{', '}' -> return null
            }
            index++
        }
        return null
    }

    private fun inferExpectedTypeFromBindingPatternText(
        anchor: PsiElement,
        patternText: String
    ): String? {
        val annotation = topLevelTypeAnnotation(patternText)
        if (annotation != null) {
            return normalizeTypeText(annotation.second).takeIf { it.isNotBlank() }
        }

        val strippedPattern = stripTopLevelTypeAnnotation(patternText).trim()
        val constructorHead = parseTopLevelConstructorHead(strippedPattern) ?: return null
        val constructorName = constructorHead.name.trim()
        if (constructorName.isBlank()) return null
        return resolveConstructibleResultType(anchor, constructorName)
            ?: constructorName.substringAfterLast('.').takeIf { it.isNotBlank() }
    }

    private fun topLevelTypeAnnotation(patternText: String): Pair<String, String>? {
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < patternText.length) {
            val ch = patternText[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }
            if (inString) {
                if (ch == '\\' && index + 1 < patternText.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }
            if (ch == '/' && index + 1 < patternText.length && patternText[index + 1] == '/') {
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
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' ->
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        val left = patternText.substring(0, index).trim()
                        val right = patternText.substring(index + 1).trim()
                        if (left.isBlank() || right.isBlank()) return null
                        return left to right
                    }
            }
            index++
        }

        return null
    }

    private fun effectiveExpectedTypeContext(expectedType: String, currentValueText: String): ExpectedTypeContext? {
        var effectiveType = normalizeTypeText(expectedType)
        var spreadContext = false

        for (wrapper in AikenValueWrapperScanner.scan(currentValueText)) {
            when (wrapper) {
                AikenValueWrapperScanner.Wrapper.OptionSome -> {
                    effectiveType = unwrapOptionType(effectiveType) ?: return null
                }
                is AikenValueWrapperScanner.Wrapper.ListLiteral -> {
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
    ): InferredListLiteralContext? =
        AikenListLiteralScanner.inferContext(
            text = text,
            offset = offset,
            expectedListTypeAtListStart = { parent, listStartOffset ->
                parent?.let(::nestedExpectedListTypeFromParent)
                    ?: expectedListTypeFromEnclosingBindingAnnotation(text, listStartOffset, anchor)
            },
            inferSegmentType = { startOffset, endExclusive ->
                inferListElementTypeFromCurrentRange(text, startOffset, endExclusive, anchor, offset)
            },
            fallbackElementType = { frame ->
                frame.expectedListType?.let(::unwrapListType)
            }
        )?.let { context ->
            InferredListLiteralContext(
                expectedListType = context.expectedListType,
                currentSegment = context.currentSegment
            )
        }

    private fun expectedListTypeFromEnclosingBindingAnnotation(
        text: String,
        listStartOffset: Int,
        anchor: PsiElement
    ): String? {
        val bindingSite =
            AikenBindingAnnotationScanner.findBindingNameBeforeInitializer(
                text = text,
                initializerOffset = listStartOffset
            )
                ?: return null

        val declaredType = parseBindingTypeAt(text, bindingSite.nameStart, bindingSite.name, anchor) ?: return null
        val normalizedType = normalizeTypeText(declaredType)
        return normalizedType.takeIf { unwrapListType(it) != null }
    }

    private fun nestedExpectedListTypeFromParent(parent: AikenListLiteralScanner.FrameState): String? {
        parent.inferredElementType
            ?.let(::normalizeTypeText)
            ?.takeIf { unwrapListType(it) != null }
            ?.let { return it }

        val parentElementType = parent.expectedListType?.let(::unwrapListType) ?: return null
        val normalizedElementType = normalizeTypeText(parentElementType)
        return normalizedElementType.takeIf { unwrapListType(it) != null }
    }

    private fun findImportedConstType(
        anchor: PsiElement,
        modulePath: String,
        constName: String
    ): String? =
        AikenTypedCandidateResolver.findImportedConstType(anchor, modulePath, constName)

    private fun parseBindingTypeAt(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        anchor: PsiElement,
        visitedDeclarationOffsets: MutableSet<Int> = linkedSetOf()
    ): String? {
        if (!visitedDeclarationOffsets.add(declarationOffset)) return null
        findEnclosingIfSoftCastType(
            text = text,
            symbolText = bindingName,
            referenceOffset = anchor.textRange.startOffset.coerceIn(0, text.length),
            declarationOffset = declarationOffset
        )?.let { return it }
        findParameterBindingType(
            text = text,
            declarationOffset = declarationOffset,
            bindingName = bindingName,
            anchor = anchor,
            visitedDeclarationOffsets = visitedDeclarationOffsets
        )?.let { return it }
        AikenBindingAnnotationScanner.declaredTypeAt(text, declarationOffset, bindingName)?.let { return it }

        val initializer =
            AikenBindingInitializerScanner.findInitializer(
                text = text,
                declarationOffset = declarationOffset,
                bindingName = bindingName
            )
        if (initializer != null) {
            val expressionEnd = AikenExpressionTypeInference.consumeExpressionEnd(text, initializer.expressionStart)
            val expressionText = text.substring(initializer.expressionStart, expressionEnd.coerceAtMost(text.length))
            val expressionType =
                AikenExpressionTypeInference.inferExpressionType(
                    anchor = anchor,
                    expressionText = expressionText,
                    beforeOffset = expressionEnd,
                    visitedDeclarationOffsets = visitedDeclarationOffsets,
                    resolver = expressionTypeResolver
                )
                    ?: return null
            return if (initializer.operatorText == "<-") {
                unwrapMonadicBindType(expressionType) ?: expressionType
            } else {
                expressionType
            }
        }

        val patternContext =
            findPatternInitializerContext(
                text = text,
                declarationOffset = declarationOffset,
                bindingName = bindingName
            )
        if (patternContext != null) {
            val expressionEnd = AikenExpressionTypeInference.consumeExpressionEnd(text, patternContext.initializerStart)
            val expressionText = text.substring(patternContext.initializerStart, expressionEnd.coerceAtMost(text.length))
            val initializerType =
                AikenExpressionTypeInference.inferExpressionType(
                    anchor = anchor,
                    expressionText = expressionText,
                    beforeOffset = expressionEnd,
                    visitedDeclarationOffsets = visitedDeclarationOffsets,
                    resolver = expressionTypeResolver
                )
                    ?: return null
            val patternValueType =
                if (patternContext.operatorText == "<-") {
                    unwrapMonadicBindType(initializerType) ?: initializerType
                } else {
                    initializerType
                }
            return inferPatternBindingType(
                patternText = patternContext.patternText,
                bindingName = bindingName,
                initializerType = patternValueType,
                anchor = anchor
            )
        }

        val whenPatternContext =
            findWhenPatternContext(
                text = text,
                declarationOffset = declarationOffset,
                bindingName = bindingName
            )
                ?: return null
        if (whenPatternContext.scrutineeEnd <= whenPatternContext.scrutineeStart) return null

        val scrutineeText =
            text.substring(whenPatternContext.scrutineeStart, whenPatternContext.scrutineeEnd.coerceAtMost(text.length)).trim()
        if (scrutineeText.isBlank()) return null
        val scrutineeType =
            AikenExpressionTypeInference.inferExpressionType(
                anchor = anchor,
                expressionText = scrutineeText,
                beforeOffset = whenPatternContext.scrutineeEnd,
                visitedDeclarationOffsets = visitedDeclarationOffsets,
                resolver = expressionTypeResolver
            )
                ?: return null
        return inferPatternBindingType(
            patternText = whenPatternContext.patternText,
            bindingName = bindingName,
            initializerType = scrutineeType,
            anchor = anchor
        )
    }

    private fun findParameterBindingType(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        anchor: PsiElement,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        val context = AikenParameterBindingScanner.findBindingContext(text, declarationOffset, bindingName) ?: return null
        val parameterType =
            context.explicitTypeText
                ?.let(::normalizeTypeText)
                ?.takeIf { it.isNotBlank() }
                ?: context.viaExpressionText
                    ?.let { expressionText ->
                        inferExpressionType(
                            anchor = anchor,
                            expressionText = expressionText,
                            beforeOffset = context.viaExpressionStart ?: anchor.textRange.startOffset
                        )
                    }
                    ?.let(::unwrapFuzzerType)
                ?: inferParameterPatternRootType(anchor, context.patternText, visitedDeclarationOffsets)
                ?: return null
        return inferPatternBindingType(
            patternText = context.patternText,
            bindingName = bindingName,
            initializerType = parameterType,
            anchor = anchor
        )
    }

    private fun inferParameterPatternRootType(
        anchor: PsiElement,
        patternText: String,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        val constructorName =
            AikenPatternBindingText.topLevelConstructorName(patternText)
                ?.substringAfterLast('.')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (constructorName != null) {
            resolveConstructibleResultType(anchor, constructorName)?.let { return it }
        }

        val normalizedPattern = patternText.trim()
        if (normalizedPattern.startsWith('(') || normalizedPattern.startsWith('[')) {
            return null
        }

        return null
    }

    private fun findPatternInitializerContext(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): PatternInitializerContext? {
        if (declarationOffset !in 0 until text.length) return null

        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        var collectingBindingPattern = false
        var patternStart = -1
        var containsTargetBinding = false

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (!collectingBindingPattern) {
                if (tokenType == AikenTokenTypes.KEYWORD &&
                    AikenBindingInitializerScanner.startsBindingPattern(text, tokenText, lexer.tokenEnd)
                ) {
                    collectingBindingPattern = true
                    patternStart = lexer.tokenEnd
                    containsTargetBinding = false
                }
                lexer.advance()
                continue
            }

            if (
                (tokenType == AikenTokenTypes.IDENTIFIER || tokenType == AikenTokenTypes.FIELD) &&
                lexer.tokenStart == declarationOffset &&
                tokenText == bindingName
            ) {
                containsTargetBinding = true
            }

            val bindingOperator =
                if (tokenType == AikenTokenTypes.OPERATOR) {
                    AikenBindingInitializerScanner.bindingOperatorAt(text, lexer.tokenStart)
                } else {
                    null
                }
            if (bindingOperator != null) {
                if (containsTargetBinding && patternStart in 0..lexer.tokenStart) {
                    val operatorEnd = lexer.tokenStart + bindingOperator.length
                    val initializerStart = skipWhitespaceForward(text, operatorEnd)
                    if (initializerStart < text.length) {
                        return PatternInitializerContext(
                            patternText = text.substring(patternStart, lexer.tokenStart),
                            initializerStart = initializerStart,
                            operatorText = bindingOperator
                        )
                    }
                }

                collectingBindingPattern = false
                patternStart = -1
                containsTargetBinding = false
                if (bindingOperator == "<-") {
                    lexer.start(text, (lexer.tokenStart + 2).coerceAtMost(text.length), text.length, 0)
                } else {
                    lexer.advance()
                }
                continue
            }

            if (tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.COMMENT) {
                lexer.advance()
                continue
            }

            lexer.advance()
        }

        return null
    }

    private fun findWhenPatternContext(
        text: String,
        declarationOffset: Int,
        bindingName: String
    ): WhenPatternContext? {
        if (declarationOffset !in 0 until text.length) return null

        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (tokenType != AikenTokenTypes.KEYWORD || tokenText != "when") {
                lexer.advance()
                continue
            }

            val whenBody = findWhenPatternBodyContext(text, lexer.tokenEnd)
            if (whenBody == null) {
                lexer.advance()
                continue
            }

            var cursor = whenBody.bodyOpenBrace + 1
            while (cursor < whenBody.bodyCloseBrace) {
                val arrowOffset = findTopLevelArrowInRange(text, cursor, whenBody.bodyCloseBrace) ?: break
                val patternStart = skipWhitespaceForward(text, cursor)
                val patternEnd = arrowOffset
                if (patternStart < patternEnd) {
                    val patternText = text.substring(patternStart, patternEnd)
                    val bindings = extractPatternBindings(patternText, patternStart)
                    val declarationInsideCurrentPattern = declarationOffset in patternStart until patternEnd
                    if (declarationInsideCurrentPattern && bindings.any { (name, _) -> name == bindingName }) {
                        return WhenPatternContext(
                            patternText = patternText,
                            scrutineeStart = whenBody.scrutineeStart,
                            scrutineeEnd = whenBody.scrutineeEnd
                        )
                    }
                }

                val expressionStart = skipWhitespaceForward(text, arrowOffset + 2)
                if (expressionStart >= whenBody.bodyCloseBrace) break
                val expressionEnd = consumeExpressionEndWithin(text, expressionStart, whenBody.bodyCloseBrace)
                cursor = if (expressionEnd > cursor) expressionEnd else (arrowOffset + 2)
            }

            lexer.start(
                text,
                (whenBody.bodyCloseBrace + 1).coerceAtMost(text.length),
                text.length,
                0
            )
        }

        return null
    }

    private fun findWhenPatternBodyContext(
        text: String,
        afterWhenOffset: Int
    ): WhenPatternBodyContext? {
        val lexer = AikenLexing.createLexer()
        lexer.start(text, afterWhenOffset.coerceIn(0, text.length), text.length, 0)

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0
        var sawTopLevelIs = false
        var scrutineeStart = -1
        var scrutineeEnd = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            val ignoredToken =
                tokenType == TokenType.WHITE_SPACE ||
                    tokenType == AikenTokenTypes.WHITESPACE ||
                    tokenType == AikenTokenTypes.COMMENT
            if (ignoredToken) {
                lexer.advance()
                continue
            }

            val atTopLevel = parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0
            if (!sawTopLevelIs && scrutineeStart < 0) {
                scrutineeStart = lexer.tokenStart
            }
            if (tokenType == AikenTokenTypes.KEYWORD && tokenText == "is" && atTopLevel) {
                if (scrutineeStart < 0 || scrutineeEnd <= scrutineeStart) return null
                sawTopLevelIs = true
                lexer.advance()
                continue
            }
            if (sawTopLevelIs) {
                if (tokenType == AikenTokenTypes.LBRACE && atTopLevel) {
                    val openBrace = lexer.tokenStart
                    val closeBrace = AikenSyntaxText.findMatchingDelimiter(text, openBrace, '{', '}') ?: return null
                    return WhenPatternBodyContext(
                        scrutineeStart = scrutineeStart,
                        scrutineeEnd = scrutineeEnd,
                        bodyOpenBrace = openBrace,
                        bodyCloseBrace = closeBrace
                    )
                }
                return null
            }

            scrutineeEnd = lexer.tokenEnd
            when {
                tokenType == AikenTokenTypes.LPAREN -> parenDepth++
                tokenType == AikenTokenTypes.RPAREN -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.LBRACKET -> bracketDepth++
                tokenType == AikenTokenTypes.RBRACKET -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.LBRACE -> braceDepth++
                tokenType == AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.OPERATOR && tokenText == "<" -> angleDepth++
                tokenType == AikenTokenTypes.OPERATOR && tokenText == ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }
            lexer.advance()
        }

        return null
    }

    private fun findTopLevelArrowInRange(
        text: String,
        start: Int,
        endExclusive: Int
    ): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0
        var inString = false
        var inLineComment = false
        var index = start.coerceIn(0, text.length)
        val limit = endExclusive.coerceIn(index, text.length)

        while (index + 1 < limit) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < limit) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < limit && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0 && ch == '-' && text[index + 1] == '>') {
                return index
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }
            index++
        }

        return null
    }

    private fun consumeExpressionEndWithin(
        text: String,
        start: Int,
        limitExclusive: Int
    ): Int {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = start.coerceIn(0, text.length)
        val limit = limitExclusive.coerceIn(index, text.length)

        while (index < limit) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < limit) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < limit && text[index + 1] == '/') {
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

        return limit
    }

    private fun extractPatternBindings(
        patternText: String,
        absoluteStartOffset: Int
    ): List<Pair<String, Int>> =
        AikenPatternBindingText.extractBindings(patternText, absoluteStartOffset)

    private fun inferPatternBindingType(
        patternText: String,
        bindingName: String,
        initializerType: String,
        anchor: PsiElement
    ): String? {
        val normalizedInitializerType = normalizeTypeText(initializerType)
        if (normalizedInitializerType.isBlank()) return null

        val patternWithoutAnnotation = stripTopLevelTypeAnnotation(patternText).trim()
        return inferPatternBindingTypeRecursive(
            patternText = patternWithoutAnnotation,
            bindingName = bindingName,
            valueType = normalizedInitializerType,
            anchor = anchor
        )
    }

    private fun inferPatternBindingTypeRecursive(
        patternText: String,
        bindingName: String,
        valueType: String,
        anchor: PsiElement,
        depth: Int = 0
    ): String? {
        if (depth > 20) return null

        val normalizedValueType = normalizeTypeText(valueType)
        if (normalizedValueType.isBlank()) return null

        val stripped = stripTopLevelTypeAnnotation(patternText).trim()
        if (stripped.isBlank() || stripped == "_") return null
        if (stripped == bindingName) return normalizedValueType

        if (stripped.startsWith("..")) {
            val spreadBinding = stripped.removePrefix("..").trim()
            if (spreadBinding == bindingName) return normalizedValueType
            if (spreadBinding.isNotBlank()) {
                return inferPatternBindingTypeRecursive(
                    patternText = spreadBinding,
                    bindingName = bindingName,
                    valueType = normalizedValueType,
                    anchor = anchor,
                    depth = depth + 1
                )
            }
            return null
        }

        val asPattern = splitTopLevelAsPattern(stripped)
        if (asPattern != null) {
            val (innerPattern, capturedName) = asPattern
            if (capturedName == bindingName) return normalizedValueType
            return inferPatternBindingTypeRecursive(
                patternText = innerPattern,
                bindingName = bindingName,
                valueType = normalizedValueType,
                anchor = anchor,
                depth = depth + 1
            )
        }

        if (isEnclosedBy(stripped, '(', ')')) {
            val inner = stripped.substring(1, stripped.length - 1).trim()
            val tupleSegments = topLevelSegments(inner)
            if (tupleSegments.size == 1) {
                return inferPatternBindingTypeRecursive(
                    patternText = tupleSegments.first(),
                    bindingName = bindingName,
                    valueType = normalizedValueType,
                    anchor = anchor,
                    depth = depth + 1
                )
            }

            val tupleElementTypes = tupleElementTypes(normalizedValueType)
            if (tupleElementTypes != null) {
                for ((index, segment) in tupleSegments.withIndex()) {
                    val itemType = tupleElementTypes.getOrNull(index) ?: continue
                    inferPatternBindingTypeRecursive(
                        patternText = segment,
                        bindingName = bindingName,
                        valueType = itemType,
                        anchor = anchor,
                        depth = depth + 1
                    )?.let { return it }
                }
            }
            return null
        }

        if (isEnclosedBy(stripped, '[', ']')) {
            val itemType = unwrapListType(normalizedValueType) ?: return null
            val segments = topLevelSegments(stripped.substring(1, stripped.length - 1))
            for (segment in segments) {
                val trimmedSegment = segment.trim()
                if (trimmedSegment.isBlank()) continue
                if (trimmedSegment.startsWith("..")) {
                    val spreadBinding = trimmedSegment.removePrefix("..").trim()
                    if (spreadBinding == bindingName) return normalizedValueType
                    if (spreadBinding.isNotBlank()) {
                        inferPatternBindingTypeRecursive(
                            patternText = spreadBinding,
                            bindingName = bindingName,
                            valueType = normalizedValueType,
                            anchor = anchor,
                            depth = depth + 1
                        )?.let { return it }
                    }
                    continue
                }
                inferPatternBindingTypeRecursive(
                    patternText = trimmedSegment,
                    bindingName = bindingName,
                    valueType = itemType,
                    anchor = anchor,
                    depth = depth + 1
                )?.let { return it }
            }
            return null
        }

        val bareSegments = topLevelSegments(stripped)
        if (bareSegments.size > 1) {
            val tupleElementTypes = tupleElementTypes(normalizedValueType) ?: return null
            for ((index, segment) in bareSegments.withIndex()) {
                val itemType = tupleElementTypes.getOrNull(index) ?: continue
                inferPatternBindingTypeRecursive(
                    patternText = segment,
                    bindingName = bindingName,
                    valueType = itemType,
                    anchor = anchor,
                    depth = depth + 1
                )?.let { return it }
            }
            return null
        }

        val constructorHead = parseTopLevelConstructorHead(stripped)
        if (constructorHead != null) {
            val constructorName = constructorHead.name.substringAfterLast('.').trim()
            if (constructorName.isBlank()) return null

            val innerText = stripped.substring(constructorHead.openIndex + 1, constructorHead.closeIndex)
            val segments = topLevelSegments(innerText)

            if (constructorHead.openChar == '(') {
                if (constructorName == "Some" && segments.size == 1) {
                    val optionInnerType = unwrapOptionType(normalizedValueType)
                    if (optionInnerType != null) {
                        return inferPatternBindingTypeRecursive(
                            patternText = segments.first(),
                            bindingName = bindingName,
                            valueType = optionInnerType,
                            anchor = anchor,
                            depth = depth + 1
                        )
                    }
                }

                for ((index, segment) in segments.withIndex()) {
                    val argumentType =
                        resolveConstructiblePatternFieldType(
                            anchor = anchor,
                            containerType = normalizedValueType,
                            constructorName = constructorName,
                            positionalIndex = index,
                            fieldName = null
                        )
                            ?: continue
                    inferPatternBindingTypeRecursive(
                        patternText = segment,
                        bindingName = bindingName,
                        valueType = argumentType,
                        anchor = anchor,
                        depth = depth + 1
                    )?.let { return it }
                }
                return null
            }

            for (segment in segments) {
                val trimmedSegment = segment.trim()
                if (trimmedSegment.isBlank()) continue
                if (trimmedSegment.startsWith("..")) {
                    val spreadBinding = trimmedSegment.removePrefix("..").trim()
                    if (spreadBinding == bindingName) return normalizedValueType
                    if (spreadBinding.isNotBlank()) {
                        inferPatternBindingTypeRecursive(
                            patternText = spreadBinding,
                            bindingName = bindingName,
                            valueType = normalizedValueType,
                            anchor = anchor,
                            depth = depth + 1
                        )?.let { return it }
                    }
                    continue
                }

                val colonIndex = AikenTopLevelText.indexOf(trimmedSegment, ':')
                if (colonIndex >= 0) {
                    val fieldName = trimmedSegment.substring(0, colonIndex).trim()
                    val fieldPattern = trimmedSegment.substring(colonIndex + 1).trim()
                    if (fieldName.isBlank() || fieldPattern.isBlank()) continue
                    val fieldType =
                        resolveConstructiblePatternFieldType(
                            anchor = anchor,
                            containerType = normalizedValueType,
                            constructorName = constructorName,
                            positionalIndex = null,
                            fieldName = fieldName
                        )
                            ?: continue
                    inferPatternBindingTypeRecursive(
                        patternText = fieldPattern,
                        bindingName = bindingName,
                        valueType = fieldType,
                        anchor = anchor,
                        depth = depth + 1
                    )?.let { return it }
                    continue
                }

                val shorthandName = trimmedSegment
                val fieldType =
                    resolveConstructiblePatternFieldType(
                        anchor = anchor,
                        containerType = normalizedValueType,
                        constructorName = constructorName,
                        positionalIndex = null,
                        fieldName = shorthandName
                    )
                        ?: continue
                if (shorthandName == bindingName) return normalizeTypeText(fieldType)
                inferPatternBindingTypeRecursive(
                    patternText = shorthandName,
                    bindingName = bindingName,
                    valueType = fieldType,
                    anchor = anchor,
                    depth = depth + 1
                )?.let { return it }
            }
        }

        return null
    }

    private fun stripTopLevelTypeAnnotation(patternText: String): String {
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < patternText.length) {
            val ch = patternText[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < patternText.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < patternText.length && patternText[index + 1] == '/') {
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
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return patternText.substring(0, index)
                }
            }

            index++
        }

        return patternText
    }

    private fun splitTopLevelAsPattern(patternText: String): Pair<String, String>? {
        val lexer = AikenLexing.createLexer()
        lexer.start(patternText)

        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var asStart = -1
        var asEnd = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = patternText.substring(lexer.tokenStart, lexer.tokenEnd)
            if (
                tokenType == AikenTokenTypes.KEYWORD &&
                tokenText == "as" &&
                angleDepth == 0 &&
                parenDepth == 0 &&
                bracketDepth == 0 &&
                braceDepth == 0
            ) {
                asStart = lexer.tokenStart
                asEnd = lexer.tokenEnd
            }

            when (tokenText) {
                "<" -> angleDepth++
                ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                "(" -> parenDepth++
                ")" -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                "[" -> bracketDepth++
                "]" -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                "{" -> braceDepth++
                "}" -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            lexer.advance()
        }

        if (asStart < 0 || asEnd <= asStart) return null
        val left = patternText.substring(0, asStart).trim()
        val right = patternText.substring(asEnd).trim()
        if (left.isBlank() || right.isBlank()) return null
        if (right.any { !isIdentifierChar(it) }) return null
        return left to right
    }

    private fun isEnclosedBy(
        text: String,
        open: Char,
        close: Char
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2 || trimmed.first() != open || trimmed.last() != close) return false
        val closeIndex = AikenSyntaxText.findMatchingDelimiter(trimmed, 0, open, close) ?: return false
        return closeIndex == trimmed.lastIndex
    }

    private data class PatternConstructorHead(
        val name: String,
        val openChar: Char,
        val openIndex: Int,
        val closeIndex: Int
    )

    private fun parseTopLevelConstructorHead(patternText: String): PatternConstructorHead? {
        val trimmed = patternText.trim()
        if (trimmed.isBlank()) return null

        val parenOpen = AikenTopLevelText.indexOf(trimmed, '(')
        val braceOpen = AikenTopLevelText.indexOf(trimmed, '{')
        val openIndex =
            sequenceOf(parenOpen, braceOpen)
                .filter { it >= 0 }
                .minOrNull()
                ?: return null
        if (openIndex <= 0) return null

        val openChar = trimmed[openIndex]
        val closeChar = if (openChar == '(') ')' else '}'
        val closeIndex = AikenSyntaxText.findMatchingDelimiter(trimmed, openIndex, openChar, closeChar) ?: return null
        val trailing = trimmed.substring(closeIndex + 1).trim()
        if (trailing.isNotEmpty()) return null

        val name = trimmed.substring(0, openIndex).trim()
        if (name.isBlank()) return null
        return PatternConstructorHead(name, openChar, openIndex, closeIndex)
    }

    private fun topLevelSegments(text: String): List<String> =
        AikenTopLevelText.splitRanges(text, ',')
            .map { range -> text.substring(range.startOffset, range.endOffset).trim() }
            .filter { it.isNotEmpty() }

    private fun tupleElementTypes(typeText: String): List<String>? {
        val normalized = normalizeTypeText(typeText)
        if (!isEnclosedBy(normalized, '(', ')')) return null
        val inner = normalized.substring(1, normalized.length - 1).trim()
        if (inner.isBlank()) return emptyList()
        return AikenTypeText.splitTopLevelTypeArguments(inner)?.map(::normalizeTypeText)
    }

    private fun resolveConstructiblePatternFieldType(
        anchor: PsiElement,
        containerType: String,
        constructorName: String,
        positionalIndex: Int?,
        fieldName: String?
    ): String? {
        val constructible =
            resolveBestConstructibleForPattern(
                anchor = anchor,
                containerType = containerType,
                constructorName = constructorName
            )
                ?: return null

        val fieldType =
            when {
                positionalIndex != null -> constructible.fields.getOrNull(positionalIndex)?.type
                !fieldName.isNullOrBlank() -> constructible.fields.firstOrNull { it.name == fieldName }?.type
                else -> null
            }
                ?: return null
        return normalizeTypeText(fieldType)
    }

    private data class ConstructibleResolutionCandidate(
        val entry: AikenConstructibleEntry,
        val distance: Int
    )

    private fun resolveBestConstructibleForPattern(
        anchor: PsiElement,
        containerType: String,
        constructorName: String
    ): AikenConstructibleEntry? {
        if (constructorName.isBlank()) return null
        val expectedProfile = buildExpectedTypeProfile(anchor, containerType)
        val candidates =
            collectVisibleConstructibleEntriesByOwner(
                anchor = anchor,
                ownerName = constructorName,
                containerType = containerType
            )
        if (candidates.isEmpty()) return null

        return candidates
            .asSequence()
            .mapNotNull { entry ->
                val distance = expectedTypeDistance(anchor, entry.resultTypeName, expectedProfile) ?: return@mapNotNull null
                ConstructibleResolutionCandidate(entry = entry, distance = distance)
            }
            .minByOrNull { it.distance }
            ?.entry
    }

    private fun collectVisibleConstructibleEntriesByOwner(
        anchor: PsiElement,
        ownerName: String,
        containerType: String
    ): List<AikenConstructibleEntry> {
        if (ownerName.isBlank()) return emptyList()
        val file = anchor.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val entries = ArrayList<AikenConstructibleEntry>()
        val seen = LinkedHashSet<Pair<String?, Int>>()

        fun addEntry(modulePath: String?, entry: AikenConstructibleEntry) {
            if (entry.ownerName != ownerName) return
            val key = modulePath to entry.offset
            if (seen.add(key)) {
                entries += entry
            }
        }

        AikenConstructibleExtractor.extract(file.text).forEach { addEntry(currentModulePath, it) }

        val importedModules =
            useModel.statements
                .mapTo(LinkedHashSet()) { it.modulePath.trim() }
                .filter { it.isNotBlank() }

        for (modulePath in importedModules) {
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))
                    .forEach { addEntry(modulePath, it) }
            }
        }

        if (!DumbService.isDumb(anchor.project)) {
            val index = FileBasedIndex.getInstance()
            val scope = AikenSearchScopes.forElement(anchor)
            val expectedProfile = buildExpectedTypeProfile(anchor, expectedType = containerType)

            val modulePathsFromResultType = LinkedHashSet<String>()
            for (compatibleType in expectedProfile.compatibleTypes.keys) {
                val key = aikenConstructibleResultTypeKey(compatibleType)
                for (value in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, key, scope)) {
                    for (entry in decodeAikenConstructibleReturnTypeIndexValues(value)) {
                        if (entry.ownerName == ownerName) {
                            modulePathsFromResultType += entry.modulePath
                        }
                    }
                }
            }

            val modulePaths = LinkedHashSet<String>().apply {
                addAll(importedModules)
                addAll(modulePathsFromResultType)
            }
            for (modulePath in modulePaths) {
                for (value in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, modulePath, scope)) {
                    for (entry in decodeAikenConstructibleIndexValue(value)) {
                        addEntry(modulePath, entry)
                    }
                }
            }
        }

        return entries
    }

    private fun skipWhitespaceForward(
        text: String,
        start: Int
    ): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun resolveSameFileConstType(
        text: String,
        declarationOffset: Int,
        constName: String,
        anchor: PsiElement
    ): String? =
        parseBindingTypeAt(
            text = text,
            declarationOffset = declarationOffset,
            bindingName = constName,
            anchor = anchor
        )

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
                        inferListElementTypeFromCurrentRange(
                            text,
                            segmentStart,
                            index,
                            anchor,
                            referenceOffset,
                            visitedDeclarationOffsets
                        )
                            ?.let { return "List<$it>" }
                        break
                    }
                }
                ',' -> {
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 1 && braceDepth == 0) {
                        inferListElementTypeFromCurrentRange(
                            text,
                            segmentStart,
                            index,
                            anchor,
                            referenceOffset,
                            visitedDeclarationOffsets
                        )
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
        return inferListElementTypeFromSegment(
            segment = AikenCurrentExpressionSegment(segmentText),
            anchor = anchor,
            declarationOffset = declarationOffset,
            visitedDeclarationOffsets = visitedDeclarationOffsets
        )
    }

    private fun inferListElementTypeFromSegment(
        segment: AikenCurrentExpressionSegment,
        anchor: PsiElement,
        declarationOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>
    ): String? {
        if (segment.text.isBlank()) return null
        val effectiveSegment = segment.effectiveValueText
        if (effectiveSegment.isBlank()) return null
        val resolvedType =
            AikenExpressionTypeInference.inferExpressionType(
                anchor = anchor,
                expressionText = effectiveSegment,
                beforeOffset = declarationOffset,
                visitedDeclarationOffsets = visitedDeclarationOffsets,
                resolver = expressionTypeResolver
            )
        val normalizedType = normalizeTypeText(resolvedType.orEmpty())
        if (segment.isSpread) {
            return unwrapListType(normalizedType)
        }
        return normalizedType.takeIf { it.isNotEmpty() }
    }

    private fun inferListElementTypeFromCurrentRange(
        text: String,
        startOffset: Int,
        endExclusive: Int,
        anchor: PsiElement,
        declarationOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int> = linkedSetOf()
    ): String? =
        inferListElementTypeFromSegment(
            segment = AikenCurrentExpressionSegment.fromRange(text, startOffset, endExclusive),
            anchor = anchor,
            declarationOffset = declarationOffset,
            visitedDeclarationOffsets = visitedDeclarationOffsets
        )

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

    private fun findEnclosingIfSoftCastType(
        text: String,
        symbolText: String,
        referenceOffset: Int,
        declarationOffset: Int
    ): String? {
        val lexer = AikenLexing.createLexer()
        lexer.start(text, 0, referenceOffset.coerceIn(0, text.length), 0)

        var bestMatch: IfSoftCastContext? = null
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (tokenType == AikenTokenTypes.KEYWORD && tokenText == "if") {
                val context = findIfSoftCastContext(text, lexer.tokenEnd)
                if (context != null &&
                    declarationOffset <= context.subjectStart &&
                    referenceOffset > context.thenOpenBrace &&
                    referenceOffset <= context.thenCloseBrace
                ) {
                    val subject = text.substring(context.subjectStart, context.subjectEnd).trim()
                    if (subject == symbolText &&
                        (bestMatch == null || context.thenOpenBrace >= bestMatch!!.thenOpenBrace)
                    ) {
                        bestMatch = context
                    }
                }
            }
            lexer.advance()
        }

        return bestMatch?.narrowedType
    }

    private fun findIfSoftCastContext(
        text: String,
        afterIfOffset: Int
    ): IfSoftCastContext? {
        val lexer = AikenLexing.createLexer()
        lexer.start(text, afterIfOffset.coerceIn(0, text.length), text.length, 0)

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0
        var sawTopLevelIs = false
        var subjectStart = -1
        var subjectEnd = -1
        var typeStart = -1

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            val ignoredToken =
                tokenType == TokenType.WHITE_SPACE ||
                    tokenType == AikenTokenTypes.WHITESPACE ||
                    tokenType == AikenTokenTypes.COMMENT
            if (ignoredToken) {
                lexer.advance()
                continue
            }

            val atTopLevel = parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0
            if (!sawTopLevelIs && subjectStart < 0) {
                subjectStart = lexer.tokenStart
            }
            if (tokenType == AikenTokenTypes.KEYWORD && tokenText == "is" && atTopLevel) {
                if (subjectStart < 0 || subjectEnd <= subjectStart) return null
                sawTopLevelIs = true
                lexer.advance()
                continue
            }
            if (sawTopLevelIs) {
                if (typeStart < 0) {
                    if (tokenType == AikenTokenTypes.LBRACE && atTopLevel) return null
                    typeStart = lexer.tokenStart
                }
                if (tokenType == AikenTokenTypes.LBRACE && atTopLevel) {
                    val thenOpenBrace = lexer.tokenStart
                    val thenCloseBrace = AikenSyntaxText.findMatchingDelimiter(text, thenOpenBrace, '{', '}') ?: return null
                    val narrowedType = normalizeTypeText(text.substring(typeStart, thenOpenBrace).trim())
                    if (narrowedType.isEmpty()) return null
                    return IfSoftCastContext(
                        subjectStart = subjectStart,
                        subjectEnd = subjectEnd,
                        narrowedType = narrowedType,
                        thenOpenBrace = thenOpenBrace,
                        thenCloseBrace = thenCloseBrace
                    )
                }
            } else {
                subjectEnd = lexer.tokenEnd
            }

            when {
                tokenType == AikenTokenTypes.LPAREN -> parenDepth++
                tokenType == AikenTokenTypes.RPAREN -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.LBRACKET -> bracketDepth++
                tokenType == AikenTokenTypes.RBRACKET -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.LBRACE -> braceDepth++
                tokenType == AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.OPERATOR && tokenText == "<" -> angleDepth++
                tokenType == AikenTokenTypes.OPERATOR && tokenText == ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }
            lexer.advance()
        }

        return null
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
            inferFunctionReturnType(anchor, name)?.let { return it }
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
            inferFunctionReturnType(anchor, target.symbolName, target.modulePath)?.let { return it }
        }

        return null
    }

    private fun resolveFunctionValueType(anchor: PsiElement, symbolText: String): String? {
        val file = anchor.containingFile ?: return null
        val qualifier = symbolText.substringBeforeLast('.', "")
        val name = symbolText.substringAfterLast('.')
        val signatures = com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extract(file.text)
        if (qualifier.isBlank()) {
            signatures[name]
                ?.let { signature ->
                    AikenFunctionSignatureText.functionType(signature, inferFunctionReturnType(anchor, name))
                        ?.let { return it }
                }
        }

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedTargets = useModel.resolveCallableTargets(name, qualifier.ifBlank { null })
        if (importedTargets.isEmpty()) return null

        val index = FileBasedIndex.getInstance()
        val scope = AikenSearchScopes.forElement(anchor)
        for (target in importedTargets) {
            val signature =
                index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, aikenFunctionSignatureModuleKey(target.modulePath, target.symbolName), scope)
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { resolveFunctionSignature(anchor, target.symbolName, target.modulePath).orEmpty() }
            if (signature.isEmpty()) continue

            AikenFunctionSignatureText.functionType(
                signature,
                inferFunctionReturnType(anchor, target.symbolName, target.modulePath)
            )?.let { return it }
        }

        return null
    }

    private fun inferFunctionReturnType(
        anchor: PsiElement,
        functionName: String,
        modulePath: String? = null
    ): String? {
        if (functionName.isBlank()) return null

        if (modulePath.isNullOrBlank()) {
            val file = anchor.containingFile ?: return null
            return inferFunctionReturnTypeInFile(anchor, file.text, file, functionName)
        }

        val anchorFile = anchor.containingFile ?: return null
        val psiManager = PsiManager.getInstance(anchor.project)
        for (moduleFile in AikenModuleFiles.findFilesForModulePath(anchorFile.virtualFile, modulePath)) {
            val psiFile = psiManager.findFile(moduleFile) ?: continue
            inferFunctionReturnTypeInFile(anchor, psiFile.text, psiFile, functionName)?.let { return it }
        }

        return null
    }

    private fun resolveFunctionSignature(
        anchor: PsiElement,
        functionName: String,
        modulePath: String? = null
    ): String? {
        if (functionName.isBlank()) return null

        if (modulePath.isNullOrBlank()) {
            val file = anchor.containingFile ?: return null
            return com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extract(file.text)[functionName]
                ?.takeIf { it.isNotBlank() }
        }

        val anchorFile = anchor.containingFile ?: return null
        val psiManager = PsiManager.getInstance(anchor.project)
        for (moduleFile in AikenModuleFiles.findFilesForModulePath(anchorFile.virtualFile, modulePath)) {
            val psiFile = psiManager.findFile(moduleFile) ?: continue
            com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor.extract(psiFile.text)[functionName]
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return null
    }

    private fun inferFunctionReturnTypeInFile(
        fallbackAnchor: PsiElement,
        fileText: String,
        psiFile: com.intellij.psi.PsiFile,
        functionName: String
    ): String? {
        val callableBody = AikenFunctionBodyScanner.findNamedCallableBody(fileText, functionName) ?: return null
        val guardKey = "${psiFile.virtualFile?.path ?: psiFile.name}:${callableBody.declarationOffset}"
        val active = inferredFunctionReturnGuard.get()
        if (!active.add(guardKey)) return null

        return try {
            val inferenceAnchorOffset =
                (callableBody.bodyRange.startOffset + 1).coerceIn(0, (fileText.length - 1).coerceAtLeast(0))
            val inferenceAnchor =
                psiFile.findElementAt(inferenceAnchorOffset)
                    ?: psiFile.firstChild
                    ?: fallbackAnchor

            AikenExpressionTypeInference.inferExpressionType(
                anchor = inferenceAnchor,
                expressionText = fileText.substring(callableBody.bodyRange.startOffset, callableBody.bodyRange.endOffset),
                beforeOffset = callableBody.bodyRange.endOffset,
                resolver = expressionTypeResolver
            )
        } finally {
            active.remove(guardKey)
            if (active.isEmpty()) {
                inferredFunctionReturnGuard.remove()
            }
        }
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

    private fun resolveFieldAccessType(anchor: PsiElement, containerType: String, fieldName: String): String? {
        if (fieldName.isBlank()) return null

        val file = anchor.containingFile ?: return null
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val equivalentTypes = equivalentTypeNames(anchor, containerType)
        if (equivalentTypes.isEmpty()) return null

        val normalizedTypeNames =
            equivalentTypes
                .mapNotNullTo(LinkedHashSet()) { typeText ->
                    globalTypeLookupSeed(typeText)?.let(::normalizeTypeText)
                }
                .ifEmpty {
                    linkedSetOf(normalizeTypeText(containerType))
                }

        fun matchingField(modulePathFilter: Set<String>?): String? {
            AikenConstructibleExtractor.extract(file.text)
                .firstNotNullOfOrNull { entry ->
                    val resultTypeName = normalizeTypeText(entry.resultTypeName)
                    if (resultTypeName !in normalizedTypeNames) return@firstNotNullOfOrNull null
                    entry.fields.firstOrNull { it.name == fieldName }?.type
                }
                ?.let { return it }

            val importedModules =
                if (modulePathFilter != null) modulePathFilter
                else useModel.statements.mapTo(LinkedHashSet()) { it.modulePath.trim() }.filter { it.isNotBlank() }.toSet()

            for (modulePath in importedModules) {
                for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                    AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))
                        .firstNotNullOfOrNull { entry ->
                            val resultTypeName = normalizeTypeText(entry.resultTypeName)
                            if (resultTypeName !in normalizedTypeNames) return@firstNotNullOfOrNull null
                            entry.fields.firstOrNull { it.name == fieldName }?.type
                        }
                        ?.let { return it }
                }
            }

            return null
        }

        return matchingField(null)
    }

    private fun unwrapListType(typeText: String): String? =
        AikenTypeText.unwrapSingleGenericType(typeText, "List", ::normalizeTypeText)

    private fun unwrapOptionType(typeText: String): String? =
        AikenTypeText.unwrapSingleGenericType(typeText, "Option", ::normalizeTypeText)

    private fun unwrapFuzzerType(typeText: String): String? =
        AikenTypeText.unwrapSingleGenericType(typeText, "Fuzzer", ::normalizeTypeText)

    private fun unwrapMonadicBindType(typeText: String): String? =
        normalizeTypeText(typeText)
            .let { normalized ->
                val openIndex = normalized.indexOf('<')
                if (openIndex <= 0 || !normalized.endsWith(">")) return@let null
                val inner = normalized.substring(openIndex + 1, normalized.length - 1)
                AikenTypeText.splitTopLevelTypeArguments(inner)
                    ?.takeIf { it.size == 1 }
                    ?.firstOrNull()
                    ?.let(::normalizeTypeText)
            }

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun normalizeTypeText(text: String): String = AikenTypeText.normalizeWhitespace(text)

    fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean {
        val normalizedCandidate = normalizeTypeText(candidateType)
        if (normalizedCandidate.isEmpty()) return false

        return expectedTypes.any { expectedType ->
            val normalizedExpected = normalizeTypeText(expectedType)
            normalizedExpected.isNotEmpty() &&
                typePatternsAreCompatible(normalizedCandidate, normalizedExpected)
        }
    }

    internal fun buildExpectedTypeProfile(
        anchor: PsiElement,
        expectedType: String
    ): AikenExpectedTypeProfile {
        val normalizedExpectedType = normalizeTypeText(expectedType)
        if (normalizedExpectedType.isEmpty()) {
            return AikenExpectedTypeProfile(primaryType = "", compatibleTypes = emptyMap())
        }

        val aliasEntries = collectAliasClosure(anchor, seedTypes = listOf(normalizedExpectedType))
        return AikenExpectedTypeProfile(
            primaryType = normalizedExpectedType,
            compatibleTypes = forwardEquivalentTypeDistances(normalizedExpectedType, aliasEntries),
            aliasEntries = aliasEntries
        )
    }

    internal fun expectedTypeDistance(
        anchor: PsiElement,
        candidateType: String,
        expectedType: AikenExpectedTypeProfile
    ): Int? {
        val normalizedCandidate = normalizeTypeText(candidateType)
        if (normalizedCandidate.isEmpty() || expectedType.primaryType.isBlank()) return null

        val aliasEntries =
            collectAliasClosure(
                anchor = anchor,
                seedTypes = expectedType.compatibleTypes.keys + normalizedCandidate,
                baseAliases = expectedType.aliasEntries
            )
        val candidateDistances = forwardEquivalentTypeDistances(normalizedCandidate, aliasEntries)
        if (candidateDistances.isEmpty()) return null

        var bestDistance: Int? = null
        for ((candidateEquivalent, candidateDistance) in candidateDistances) {
            val candidatePattern = parseTypePattern(candidateEquivalent)
            for ((expectedEquivalent, expectedDistance) in expectedType.compatibleTypes) {
                val compatibilityPenalty =
                    typePatternCompatibilityPenalty(candidatePattern, parseTypePattern(expectedEquivalent))
                        ?: continue
                val totalDistance = candidateDistance + expectedDistance + compatibilityPenalty
                if (bestDistance == null || totalDistance < bestDistance) {
                    bestDistance = totalDistance
                }
            }
        }

        return bestDistance
    }

    private fun typePatternsAreCompatible(leftType: String, rightType: String): Boolean =
        typePatternsAreCompatible(parseTypePattern(leftType), parseTypePattern(rightType))

    private fun typePatternsAreCompatible(left: TypePattern, right: TypePattern): Boolean =
        typePatternCompatibilityPenalty(left, right) != null

    private fun typePatternCompatibilityPenalty(left: TypePattern, right: TypePattern): Int? =
        when {
            left is TypePattern.Wildcard && right is TypePattern.Wildcard -> 1
            left is TypePattern.Wildcard || right is TypePattern.Wildcard -> 1
            left is TypePattern.Named && right is TypePattern.Named -> {
                if (left.name != right.name || left.arguments.size != right.arguments.size) {
                    null
                } else {
                    var totalPenalty = 0
                    for ((leftArgument, rightArgument) in left.arguments.zip(right.arguments)) {
                        val nestedPenalty = typePatternCompatibilityPenalty(leftArgument, rightArgument) ?: return null
                        totalPenalty += nestedPenalty
                    }
                    totalPenalty
                }
            }
            left is TypePattern.Raw && right is TypePattern.Raw ->
                if (left.text == right.text) 0 else null
            left is TypePattern.Named && right is TypePattern.Raw ->
                if (left.rendered == right.text) 0 else null
            left is TypePattern.Raw && right is TypePattern.Named ->
                if (left.text == right.rendered) 0 else null
            else -> null
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
        val arguments = AikenTypeText.splitTopLevelTypeArguments(inner) ?: return TypePattern.Raw(normalized)
        return TypePattern.Named(
            name = head,
            arguments = arguments.map(::parseTypePattern),
            rendered = normalized
        )
    }

    private fun isTypeVariable(typeText: String): Boolean =
        typeText.isNotEmpty() &&
            typeText.none { it == '<' || it == '>' || it == ',' || it == '(' || it == ')' || it == '[' || it == ']' || it == '{' || it == '}' || it == '.' || it == '/' || it.isWhitespace() } &&
            typeText.first().isLowerCase()

    private fun equivalentTypeNames(anchor: PsiElement, expectedType: String): Set<String> =
        buildExpectedTypeProfile(anchor, expectedType).compatibleTypes.keys

    private fun collectAliasClosure(
        anchor: PsiElement,
        seedTypes: Collection<String>,
        baseAliases: Collection<AikenTypeAliasEntry> = emptyList()
    ): List<AikenTypeAliasEntry> {
        val aliases = LinkedHashSet<AikenTypeAliasEntry>()
        aliases += baseAliases
        aliases += collectVisibleTypeAliases(anchor)

        val normalizedSeeds = seedTypes.map(::normalizeTypeText).filter { it.isNotBlank() }
        val processedGlobalAliasSeeds = LinkedHashSet<String>()
        var changed = true

        while (changed) {
            changed = false
            val reachableTypes = LinkedHashSet<String>()
            for (seedType in normalizedSeeds) {
                reachableTypes += forwardEquivalentTypeDistances(seedType, aliases).keys
            }
            for (alias in aliases) {
                reachableTypes += normalizeTypeText(alias.alias)
                reachableTypes += normalizeTypeText(alias.targetType)
            }

            for (reachableType in reachableTypes) {
                val seedTypeName = globalTypeLookupSeed(reachableType) ?: continue
                if (!processedGlobalAliasSeeds.add(seedTypeName)) continue
                if (aliases.addAll(collectGlobalTypeAliases(anchor, seedTypeName))) {
                    changed = true
                }
            }
        }

        return aliases.toList()
    }

    private fun forwardEquivalentTypeDistances(
        typeText: String,
        aliases: Collection<AikenTypeAliasEntry>
    ): Map<String, Int> {
        val normalizedType = normalizeTypeText(typeText)
        if (normalizedType.isBlank()) return emptyMap()

        val queue = ArrayDeque<Pair<TypePattern, Int>>()
        val distances = LinkedHashMap<String, Int>()
        queue.addLast(parseTypePattern(normalizedType) to 0)

        while (queue.isNotEmpty()) {
            val (pattern, distance) = queue.removeFirst()
            val rendered = pattern.asRenderedString()
            val existing = distances[rendered]
            if (existing != null && existing <= distance) continue
            distances[rendered] = distance

            for (expandedPattern in forwardExpandedPatterns(pattern, aliases)) {
                queue.addLast(expandedPattern to (distance + 1))
            }
        }

        return distances
    }

    private fun forwardExpandedPatterns(
        pattern: TypePattern,
        aliases: Collection<AikenTypeAliasEntry>
    ): Set<TypePattern> {
        val expandedPatterns = LinkedHashSet<TypePattern>()

        for (alias in aliases) {
            val aliasPattern = parseTypePattern(alias.alias)
            val targetPattern = parseTypePattern(alias.targetType)
            val substitution = matchAliasPattern(pattern, aliasPattern) ?: continue
            expandedPatterns += substituteAliasPattern(targetPattern, substitution)
        }

        if (pattern is TypePattern.Named) {
            for ((index, argument) in pattern.arguments.withIndex()) {
                for (expandedArgument in forwardExpandedPatterns(argument, aliases)) {
                    val updatedArguments = pattern.arguments.toMutableList()
                    updatedArguments[index] = expandedArgument
                    expandedPatterns += pattern.copy(arguments = updatedArguments)
                }
            }
        }

        return expandedPatterns
    }

    private fun matchAliasPattern(
        concrete: TypePattern,
        aliasPattern: TypePattern,
        bindings: Map<String, TypePattern> = emptyMap()
    ): Map<String, TypePattern>? {
        return when {
            aliasPattern is TypePattern.Wildcard -> {
                val existing = bindings[aliasPattern.name]
                when {
                    existing == null -> bindings + (aliasPattern.name to concrete)
                    existing.asRenderedString() == concrete.asRenderedString() -> bindings
                    else -> null
                }
            }
            concrete is TypePattern.Named && aliasPattern is TypePattern.Named -> {
                if (concrete.name != aliasPattern.name || concrete.arguments.size != aliasPattern.arguments.size) {
                    return null
                }
                var currentBindings = bindings
                for ((concreteArgument, aliasArgument) in concrete.arguments.zip(aliasPattern.arguments)) {
                    currentBindings = matchAliasPattern(concreteArgument, aliasArgument, currentBindings) ?: return null
                }
                currentBindings
            }
            concrete is TypePattern.Raw && aliasPattern is TypePattern.Raw ->
                bindings.takeIf { concrete.text == aliasPattern.text }
            concrete is TypePattern.Named && aliasPattern is TypePattern.Raw ->
                bindings.takeIf { concrete.rendered == aliasPattern.text }
            concrete is TypePattern.Raw && aliasPattern is TypePattern.Named ->
                bindings.takeIf { concrete.text == aliasPattern.rendered }
            else -> null
        }
    }

    private fun substituteAliasPattern(
        pattern: TypePattern,
        bindings: Map<String, TypePattern>
    ): TypePattern =
        when (pattern) {
            is TypePattern.Named ->
                pattern.copy(arguments = pattern.arguments.map { substituteAliasPattern(it, bindings) })
            is TypePattern.Wildcard ->
                bindings[pattern.name] ?: pattern
            is TypePattern.Raw -> pattern
        }

    private fun collectGlobalTypeAliases(
        anchor: PsiElement,
        typeName: String
    ): List<AikenTypeAliasEntry> {
        if (typeName.isBlank() || DumbService.isDumb(anchor.project)) return emptyList()

        val scope = AikenSearchScopes.forElement(anchor)
        val index = FileBasedIndex.getInstance()
        val seenFiles = LinkedHashSet<VirtualFile>()
        val aliases = LinkedHashSet<AikenTypeAliasEntry>()

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

    private fun collectVisibleTypeAliases(anchor: PsiElement): List<AikenTypeAliasEntry> {
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

    private fun extractTypeAliases(text: String): List<AikenTypeAliasEntry> {
        val results = ArrayList<AikenTypeAliasEntry>()
        val aliasRegex =
            Regex(
                pattern = """(?m)^\s*(?:pub\s+)?(?:opaque\s+)?type\s+([A-Z][A-Za-z0-9_]*(?:<[^=\n]+>)?)\s*=\s*([^\n/]+?)\s*(?://.*)?$"""
            )
        for (match in aliasRegex.findAll(text)) {
            val alias = match.groupValues[1].trim()
            val targetType = match.groupValues[2].trim()
            if (alias.isNotEmpty() && targetType.isNotEmpty()) {
                results += AikenTypeAliasEntry(alias, targetType)
            }
        }
        return results
    }

    private data class ExpectedTypeContext(
        val expectedType: String,
        val isSpread: Boolean
    )

    private data class InferredListLiteralContext(
        val expectedListType: String,
        val currentSegment: AikenCurrentExpressionSegment
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

}
