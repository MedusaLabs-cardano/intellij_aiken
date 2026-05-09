package com.medusalabs.aiken.parameterinfo

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.completion.AikenSyntaxText
import com.medusalabs.aiken.completion.AikenValidatorNamespaceSupport
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_FUNCTION_SIGNATURE_INDEX_NAME
import com.medusalabs.aiken.index.aikenFunctionSignatureModuleKey
import com.medusalabs.aiken.index.aikenFunctionSignatureNameKey
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

private const val AIKEN_PIPE_IMPLICIT_ARGUMENT_MARKER = "\u0000PIPE"
private val AIKEN_CALLABLE_BINDING_TARGET_PATTERN =
    Regex("""(?:=|->)\s*([A-Za-z_][A-Za-z0-9_]*)(?:\s*\.\s*([A-Za-z_][A-Za-z0-9_]*))?""")
private val AIKEN_CALLABLE_BRANCH_TARGET_PATTERN =
    Regex("""\{\s*([A-Za-z_][A-Za-z0-9_]*)(?:\s*\.\s*([A-Za-z_][A-Za-z0-9_]*))?(?=\s*[(}])""")
private val AIKEN_CALLABLE_BINDING_IGNORED_KEYWORDS =
    setOf("when", "if", "fn", "expect", "let", "todo", "fail", "trace")

class AikenParameterInfoHandler : ParameterInfoHandler<PsiElement, AikenParameterInfoHandler.SignatureItem> {
    data class SignatureItem(
        val signature: String,
        val parameterRanges: List<Range>
    )

    private data class CallableTarget(
        val symbolName: String,
        val qualifier: String?,
        val returnedCallableDepth: Int = 0
    )

    data class Range(val start: Int, val end: Int)

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val file = context.file
        val offset = context.offset.coerceIn(0, file.textLength)

        val hasRelevantLeaf = (
            sequenceOf(
                (offset - 1).coerceAtLeast(0),
                offset
            ).distinct()
                .mapNotNull { candidate ->
                    if (candidate >= file.textLength) null else file.findElementAt(candidate)
                }
                .firstOrNull { !isIgnoredLeaf(it) }
                != null
            )
        if (!hasRelevantLeaf) return null

        val call = findCallContext(file.text, offset) ?: return null
        return file.findElementAt(call.openParenOffset)
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        val file = context.file
        val openParenOffset = element.textRange.startOffset
        val call = findCallContext(file.text, openParenOffset + 1) ?: return

        val signatures = collectSignatures(file, call)
        if (signatures.isEmpty()) return

        context.itemsToShow = signatures.toTypedArray()
        context.showHint(element, openParenOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val file = context.file
        val offset = context.offset.coerceIn(0, file.textLength)

        val call = findCallContext(file.text, offset) ?: return null
        return file.findElementAt(call.openParenOffset)
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val file = context.file
        val offset = context.offset.coerceIn(0, file.textLength)

        val openParenOffset = parameterOwner.textRange.startOffset
        val call = findCallContext(file.text, openParenOffset + 1) ?: run {
            context.removeHint()
            return
        }
        val argumentIndex = computeArgumentIndex(file.text, openParenOffset, offset) ?: run {
            context.removeHint()
            return
        }

        context.setCurrentParameter(argumentIndex + computeImplicitArgumentShift(file, call))
    }

    override fun updateUI(p: SignatureItem, context: ParameterInfoUIContext) {
        val currentIndex = context.currentParameterIndex
        val highlight =
            if (currentIndex in p.parameterRanges.indices) p.parameterRanges[currentIndex]
            else null

        val highlightStart = highlight?.start ?: 0
        val highlightEnd = highlight?.end ?: 0

        context.setupUIComponentPresentation(
            p.signature,
            highlightStart,
            highlightEnd,
            false,
            false,
            false,
            context.defaultParameterColor
        )
    }

    private fun collectSignatures(file: PsiFile, call: CallContext): List<SignatureItem> {
        val anchorFile = file.virtualFile
        val fileText = file.text
        val sameFileSignatures = AikenFunctionSignatureExtractor.extract(fileText)
        val scope = AikenSearchScopes.forFile(file.project, anchorFile)
        val index = FileBasedIndex.getInstance()
        call.inlineSignature
            ?.let { adaptSignatureForCallShape(it, call.returnedCallableDepth, call.capturedArgumentPositions) }
            ?.let { return signatureItems(setOf(it)) }
        call.groupedExpression
            ?.let {
                adaptSignaturesForCallShape(
                    resolveCallableExpressionSignatures(index, scope, anchorFile, sameFileSignatures, fileText, it),
                    call.returnedCallableDepth,
                    call.capturedArgumentPositions
                )
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return signatureItems(it) }

        if (call.qualifier == null) {
            val currentModuleKeys = collectCurrentModuleSignatureKeys(anchorFile, call.calleeName)
            val currentModuleIndexed =
                adaptSignaturesForCallShape(
                    loadIndexedSignatures(index, scope, currentModuleKeys),
                    call.returnedCallableDepth,
                    call.capturedArgumentPositions
                )
            if (currentModuleIndexed.isNotEmpty()) {
                return signatureItems(currentModuleIndexed)
            }

            val currentFileSignature =
                sameFileSignatures[call.calleeName]
                    ?.let { adaptSignatureForCallShape(it, call.returnedCallableDepth, call.capturedArgumentPositions) }
            if (currentFileSignature != null) {
                return signatureItems(setOf(currentFileSignature))
            }

            val directLocalBindingSignatures =
                collectDirectLocalBindingSignatures(index, scope, anchorFile, sameFileSignatures, fileText, file, call)
            if (directLocalBindingSignatures.isNotEmpty()) {
                return signatureItems(directLocalBindingSignatures)
            }

            val localBindingTargets = collectCallableTargetsFromLocalBinding(file, call)
            if (localBindingTargets.isNotEmpty()) {
                val localBindingSignatures = LinkedHashSet<String>()
                for (target in localBindingTargets) {
                    localBindingSignatures +=
                        collectTargetSignatures(
                            index,
                            scope,
                            anchorFile,
                            sameFileSignatures,
                            fileText,
                            target.copy(returnedCallableDepth = target.returnedCallableDepth + call.returnedCallableDepth)
                        )
                }
                if (localBindingSignatures.isNotEmpty()) {
                    return signatureItems(localBindingSignatures)
                }
            }
        }

        val importedIndexed =
            adaptSignaturesForCallShape(
                collectTargetSignatures(
                    index,
                    scope,
                    anchorFile,
                    sameFileSignatures,
                    fileText,
                    CallableTarget(call.calleeName, call.qualifier)
                ),
                call.returnedCallableDepth,
                call.capturedArgumentPositions
            )
        if (importedIndexed.isNotEmpty()) {
            return signatureItems(importedIndexed)
        }

        val fallbackIndexed =
            adaptSignaturesForCallShape(
                index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, aikenFunctionSignatureNameKey(call.calleeName), scope)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
                call.returnedCallableDepth,
                call.capturedArgumentPositions
            )
        if (fallbackIndexed.isNotEmpty()) {
            return signatureItems(fallbackIndexed)
        }

        val local =
            sameFileSignatures[call.calleeName]
                ?.let { adaptSignatureForCallShape(it, call.returnedCallableDepth, call.capturedArgumentPositions) }
        return if (local != null) {
            signatureItems(setOf(local))
        } else {
            emptyList()
        }
    }

    private data class CallContext(
        val openParenOffset: Int,
        val callableStartOffset: Int,
        val calleeOffset: Int,
        val calleeName: String,
        val qualifier: String?,
        val implicitArgumentCount: Int,
        val returnedCallableDepth: Int,
        val inlineSignature: String?,
        val groupedExpression: String?,
        val capturedArgumentPositions: List<Int>,
        val directPlaceholderPositions: List<Int>,
        val visibleArgumentCount: Int
    )

    private fun findCallContext(text: CharSequence, offset: Int): CallContext? {
        val openParenOffset = findOpenParenForOffset(text, offset) ?: return null

        val callee = findCalleeBeforeParen(text, openParenOffset) ?: return null
        if (callee.name.isEmpty() && callee.inlineSignature == null && callee.groupedExpression == null) return null

        // If we already have the closing paren and caret is after it, do not show.
        val closeParenOffset = findMatchingParen(text, openParenOffset)
        if (closeParenOffset != null && offset > closeParenOffset) return null

        val arguments = splitTopLevelArguments(text.toString(), openParenOffset).orEmpty()

        return CallContext(
            openParenOffset = openParenOffset,
            callableStartOffset = callee.callableStartOffset,
            calleeOffset = callee.offset,
            calleeName = callee.name,
            qualifier = callee.qualifier,
            implicitArgumentCount =
                if (callee.groupedExpression != null) 0 else detectPipeImplicitArgumentCount(text, callee.callableStartOffset),
            returnedCallableDepth = callee.returnedCallableDepth,
            inlineSignature = callee.inlineSignature,
            groupedExpression = callee.groupedExpression,
            capturedArgumentPositions = callee.capturedArgumentPositions,
            directPlaceholderPositions = collectPlaceholderPositions(arguments),
            visibleArgumentCount = countVisibleArguments(arguments)
        )
    }

    private fun computeImplicitArgumentShift(file: PsiFile, call: CallContext): Int {
        if (call.implicitArgumentCount == 0) return 0
        if (call.directPlaceholderPositions.isNotEmpty()) return 0

        val signatures = collectSignatures(file, call)
        if (signatures.isEmpty()) return call.implicitArgumentCount

        return if (signatures.all { it.parameterRanges.size > call.visibleArgumentCount }) {
            call.implicitArgumentCount
        } else {
            0
        }
    }

    private data class Callee(
        val name: String,
        val qualifier: String?,
        val offset: Int,
        val callableStartOffset: Int,
        val returnedCallableDepth: Int,
        val inlineSignature: String?,
        val groupedExpression: String?,
        val capturedArgumentPositions: List<Int>
    )

    private fun findCalleeBeforeParen(text: CharSequence, openParenOffset: Int): Callee? {
        var i = openParenOffset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return null

        if (text[i] == ')') {
            val previousOpenParen = findOpenParenForClose(text, i) ?: return null
            val previousCallee = findCalleeBeforeParen(text, previousOpenParen)
            if (previousCallee == null) {
                return findGroupedExpressionCallee(text, previousOpenParen, i)
            }
            val placeholderPositions =
                collectPlaceholderPositions(splitTopLevelArguments(text.toString(), previousOpenParen).orEmpty())

            return if (placeholderPositions.isNotEmpty()) {
                previousCallee.copy(capturedArgumentPositions = placeholderPositions)
            } else {
                previousCallee.copy(returnedCallableDepth = previousCallee.returnedCallableDepth + 1)
            }
        }

        if (text[i] == '}') {
            return findInlineLambdaCallee(text, i)
        }

        val end = i + 1
        while (i >= 0 && isIdentifierChar(text[i])) i--
        val start = i + 1
        if (start >= end) return null

        val name = text.subSequence(start, end).toString()

        val qualifier = AikenSyntaxText.qualifiedChainBeforeOffset(text, start)
        val callableStartOffset = qualifier?.let { start - it.length - 1 } ?: start

        return Callee(name, qualifier, start, callableStartOffset, 0, null, null, emptyList())
    }

    private fun detectPipeImplicitArgumentCount(text: CharSequence, callableStartOffset: Int): Int {
        var i = callableStartOffset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 1) return 0

        return if (text[i] == '>' && text[i - 1] == '|') 1 else 0
    }

    private fun findGroupedExpressionCallee(
        text: CharSequence,
        groupOpenParenOffset: Int,
        groupCloseParenOffset: Int
    ): Callee? {
        val expression = text.subSequence(groupOpenParenOffset + 1, groupCloseParenOffset).toString().trim()
        if (expression.isEmpty()) return null
        return Callee(
            name = "",
            qualifier = null,
            offset = groupOpenParenOffset + 1,
            callableStartOffset = groupOpenParenOffset,
            returnedCallableDepth = 0,
            inlineSignature = null,
            groupedExpression = expression,
            capturedArgumentPositions = emptyList()
        )
    }

    private fun findInlineLambdaCallee(text: CharSequence, closeBraceOffset: Int): Callee? {
        val openBraceOffset = findOpenBraceForClose(text, closeBraceOffset) ?: return null

        var i = openBraceOffset - 1
        var lambdaStart: Int? = null
        while (i >= 0) {
            if (text[i] == ')') {
                val paramsOpen = findOpenParenForClose(text, i) ?: return null
                var j = paramsOpen - 1
                while (j >= 0 && text[j].isWhitespace()) j--
                val wordEnd = j + 1
                while (j >= 0 && isIdentifierChar(text[j])) j--
                val wordStart = j + 1
                if (
                    wordStart < wordEnd &&
                    text.subSequence(wordStart, wordEnd).toString() == "fn" &&
                    findLambdaBodyOpenBrace(text, wordStart) == openBraceOffset
                ) {
                    lambdaStart = wordStart
                }
                i = paramsOpen - 1
                continue
            }
            i--
        }

        val inlineSignature = lambdaStart?.let { extractLambdaSignature(text.toString(), it) } ?: return null
        return Callee(
            name = "",
            qualifier = null,
            offset = lambdaStart,
            callableStartOffset = lambdaStart,
            returnedCallableDepth = 0,
            inlineSignature = inlineSignature,
            groupedExpression = null,
            capturedArgumentPositions = emptyList()
        )
    }

    private fun findLambdaBodyOpenBrace(text: CharSequence, fnStart: Int): Int? {
        if (!text.startsWith("fn", fnStart)) return null

        var i = fnStart + 2
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '(') return null

        val closeParen = findMatchingParen(text, i) ?: return null
        i = closeParen + 1
        while (i < text.length && text[i].isWhitespace()) i++

        if (i + 1 < text.length && text[i] == '-' && text[i + 1] == '>') {
            i += 2
            while (i < text.length) {
                val ch = text[i]
                if (ch == '{') return i
                if (ch == '\n' || ch == '\r') return null
                i++
            }
            return null
        }

        return if (i < text.length && text[i] == '{') i else null
    }

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun isIgnoredLeaf(element: PsiElement): Boolean {
        val type = element.node.elementType
        return type == AikenTokenTypes.COMMENT || type == AikenTokenTypes.STRING || type == TokenType.WHITE_SPACE
    }

    private fun loadIndexedSignatures(
        index: FileBasedIndex,
        scope: com.intellij.psi.search.GlobalSearchScope,
        keys: Iterable<String>
    ): Set<String> =
        keys
            .asSequence()
            .flatMap { key -> index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, key, scope).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun collectCurrentModuleSignatureKeys(
        anchorFile: VirtualFile?,
        calleeName: String
    ): LinkedHashSet<String> {
        val keys = LinkedHashSet<String>()

        val currentModulePath = AikenModulePath.fromFile(anchorFile)
        if (!currentModulePath.isNullOrBlank()) {
            keys += aikenFunctionSignatureModuleKey(currentModulePath, calleeName)
        }

        return keys
    }

    private fun collectImportedSignatureKeys(
        fileText: CharSequence,
        target: CallableTarget
    ): LinkedHashSet<String> {
        val keys = LinkedHashSet<String>()
        val importTargets =
            AikenUseStatementParser.parseModel(fileText).resolveCallableTargets(target.symbolName, target.qualifier)
        for (target in importTargets) {
            keys += aikenFunctionSignatureModuleKey(target.modulePath, target.symbolName)
        }

        return keys
    }

    private fun collectTargetSignatures(
        index: FileBasedIndex,
        scope: com.intellij.psi.search.GlobalSearchScope,
        anchorFile: VirtualFile?,
        sameFileSignatures: Map<String, String>,
        fileText: CharSequence,
        target: CallableTarget
    ): Set<String> {
        if (target.qualifier == null) {
            val currentModuleIndexed =
                adaptSignaturesForCallShape(
                    loadIndexedSignatures(index, scope, collectCurrentModuleSignatureKeys(anchorFile, target.symbolName)),
                    target.returnedCallableDepth,
                    emptyList()
                )
            if (currentModuleIndexed.isNotEmpty()) return currentModuleIndexed

            sameFileSignatures[target.symbolName]
                ?.let { adaptSignatureForCallShape(it, target.returnedCallableDepth, emptyList()) }
                ?.let { return setOf(it) }
        }

        val importedIndexed =
            adaptSignaturesForCallShape(
                loadIndexedSignatures(index, scope, collectImportedSignatureKeys(fileText, target)),
                target.returnedCallableDepth,
                emptyList()
            )
        if (importedIndexed.isNotEmpty()) return importedIndexed

        val importedValidatorHandlers =
            target.qualifier
                ?.let { qualifierChain ->
                    val useModel = AikenUseStatementParser.parseModel(fileText)
                    AikenValidatorNamespaceSupport.importedValidatorHandlerSignatures(
                        anchorFile = anchorFile,
                        useModel = useModel,
                        qualifierChain = qualifierChain,
                        handlerName = target.symbolName
                    )
                }
                .orEmpty()
        if (importedValidatorHandlers.isNotEmpty()) {
            return adaptSignaturesForCallShape(importedValidatorHandlers, target.returnedCallableDepth, emptyList())
        }

        return emptySet()
    }

    private fun collectDirectLocalBindingSignatures(
        index: FileBasedIndex,
        scope: com.intellij.psi.search.GlobalSearchScope,
        anchorFile: VirtualFile?,
        sameFileSignatures: Map<String, String>,
        fileText: CharSequence,
        file: PsiFile,
        call: CallContext
    ): Set<String> {
        val bindingSlice = findLocalBindingSlice(file, call) ?: return emptySet()
        return resolveAssignedExpressionSignatures(
            index,
            scope,
            anchorFile,
            sameFileSignatures,
            fileText,
            bindingSlice
        ).mapNotNullTo(LinkedHashSet()) { signature ->
            adaptSignatureForCallShape(signature, call.returnedCallableDepth, emptyList())
        }
    }

    private fun findLocalBindingSlice(file: PsiFile, call: CallContext): String? {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val declarationOffset =
            AikenLocalScopeAnalyzer.findDeclarationOffset(document, call.calleeName, call.calleeOffset) ?: return null
        if (declarationOffset >= call.calleeOffset) return null

        return file.text.substring(declarationOffset, call.calleeOffset)
    }

    private fun collectCallableTargetsFromLocalBinding(file: PsiFile, call: CallContext): Set<CallableTarget> {
        val bindingSlice = findLocalBindingSlice(file, call) ?: return emptySet()
        val targets = LinkedHashSet<CallableTarget>()
        AIKEN_CALLABLE_BINDING_TARGET_PATTERN
            .findAll(bindingSlice)
            .mapNotNull { callableTargetFromMatch(bindingSlice, it) }
            .forEach(targets::add)
        AIKEN_CALLABLE_BRANCH_TARGET_PATTERN
            .findAll(bindingSlice)
            .mapNotNull { callableTargetFromMatch(bindingSlice, it) }
            .forEach(targets::add)
        return targets
    }

    private fun callableTargetFromMatch(bindingSlice: String, match: MatchResult): CallableTarget? {
        val first = match.groupValues[1]
        val second = match.groupValues[2].ifBlank { null }
        if (first in AIKEN_CALLABLE_BINDING_IGNORED_KEYWORDS) return null

        val returnedCallableDepth = countImmediateCallSuffixes(bindingSlice, match.range.last + 1)

        return if (second != null) {
            CallableTarget(symbolName = second, qualifier = first, returnedCallableDepth = returnedCallableDepth)
        } else {
            CallableTarget(symbolName = first, qualifier = null, returnedCallableDepth = returnedCallableDepth)
        }
    }

    private fun resolveAssignedExpressionSignatures(
        index: FileBasedIndex,
        scope: com.intellij.psi.search.GlobalSearchScope,
        anchorFile: VirtualFile?,
        sameFileSignatures: Map<String, String>,
        fileText: CharSequence,
        bindingSlice: String
    ): Set<String> {
        val expression = extractAssignedExpression(bindingSlice) ?: return emptySet()
        return resolveCallableExpressionSignatures(index, scope, anchorFile, sameFileSignatures, fileText, expression)
    }

    private fun extractAssignedExpression(bindingSlice: String): String? {
        val assignmentIndex = bindingSlice.indexOf('=')
        if (assignmentIndex < 0 || assignmentIndex + 1 >= bindingSlice.length) return null
        return bindingSlice.substring(assignmentIndex + 1).trim().takeIf { it.isNotEmpty() }
    }

    private fun extractLambdaSignature(text: String, fnStart: Int): String? {
        if (!text.startsWith("fn", fnStart)) return null

        var i = fnStart + 2
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '(') return null

        val openParen = i
        val closeParen = findMatchingParen(text, openParen) ?: return null
        val params = text.substring(openParen, closeParen + 1)

        i = closeParen + 1
        while (i < text.length && text[i].isWhitespace()) i++

        val returnType =
            if (i + 1 < text.length && text[i] == '-' && text[i + 1] == '>') {
                i += 2
                while (i < text.length && text[i].isWhitespace()) i++
                val start = i
                while (i < text.length) {
                    val ch = text[i]
                    if (ch == '{' || ch == '\n' || ch == '\r') break
                    i++
                }
                text.substring(start, i).trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }

        return buildString {
            append("fn")
            append(params)
            if (returnType != null) {
                append(" -> ")
                append(returnType)
            }
        }
    }

    private fun resolveCallableExpressionSignatures(
        index: FileBasedIndex,
        scope: com.intellij.psi.search.GlobalSearchScope,
        anchorFile: VirtualFile?,
        sameFileSignatures: Map<String, String>,
        fileText: CharSequence,
        expression: String
    ): Set<String> {
        var current = expression.trim()
        if (current.isEmpty()) return emptySet()

        while (true) {
            val unwrapped = unwrapWholeParenthesizedExpression(current)
            if (unwrapped == current) break
            current = unwrapped.trim()
        }

        extractLambdaSignature(current, 0)?.let { return setOf(it) }

        splitTopLevelPipe(current)?.let { (_, right) ->
            return resolvePipeExpressionSignatures(index, scope, anchorFile, sameFileSignatures, fileText, right)
        }

        val callChain = splitTrailingCallSuffixes(current)
        if (callChain != null) {
            var signatures =
                resolveCallableExpressionSignatures(
                    index,
                    scope,
                    anchorFile,
                    sameFileSignatures,
                    fileText,
                    callChain.baseExpression
                )
            for (arguments in callChain.argumentLists) {
                signatures = applyCallArguments(signatures, arguments)
                if (signatures.isEmpty()) return emptySet()
            }
            return signatures
        }

        directSymbolTarget(current)?.let { target ->
            return collectTargetSignatures(index, scope, anchorFile, sameFileSignatures, fileText, target)
        }

        return emptySet()
    }

    private fun resolvePipeExpressionSignatures(
        index: FileBasedIndex,
        scope: com.intellij.psi.search.GlobalSearchScope,
        anchorFile: VirtualFile?,
        sameFileSignatures: Map<String, String>,
        fileText: CharSequence,
        rightExpression: String
    ): Set<String> {
        val callChain = splitTrailingCallSuffixes(rightExpression)
        if (callChain == null) {
            val rightSignatures =
                resolveCallableExpressionSignatures(index, scope, anchorFile, sameFileSignatures, fileText, rightExpression)
            return applyCallArguments(rightSignatures, listOf(AIKEN_PIPE_IMPLICIT_ARGUMENT_MARKER))
        }

        val baseSignatures =
            resolveCallableExpressionSignatures(
                index,
                scope,
                anchorFile,
                sameFileSignatures,
                fileText,
                callChain.baseExpression
            )
        if (baseSignatures.isEmpty()) return emptySet()

        val firstArguments = callChain.argumentLists.firstOrNull().orEmpty()
        val insertPipeCandidates = LinkedHashSet<String>()
        val applyAfterCallCandidates = LinkedHashSet<String>()

        for (signature in baseSignatures) {
            val arity = computeParameterRanges(signature).size
            if (firstArguments.size < arity) {
                insertPipeCandidates += signature
            } else {
                applyAfterCallCandidates += signature
            }
        }

        val signatures = LinkedHashSet<String>()

        if (insertPipeCandidates.isNotEmpty()) {
            var inserted = applyCallArguments(insertPipeCandidates, listOf(AIKEN_PIPE_IMPLICIT_ARGUMENT_MARKER) + firstArguments)
            for (arguments in callChain.argumentLists.drop(1)) {
                inserted = applyCallArguments(inserted, arguments)
                if (inserted.isEmpty()) break
            }
            signatures += inserted
        }

        if (applyAfterCallCandidates.isNotEmpty()) {
            var applied = applyCallArguments(applyAfterCallCandidates, firstArguments)
            if (applied.isNotEmpty()) {
                applied = applyCallArguments(applied, listOf(AIKEN_PIPE_IMPLICIT_ARGUMENT_MARKER))
                for (arguments in callChain.argumentLists.drop(1)) {
                    applied = applyCallArguments(applied, arguments)
                    if (applied.isEmpty()) break
                }
                signatures += applied
            }
        }

        return signatures
    }

    private fun applyCallArguments(signatures: Set<String>, arguments: List<String>): Set<String> {
        if (signatures.isEmpty()) return emptySet()

        val visibleArguments = if (arguments.size == 1 && arguments[0].isBlank()) emptyList() else arguments
        val placeholderPositions = collectPlaceholderPositions(visibleArguments)

        return signatures
            .asSequence()
            .mapNotNull { signature ->
                val arity = computeParameterRanges(signature).size
                if (visibleArguments.size != arity) {
                    null
                } else if (placeholderPositions.isNotEmpty()) {
                    adaptSignatureForPartialApplication(signature, placeholderPositions)
                } else {
                    extractReturnedCallableSignature(signature)
                }
            }
            .toSet()
    }

    private fun splitTopLevelPipe(expression: String): Pair<String, String>? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var splitIndex = -1
        var i = 0

        while (i + 1 < expression.length) {
            val ch = expression[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < expression.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && expression[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '|' -> {
                    if (
                        expression[i + 1] == '>' &&
                        parenDepth == 0 &&
                        bracketDepth == 0 &&
                        braceDepth == 0
                    ) {
                        splitIndex = i
                    }
                }
            }

            i++
        }

        if (splitIndex < 0) return null

        val left = expression.substring(0, splitIndex).trim()
        val right = expression.substring(splitIndex + 2).trim()
        return if (left.isNotEmpty() && right.isNotEmpty()) left to right else null
    }

    private data class CallChain(
        val baseExpression: String,
        val argumentLists: List<List<String>>
    )

    private fun splitTrailingCallSuffixes(expression: String): CallChain? {
        var current = expression.trim()
        val suffixes = ArrayList<List<String>>()

        while (current.isNotEmpty()) {
            var end = current.length - 1
            while (end >= 0 && current[end].isWhitespace()) end--
            if (end < 0 || current[end] != ')') break

            val openParen = findOpenParenForClose(current, end) ?: break
            val arguments = splitTopLevelArguments(current, openParen) ?: break
            suffixes += arguments
            current = current.substring(0, openParen).trimEnd()
        }

        if (suffixes.isEmpty()) return null
        return CallChain(current.trim(), suffixes.asReversed())
    }

    private fun unwrapWholeParenthesizedExpression(expression: String): String {
        val trimmed = expression.trim()
        if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) return trimmed
        val closeParen = findMatchingParen(trimmed, 0) ?: return trimmed
        return if (closeParen == trimmed.lastIndex) trimmed.substring(1, trimmed.length - 1) else trimmed
    }

    private fun directSymbolTarget(expression: String): CallableTarget? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null
        val lastSegment = trimmed.substringAfterLast('.').trim()
        if (lastSegment.isEmpty() || !lastSegment.all(::isIdentifierChar)) return null
        val qualifier = trimmed.substringBeforeLast('.', "").trim().takeIf { it.isNotEmpty() }
        if (qualifier != null && qualifier.split('.').any { segment -> segment.isBlank() || !segment.all(::isIdentifierChar) }) {
            return null
        }
        return CallableTarget(lastSegment, qualifier)
    }

    private fun splitTopLevelArguments(text: String, openParenOffset: Int): List<String>? {
        val closeParenOffset = findMatchingParen(text, openParenOffset) ?: return null
        val segments = ArrayList<String>()

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var segmentStart = openParenOffset + 1
        var i = openParenOffset + 1

        while (i < closeParenOffset) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < closeParenOffset) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < closeParenOffset && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        segments += text.substring(segmentStart, i)
                        segmentStart = i + 1
                    }
                }
            }

            i++
        }

        segments += text.substring(segmentStart, closeParenOffset)
        return segments
    }

    private fun collectPlaceholderPositions(arguments: List<String>): List<Int> =
        arguments.mapIndexedNotNull { index, argument ->
            if (argument.trim() == "_") index else null
        }

    private fun countVisibleArguments(arguments: List<String>): Int =
        if (arguments.size == 1 && arguments[0].isBlank()) 0 else arguments.size

    private fun countImmediateCallSuffixes(text: String, startOffset: Int): Int {
        var i = startOffset
        var depth = 0

        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '(') return depth

            val closeParen = findMatchingParen(text, i) ?: return depth
            depth++
            i = closeParen + 1
        }
    }

    private fun adaptSignaturesForCallShape(
        signatures: Set<String>,
        returnedCallableDepth: Int,
        capturedArgumentPositions: List<Int>
    ): Set<String> =
        if (returnedCallableDepth <= 0 && capturedArgumentPositions.isEmpty()) {
            signatures
        } else {
            signatures
                .asSequence()
                .mapNotNull { adaptSignatureForCallShape(it, returnedCallableDepth, capturedArgumentPositions) }
                .toSet()
        }

    private fun adaptSignatureForCallShape(
        signature: String,
        returnedCallableDepth: Int,
        capturedArgumentPositions: List<Int>
    ): String? {
        var current =
            if (capturedArgumentPositions.isNotEmpty()) {
                adaptSignatureForPartialApplication(signature, capturedArgumentPositions) ?: return null
            } else {
                signature
            }

        if (returnedCallableDepth <= 0) return current

        repeat(returnedCallableDepth) {
            current = extractReturnedCallableSignature(current) ?: return null
        }
        return current
    }

    private fun extractReturnedCallableSignature(signature: String): String? {
        val openParen = signature.indexOf('(')
        if (openParen < 0) return null

        val closeParen = findMatchingParen(signature, openParen) ?: return null
        var i = closeParen + 1
        while (i < signature.length && signature[i].isWhitespace()) i++
        if (i + 1 >= signature.length || signature[i] != '-' || signature[i + 1] != '>') return null

        val returnType = signature.substring(i + 2).trim()
        return if (returnType.startsWith("fn")) returnType else null
    }

    private fun adaptSignatureForPartialApplication(signature: String, placeholderPositions: List<Int>): String? {
        if (placeholderPositions.isEmpty()) return null

        val parameterRanges = computeParameterRanges(signature)
        if (placeholderPositions.any { it !in parameterRanges.indices }) return null

        val selectedParams =
            placeholderPositions.map { position ->
                val range = parameterRanges[position]
                signature.substring(range.start, range.end)
            }

        val returnType = extractSignatureReturnType(signature)
        return buildString {
            append("fn(")
            append(selectedParams.joinToString(", "))
            append(")")
            if (!returnType.isNullOrBlank()) {
                append(" -> ")
                append(returnType)
            }
        }
    }

    private fun extractSignatureReturnType(signature: String): String? {
        val openParen = signature.indexOf('(')
        if (openParen < 0) return null

        val closeParen = findMatchingParen(signature, openParen) ?: return null
        var i = closeParen + 1
        while (i < signature.length && signature[i].isWhitespace()) i++
        if (i + 1 >= signature.length || signature[i] != '-' || signature[i + 1] != '>') return null

        return signature.substring(i + 2).trim().takeIf { it.isNotEmpty() }
    }

    private fun signatureItems(signatures: Set<String>): List<SignatureItem> =
        signatures
            .asSequence()
            .sorted()
            .map { SignatureItem(it, computeParameterRanges(it)) }
            .toList()

    private fun findOpenParenForOffset(text: CharSequence, offset: Int): Int? {
        val stack = ArrayDeque<Int>()

        var inString = false
        var inLineComment = false

        var i = 0
        val limit = offset.coerceIn(0, text.length)
        while (i < limit) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < limit) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < limit && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '(' -> stack.addLast(i)
                ')' -> if (stack.isNotEmpty()) stack.removeLast()
            }

            i++
        }

        return stack.lastOrNull()
    }

    private fun findOpenParenForClose(text: CharSequence, closeParenOffset: Int): Int? {
        if (closeParenOffset !in 0 until text.length) return null
        if (text[closeParenOffset] != ')') return null

        val stack = ArrayDeque<Int>()
        var inString = false
        var inLineComment = false

        var i = 0
        while (i <= closeParenOffset) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 <= closeParenOffset) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 <= closeParenOffset && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '(' -> stack.addLast(i)
                ')' -> {
                    val openParen = if (stack.isNotEmpty()) stack.removeLast() else return null
                    if (i == closeParenOffset) return openParen
                }
            }

            i++
        }

        return null
    }

    private fun findOpenBraceForClose(text: CharSequence, closeBraceOffset: Int): Int? {
        if (closeBraceOffset !in 0 until text.length) return null
        if (text[closeBraceOffset] != '}') return null

        val stack = ArrayDeque<Int>()
        var inString = false
        var inLineComment = false

        var i = 0
        while (i <= closeBraceOffset) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 <= closeBraceOffset) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 <= closeBraceOffset && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '{' -> stack.addLast(i)
                '}' -> {
                    val openBrace = if (stack.isNotEmpty()) stack.removeLast() else return null
                    if (i == closeBraceOffset) return openBrace
                }
            }

            i++
        }

        return null
    }

    private fun computeArgumentIndex(text: CharSequence, openParenOffset: Int, offset: Int): Int? {
        if (openParenOffset !in 0 until text.length) return null
        if (text[openParenOffset] != '(') return null

        var inString = false
        var inLineComment = false

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        var index = 0
        var i = openParenOffset + 1
        val limit = offset.coerceIn(0, text.length)
        while (i < limit) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < limit) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < limit && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
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

            i++
        }

        return index
    }

    private fun findMatchingParen(text: CharSequence, openIndex: Int): Int? {
        if (openIndex !in 0 until text.length) return null
        if (text[openIndex] != '(') return null

        var inString = false
        var inLineComment = false
        var depth = 0

        var i = openIndex
        while (i < text.length) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < text.length && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }

            if (ch == '"') {
                inString = true
                i++
                continue
            }

            when (ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }

        return null
    }

    private fun computeParameterRanges(signature: String): List<Range> {
        val openIndex = signature.indexOf('(')
        if (openIndex < 0) return emptyList()
        val closeIndex = findMatchingParen(signature, openIndex) ?: return emptyList()

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0

        val ranges = ArrayList<Range>()
        var segmentStart = openIndex + 1
        var i = openIndex + 1
        while (i < closeIndex) {
            val ch = signature[i]
            when (ch) {
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
                        addTrimmedRange(signature, segmentStart, i, ranges)
                        segmentStart = i + 1
                    }
                }
            }
            i++
        }

        addTrimmedRange(signature, segmentStart, closeIndex, ranges)

        return ranges.filter { it.start < it.end }
    }

    private fun addTrimmedRange(text: String, start: Int, endExclusive: Int, out: MutableList<Range>) {
        var s = start
        var e = endExclusive
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        out.add(Range(s, e))
    }
}
