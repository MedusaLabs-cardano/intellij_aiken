package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.medusalabs.aiken.index.aikenFunctionSignatureReturnType

internal object AikenTypedLookupFactory {
    internal data class FunctionCallTemplate(
        val text: String,
        val caretOffset: Int?,
        val shouldTriggerAutoPopup: Boolean
    )

    private data class StandardLookupSpec(
        val text: String,
        val kind: CompletionSymbolKind,
        val typeText: String,
        val insertionFamily: AikenTypedInsertionFamily,
        val rankingCategory: AikenTypedCompletionCategory? = null,
        val matchDistance: Int = 0,
        val tailText: String? = null,
        val lookupStrings: Set<String> = emptySet(),
        val bold: Boolean = false
    )

    fun createListLiteralLookup(expectedType: String): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = "[]",
                kind = CompletionSymbolKind.TYPE,
                typeText = expectedType,
                insertionFamily = AikenTypedInsertionFamily.ListLiteral,
                rankingCategory = AikenTypedCompletionCategory.LIST_LITERAL
            )
        )

    fun createOptionSomeLookup(expectedType: String): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = "Some()",
                kind = CompletionSymbolKind.TYPE,
                typeText = expectedType,
                insertionFamily = AikenTypedInsertionFamily.OptionSome,
                rankingCategory = AikenTypedCompletionCategory.OPTION_SOME
            )
        )

    fun createAutoImportedFunctionLookup(
        name: String,
        modulePath: String,
        signature: String,
        expectedType: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = name,
                kind = CompletionSymbolKind.FUNCTION,
                typeText = expectedType,
                insertionFamily =
                    AikenTypedInsertionFamily.FunctionCall(
                        name = name,
                        signature = signature,
                        autoImportTarget = AikenTypedAutoImportTarget(modulePath, name)
                    ),
                rankingCategory = AikenTypedCompletionCategory.UNIMPORTED_FUNCTION,
                tailText = " from $modulePath"
            )
        )

    fun createAutoImportedPipeFunctionLookup(
        name: String,
        modulePath: String,
        signature: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = name,
                kind = CompletionSymbolKind.FUNCTION,
                typeText = aikenFunctionSignatureReturnType(signature).orEmpty(),
                insertionFamily =
                    AikenTypedInsertionFamily.PipeCall(
                        lookupText = name,
                        signature = signature,
                        autoImportTarget = AikenTypedAutoImportTarget(modulePath, name)
                    ),
                rankingCategory = AikenTypedCompletionCategory.UNIMPORTED_FUNCTION,
                tailText = " from $modulePath"
            )
        )

    fun createFunctionLookup(
        name: String,
        signature: String,
        expectedType: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = name,
                kind = CompletionSymbolKind.FUNCTION,
                typeText = expectedType,
                insertionFamily =
                    AikenTypedInsertionFamily.FunctionCall(
                        name = name,
                        signature = signature
                    ),
                rankingCategory = AikenTypedCompletionCategory.LOCAL_FUNCTION
            )
        )

    fun createVisiblePipeFunctionLookup(
        lookupText: String,
        signature: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = lookupText,
                kind = CompletionSymbolKind.FUNCTION,
                typeText = aikenFunctionSignatureReturnType(signature).orEmpty(),
                insertionFamily =
                    AikenTypedInsertionFamily.PipeCall(
                        lookupText = lookupText,
                        signature = signature
                    ),
                rankingCategory = AikenTypedCompletionCategory.LOCAL_FUNCTION
            )
        )

    fun createQualifiedPipeVisibleFunctionLookup(
        lookupText: String,
        matchingName: String,
        signature: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = lookupText,
                kind = CompletionSymbolKind.FUNCTION,
                typeText = aikenFunctionSignatureReturnType(signature).orEmpty(),
                insertionFamily =
                    AikenTypedInsertionFamily.PipeCall(
                        lookupText = lookupText,
                        signature = signature
                    ),
                rankingCategory = AikenTypedCompletionCategory.QUALIFIED_FUNCTION,
                lookupStrings = setOf(matchingName)
            )
        )

    fun createAutoImportedConstLookup(
        name: String,
        modulePath: String,
        expectedType: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = name,
                kind = CompletionSymbolKind.IDENTIFIER,
                typeText = expectedType,
                insertionFamily =
                    AikenTypedInsertionFamily.ReplaceIdentifier(
                        text = name,
                        autoImportTarget = AikenTypedAutoImportTarget(modulePath, name)
                    ),
                rankingCategory = AikenTypedCompletionCategory.UNIMPORTED_CONST,
                tailText = " from $modulePath"
            )
        )

    fun createAutoImportedSpreadConstLookup(
        name: String,
        modulePath: String,
        expectedType: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = name,
                kind = CompletionSymbolKind.IDENTIFIER,
                typeText = expectedType,
                insertionFamily =
                    AikenTypedInsertionFamily.SpreadIdentifier(
                        text = name,
                        autoImportTarget = AikenTypedAutoImportTarget(modulePath, name)
                    ),
                rankingCategory = AikenTypedCompletionCategory.UNIMPORTED_CONST,
                tailText = " from $modulePath"
            )
        )

    fun createTypeDirectedLookup(
        text: String,
        kind: CompletionSymbolKind,
        typeText: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = text,
                kind = kind,
                typeText = typeText,
                insertionFamily = AikenTypedInsertionFamily.ReplaceIdentifier(text),
                bold = kind == CompletionSymbolKind.KEYWORD
            )
        )

    fun createSpreadLookup(
        text: String,
        kind: CompletionSymbolKind,
        typeText: String
    ): LookupElement =
        createStandardLookup(
            StandardLookupSpec(
                text = text,
                kind = kind,
                typeText = typeText,
                insertionFamily = AikenTypedInsertionFamily.SpreadIdentifier(text),
                bold = kind == CompletionSymbolKind.KEYWORD
            )
        )

    fun createConstructibleLookup(
        constructible: AikenTypedExpectedTypeCandidate.Constructible,
        typeText: String
    ): LookupElement =
        AikenCompletionSorting.annotateTyped(
            if (
                constructible.autoImportMode == AikenTypedCandidateAutoImportMode.SYMBOL &&
                !constructible.modulePath.isNullOrBlank()
            ) {
                AikenConstructibleCompletionSupport.createAutoImportedLookup(constructible.toCompletionInfo(), typeText = typeText)
            } else {
                AikenConstructibleCompletionSupport.createVisibleLookup(constructible.toCompletionInfo(), typeText = typeText)
            },
            AikenTypedCompletionCategory.CONSTRUCTIBLE,
            constructible.matchDistance
        )

    fun createAutoImportedConstructibleLookup(
        constructible: AikenTypedExpectedTypeCandidate.Constructible,
        typeText: String
    ): LookupElement =
        AikenCompletionSorting.annotateTyped(
            AikenConstructibleCompletionSupport.createAutoImportedLookup(constructible.toCompletionInfo(), typeText = typeText),
            AikenTypedCompletionCategory.CONSTRUCTIBLE,
            constructible.matchDistance
        )

    internal fun expectedTypeRankingCategory(candidate: AikenTypedExpectedTypeCandidate): AikenTypedCompletionCategory =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier ->
                when (candidate.source) {
                    AikenTypedCandidateSource.EXTRA -> AikenTypedCompletionCategory.EXTRA
                    AikenTypedCandidateSource.RECORD_SIBLING_FIELD -> AikenTypedCompletionCategory.EXTRA
                    AikenTypedCandidateSource.BINDING -> AikenTypedCompletionCategory.LOCAL_BINDING
                    AikenTypedCandidateSource.CONST ->
                        when (candidate.origin) {
                            AikenTypedCandidateOrigin.LOCAL -> AikenTypedCompletionCategory.LOCAL_CONST
                            AikenTypedCandidateOrigin.IMPORTED -> AikenTypedCompletionCategory.IMPORTED_CONST
                            AikenTypedCandidateOrigin.UNIMPORTED -> AikenTypedCompletionCategory.UNIMPORTED_CONST
                            else -> AikenTypedCompletionCategory.OTHER
                        }
                    AikenTypedCandidateSource.BUILTIN_INVARIANT -> AikenTypedCompletionCategory.BUILTIN_INVARIANT
                    else -> AikenTypedCompletionCategory.OTHER
                }
            is AikenTypedExpectedTypeCandidate.Function ->
                when (candidate.origin) {
                    AikenTypedCandidateOrigin.LOCAL -> AikenTypedCompletionCategory.LOCAL_FUNCTION
                    AikenTypedCandidateOrigin.IMPORTED -> AikenTypedCompletionCategory.IMPORTED_FUNCTION
                    AikenTypedCandidateOrigin.UNIMPORTED -> AikenTypedCompletionCategory.UNIMPORTED_FUNCTION
                    else -> AikenTypedCompletionCategory.OTHER
                }
            is AikenTypedExpectedTypeCandidate.ListLiteral -> AikenTypedCompletionCategory.LIST_LITERAL
            is AikenTypedExpectedTypeCandidate.OptionSome -> AikenTypedCompletionCategory.OPTION_SOME
            is AikenTypedExpectedTypeCandidate.Constructible -> AikenTypedCompletionCategory.CONSTRUCTIBLE
            is AikenTypedExpectedTypeCandidate.PipeFunction -> error("Unsupported expected-type candidate: $candidate")
        }

    internal fun spreadRankingCategory(candidate: AikenTypedExpectedTypeCandidate): AikenTypedCompletionCategory =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier -> expectedTypeRankingCategory(candidate)
            else -> error("Unsupported spread candidate: $candidate")
        }

    internal fun pipeRankingCategory(candidate: AikenTypedExpectedTypeCandidate): AikenTypedCompletionCategory =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.PipeFunction ->
                when (candidate.origin) {
                    AikenTypedCandidateOrigin.LOCAL -> AikenTypedCompletionCategory.LOCAL_FUNCTION
                    AikenTypedCandidateOrigin.IMPORTED -> AikenTypedCompletionCategory.IMPORTED_FUNCTION
                    AikenTypedCandidateOrigin.QUALIFIED -> AikenTypedCompletionCategory.QUALIFIED_FUNCTION
                    AikenTypedCandidateOrigin.UNIMPORTED -> AikenTypedCompletionCategory.UNIMPORTED_FUNCTION
                    else -> AikenTypedCompletionCategory.OTHER
                }
            else -> error("Unsupported pipe candidate: $candidate")
        }

    internal fun expectedTypeInsertionFamily(candidate: AikenTypedExpectedTypeCandidate): AikenTypedInsertionFamily =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier ->
                AikenTypedInsertionFamily.ReplaceIdentifier(
                    text = candidate.name,
                    autoImportTarget = candidate.autoImportTarget()
                )
            is AikenTypedExpectedTypeCandidate.Function ->
                AikenTypedInsertionFamily.FunctionCall(
                    name = candidate.name,
                    signature = candidate.signature,
                    autoImportTarget = candidate.autoImportTarget()
                )
            is AikenTypedExpectedTypeCandidate.ListLiteral ->
                AikenTypedInsertionFamily.ListLiteral
            is AikenTypedExpectedTypeCandidate.OptionSome ->
                AikenTypedInsertionFamily.OptionSome
            is AikenTypedExpectedTypeCandidate.Constructible,
            is AikenTypedExpectedTypeCandidate.PipeFunction ->
                error("Unsupported expected-type candidate: $candidate")
        }

    internal fun spreadInsertionFamily(candidate: AikenTypedExpectedTypeCandidate): AikenTypedInsertionFamily =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier ->
                AikenTypedInsertionFamily.SpreadIdentifier(
                    text = candidate.name,
                    autoImportTarget = candidate.autoImportTarget()
                )
            else -> error("Unsupported spread candidate: $candidate")
        }

    internal fun pipeInsertionFamily(candidate: AikenTypedExpectedTypeCandidate): AikenTypedInsertionFamily =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.PipeFunction ->
                AikenTypedInsertionFamily.PipeCall(
                    lookupText = candidate.lookupText,
                    signature = candidate.signature,
                    autoImportTarget = candidate.autoImportTarget(candidate.lookupText)
                )
            else -> error("Unsupported pipe candidate: $candidate")
        }

    fun createExpectedTypeLookup(
        candidate: AikenTypedExpectedTypeCandidate,
        expectedType: String
    ): LookupElement =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier ->
                createStandardLookup(
                    StandardLookupSpec(
                        text = candidate.name,
                        kind = candidate.kind,
                        typeText = candidate.type,
                        insertionFamily = expectedTypeInsertionFamily(candidate),
                        rankingCategory = expectedTypeRankingCategory(candidate),
                        matchDistance = candidate.matchDistance,
                        tailText = candidate.autoImportTailText(),
                        bold = candidate.kind == CompletionSymbolKind.KEYWORD
                    )
                )
            is AikenTypedExpectedTypeCandidate.Function ->
                createStandardLookup(
                    StandardLookupSpec(
                        text = candidate.name,
                        kind = CompletionSymbolKind.FUNCTION,
                        typeText = expectedType,
                        insertionFamily = expectedTypeInsertionFamily(candidate),
                        rankingCategory = expectedTypeRankingCategory(candidate),
                        matchDistance = candidate.matchDistance,
                        tailText = candidate.autoImportTailText()
                    )
                )
            is AikenTypedExpectedTypeCandidate.ListLiteral ->
                createStandardLookup(
                    StandardLookupSpec(
                        text = "[]",
                        kind = CompletionSymbolKind.TYPE,
                        typeText = expectedType,
                        insertionFamily = expectedTypeInsertionFamily(candidate),
                        rankingCategory = expectedTypeRankingCategory(candidate),
                        matchDistance = candidate.matchDistance
                    )
                )
            is AikenTypedExpectedTypeCandidate.OptionSome ->
                createStandardLookup(
                    StandardLookupSpec(
                        text = "Some()",
                        kind = CompletionSymbolKind.TYPE,
                        typeText = expectedType,
                        insertionFamily = expectedTypeInsertionFamily(candidate),
                        rankingCategory = expectedTypeRankingCategory(candidate),
                        matchDistance = candidate.matchDistance
                    )
                )
            is AikenTypedExpectedTypeCandidate.Constructible ->
                if (
                    candidate.autoImportMode == AikenTypedCandidateAutoImportMode.SYMBOL &&
                    !candidate.modulePath.isNullOrBlank()
                ) {
                    createAutoImportedConstructibleLookup(candidate, expectedType)
                } else {
                    createConstructibleLookup(candidate, expectedType)
                }
            is AikenTypedExpectedTypeCandidate.PipeFunction ->
                error("Unsupported expected-type candidate: $candidate")
        }

    fun createSpreadLookup(
        candidate: AikenTypedExpectedTypeCandidate,
        expectedType: String
    ): LookupElement =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier ->
                createStandardLookup(
                    StandardLookupSpec(
                        text = candidate.name,
                        kind = candidate.kind,
                        typeText = expectedType,
                        insertionFamily = spreadInsertionFamily(candidate),
                        rankingCategory = spreadRankingCategory(candidate),
                        matchDistance = candidate.matchDistance,
                        tailText = candidate.autoImportTailText(),
                        bold = candidate.kind == CompletionSymbolKind.KEYWORD
                    )
                )
            else -> error("Unsupported spread candidate: $candidate")
        }

    fun createPipeLookup(candidate: AikenTypedExpectedTypeCandidate): LookupElement =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.PipeFunction ->
                createStandardLookup(
                    StandardLookupSpec(
                        text = candidate.lookupText,
                        kind = CompletionSymbolKind.FUNCTION,
                        typeText = aikenFunctionSignatureReturnType(candidate.signature).orEmpty(),
                        insertionFamily = pipeInsertionFamily(candidate),
                        rankingCategory = pipeRankingCategory(candidate),
                        matchDistance = candidate.matchDistance,
                        tailText = candidate.autoImportTailText(),
                        lookupStrings =
                            if (candidate.origin == AikenTypedCandidateOrigin.QUALIFIED) {
                                setOf(candidate.matchName)
                            } else {
                                emptySet()
                            }
                    )
                )
            else -> error("Unsupported pipe candidate: $candidate")
        }

    private fun createStandardLookup(spec: StandardLookupSpec): LookupElement {
        val lookupIdentity =
            buildString {
                append(spec.text)
                append('\u0000')
                append(spec.kind.name)
                append('\u0000')
                append(spec.typeText)
                append('\u0000')
                append(spec.tailText.orEmpty())
                append('\u0000')
                append(spec.insertionFamily::class.simpleName.orEmpty())
            }
        var builder =
            LookupElementBuilder
                .create(lookupIdentity, spec.text)
                .withIcon(iconFor(spec.kind))
                .withTypeText(spec.typeText, true)
                .withBoldness(spec.bold)
                .withInsertHandler { insertionContext, _ ->
                    applyInsertionFamily(insertionContext, spec.insertionFamily)
                }

        if (spec.lookupStrings.isNotEmpty()) {
            builder = builder.withLookupStrings(spec.lookupStrings)
        }
        if (spec.tailText != null) {
            builder = builder.withTailText(spec.tailText, true)
        }

        return if (spec.rankingCategory != null) {
            val lookup = builder
            AikenCompletionSorting.annotateTyped(lookup, spec.rankingCategory, spec.matchDistance)
        } else {
            builder
        }
    }

    private fun applyInsertionFamily(
        insertionContext: InsertionContext,
        insertionFamily: AikenTypedInsertionFamily
    ) {
        when (insertionFamily) {
            is AikenTypedInsertionFamily.ReplaceIdentifier -> {
                replaceCurrentIdentifierPrefix(insertionContext, insertionFamily.text)
                insertionContext.commitDocument()
                schedulePostInsert(insertionContext, insertionFamily.autoImportTarget, shouldTriggerAutoPopup = false)
            }
            is AikenTypedInsertionFamily.SpreadIdentifier -> {
                normalizeSpreadInsertion(insertionContext, insertionFamily.text)
                insertionContext.commitDocument()
                schedulePostInsert(insertionContext, insertionFamily.autoImportTarget, shouldTriggerAutoPopup = false)
            }
            is AikenTypedInsertionFamily.FunctionCall -> {
                val callTemplate = functionCallTemplate(insertionFamily.name, insertionFamily.signature)
                val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                callTemplate.caretOffset?.let { caretOffset ->
                    insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                }
                insertionContext.commitDocument()
                schedulePostInsert(
                    insertionContext,
                    insertionFamily.autoImportTarget,
                    shouldTriggerAutoPopup = callTemplate.shouldTriggerAutoPopup
                )
            }
            is AikenTypedInsertionFamily.PipeCall -> {
                val callTemplate = pipeFunctionCallTemplate(insertionFamily.lookupText, insertionFamily.signature)
                val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, callTemplate.text)
                callTemplate.caretOffset?.let { caretOffset ->
                    insertionContext.editor.caretModel.moveToOffset(insertedOffset + caretOffset)
                }
                insertionContext.commitDocument()
                schedulePostInsert(
                    insertionContext,
                    insertionFamily.autoImportTarget,
                    shouldTriggerAutoPopup = callTemplate.shouldTriggerAutoPopup
                )
            }
            AikenTypedInsertionFamily.ListLiteral -> {
                val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, "[]")
                insertionContext.editor.caretModel.moveToOffset(insertedOffset + 1)
                insertionContext.commitDocument()
                AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
            }
            AikenTypedInsertionFamily.OptionSome -> {
                val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, "Some()")
                insertionContext.editor.caretModel.moveToOffset(insertedOffset + "Some(".length)
                insertionContext.commitDocument()
                AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
            }
        }
    }

    private fun schedulePostInsert(
        insertionContext: InsertionContext,
        autoImportTarget: AikenTypedAutoImportTarget?,
        shouldTriggerAutoPopup: Boolean
    ) {
        if (autoImportTarget == null) {
            if (shouldTriggerAutoPopup) {
                AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
            }
            return
        }

        val previousLaterRunnable = insertionContext.laterRunnable
        insertionContext.setLaterRunnable {
            previousLaterRunnable?.run()
            WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                insertStandaloneUseImport(
                    insertionContext.document,
                    autoImportTarget.modulePath,
                    autoImportTarget.symbolName
                )
                insertionContext.commitDocument()
            }
            if (shouldTriggerAutoPopup) {
                AutoPopupController.getInstance(insertionContext.project).scheduleAutoPopup(insertionContext.editor)
            }
        }
    }

    internal fun functionCallTemplate(name: String, signature: String): FunctionCallTemplate {
        val parameterCount = parseSignatureParameterNames(signature).size
        if (parameterCount == 0) {
            return FunctionCallTemplate(
                text = "$name()",
                caretOffset = null,
                shouldTriggerAutoPopup = false
            )
        }

        return FunctionCallTemplate(
            text = "$name()",
            caretOffset = name.length + 1,
            shouldTriggerAutoPopup = true
        )
    }

    internal fun pipeFunctionCallTemplate(lookupText: String, signature: String): FunctionCallTemplate {
        val remainingParameterCount = (parseSignatureParameterNames(signature).size - 1).coerceAtLeast(0)
        if (remainingParameterCount == 0) {
            return FunctionCallTemplate(
                text = lookupText,
                caretOffset = null,
                shouldTriggerAutoPopup = false
            )
        }

        return FunctionCallTemplate(
            text = "$lookupText()",
            caretOffset = lookupText.length + 1,
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

    private fun AikenTypedExpectedTypeCandidate.autoImportTailText(): String? =
        modulePath?.takeIf { autoImportMode == AikenTypedCandidateAutoImportMode.SYMBOL }?.let { " from $it" }

    private fun AikenTypedExpectedTypeCandidate.autoImportTarget(symbolName: String = autoImportSymbolName()): AikenTypedAutoImportTarget? =
        modulePath
            ?.takeIf { autoImportMode == AikenTypedCandidateAutoImportMode.SYMBOL }
            ?.let { AikenTypedAutoImportTarget(it, symbolName) }

    private fun AikenTypedExpectedTypeCandidate.autoImportSymbolName(): String =
        when (this) {
            is AikenTypedExpectedTypeCandidate.Identifier -> name
            is AikenTypedExpectedTypeCandidate.Function -> name
            is AikenTypedExpectedTypeCandidate.PipeFunction -> lookupText
            is AikenTypedExpectedTypeCandidate.Constructible -> name
            AikenTypedExpectedTypeCandidate.ListLiteral -> "[]"
            AikenTypedExpectedTypeCandidate.OptionSome -> "Some()"
        }

    private fun AikenTypedExpectedTypeCandidate.Constructible.toCompletionInfo(): AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo =
        AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo(
            name = name,
            resultType = resultType,
            fields = fields,
            supportsNamedSyntax = supportsNamedSyntax,
            modulePath = modulePath,
            needsImport = autoImportMode == AikenTypedCandidateAutoImportMode.SYMBOL
        )

    private fun replaceCurrentIdentifierPrefix(
        insertionContext: InsertionContext,
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
        insertionContext: InsertionContext,
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

    private fun insertStandaloneUseImport(
        document: com.intellij.openapi.editor.Document,
        modulePath: String,
        symbolName: String
    ) {
        val useLine = "use $modulePath.{$symbolName}"
        document.insertString(0, "$useLine\n")
    }

    private fun iconFor(kind: CompletionSymbolKind) =
        when (kind) {
            CompletionSymbolKind.KEYWORD -> AllIcons.Nodes.Static
            CompletionSymbolKind.TYPE -> AllIcons.Nodes.Class
            CompletionSymbolKind.FUNCTION -> AllIcons.Nodes.Method
            CompletionSymbolKind.FIELD -> AllIcons.Nodes.Field
            CompletionSymbolKind.IDENTIFIER -> AllIcons.Nodes.Variable
        }

    private fun isCompletionBoundary(char: Char?): Boolean =
        char == null || char.isWhitespace() || char == ',' || char == ']' || char == ')' || char == '}'

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
}
