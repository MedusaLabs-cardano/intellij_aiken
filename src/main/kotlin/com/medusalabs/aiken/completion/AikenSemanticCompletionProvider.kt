package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

class AikenSemanticCompletionProvider : CompletionProvider<CompletionParameters>() {
    private val allKeywords = com.medusalabs.aiken.highlight.lexer.AikenLexing.keywords
    private val builtinValueSuggestions =
        listOf(
            "True" to "Bool",
            "False" to "Bool"
        )

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val file = parameters.originalFile
        if (file.fileType != AikenFileType) return

        val offset = parameters.offset.coerceIn(0, file.textLength)
        if (parameters.invocationCount == 0) {
            val tokenType = file.findElementAt((offset - 1).coerceAtLeast(0))?.node?.elementType
            if (tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.COMMENT ||
                tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.STRING
            ) {
                return
            }
        }
        val resolution = AikenCompletionScenarioResolver.resolve(file, offset)
        when (resolution.scenario) {
            AikenCompletionScenario.NoSuggestions -> {
                result.stopHere()
                return
            }
            AikenCompletionScenario.UseModule,
            AikenCompletionScenario.UseSymbol -> return
            else -> Unit
        }

        val anchor = resolution.anchor
        val prefix = effectivePrefix(parameters, resolution.prefix, offset)
        val text = file.text

        if (AikenCompletionContexts.isInsideMalformedCallableReturnConstructibleContext(text, offset)) {
            result.stopHere()
            return
        }

        val constructibleInvocationSuggestions =
            if (resolution.policy.typeOnlySuggestions) {
                emptyList()
            } else {
                AikenConstructibleCompletionSupport.invocationFormVariants(parameters, anchor, offset)
            }

        if (constructibleInvocationSuggestions.isNotEmpty()) {
            val invocationResult = AikenCompletionSorting.withConstructibleFormSorter(parameters, result.withPrefixMatcher(""))
            for (lookupElement in constructibleInvocationSuggestions) {
                invocationResult.addElement(lookupElement)
            }
            result.stopHere()
            return
        }

        when (val scenario = resolution.scenario) {
            AikenCompletionScenario.RecordFieldName,
            is AikenCompletionScenario.RecordFieldValue,
            AikenCompletionScenario.RecordSpread -> {
                AikenRecordCompletionSupport.recordSpecificVariants(anchor, offset)?.let { suggestions ->
                    val specialResult =
                        if (suggestions.mode == RecordCompletionMode.FIELD_VALUE || suggestions.mode == RecordCompletionMode.SPREAD) {
                            AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(""))
                        } else {
                            result.withPrefixMatcher(prefix)
                        }

                    for (lookupElement in suggestions.lookups) {
                        specialResult.addElement(lookupElement)
                    }
                }
                if (resolution.scenario != AikenCompletionScenario.RecordFieldName && resolution.qualifiedAccessQualifier != null) {
                    addQualifiedSuggestions(
                        parameters = parameters,
                        result = result,
                        anchor = anchor,
                        offset = offset,
                        prefix = prefix,
                        qualifier = resolution.qualifiedAccessQualifier,
                        allowBareTypes = resolution.policy.bareTypesAllowed,
                        typeOnly = resolution.policy.typeOnlySuggestions
                    )
                }
                result.stopHere()
                return
            }
            AikenCompletionScenario.ListItem -> {
                val inferredListSuggestions =
                    if (anchor != null) {
                        AikenTypeDirectedCompletionSupport.listLiteralItemLookups(anchor, text, offset)
                    } else {
                        emptyList()
                    }
                if (inferredListSuggestions.isNotEmpty()) {
                    if (shouldShowTypedSuggestions(inferredListSuggestions, prefix)) {
                        addTypedSupplementalSuggestions(
                            parameters,
                            result,
                            inferredListSuggestions,
                            prefix,
                            includeNonMatchingSuggestions = true
                        )
                        result.stopHere()
                        return
                    }
                }

                val argumentSuggestions =
                    if (resolution.hasArgumentContext) {
                        AikenArgumentCompletionSupport.argumentSpecificVariants(anchor, offset)
                    } else {
                        emptyList()
                    }
                addTypedSupplementalSuggestions(
                    parameters,
                    result,
                    argumentSuggestions,
                    prefix,
                    includeNonMatchingSuggestions = true
                )

                if (resolution.qualifiedAccessQualifier != null) {
                    addQualifiedSuggestions(
                        parameters = parameters,
                        result = result,
                        anchor = anchor,
                        offset = offset,
                        prefix = prefix,
                        qualifier = resolution.qualifiedAccessQualifier,
                        allowBareTypes = resolution.policy.bareTypesAllowed,
                        typeOnly = resolution.policy.typeOnlySuggestions
                    )
                    result.stopHere()
                    return
                }

                if (resolution.policy.lexicalFallbackAllowed) {
                    addOrdinarySemanticSuggestions(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = anchor,
                        fallbackAnchor = parameters.position,
                        offset = offset,
                        prefix = prefix,
                        allowBareTypes = resolution.policy.bareTypesAllowed,
                        typeOnly = resolution.policy.typeOnlySuggestions,
                        stopAfter = resolution.policy.typedCompletionStopsFurtherMerging,
                        excludedLookups = argumentSuggestions
                    )
                } else if (resolution.policy.typedCompletionStopsFurtherMerging) {
                    result.stopHere()
                }
                return
            }
            AikenCompletionScenario.PipeTarget -> {
                val pipeSuggestions = AikenArgumentCompletionSupport.pipeSpecificVariants(anchor, offset)
                val pipeResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(prefix))
                for (lookupElement in pipeSuggestions) {
                    pipeResult.addElement(lookupElement)
                }
                result.stopHere()
                return
            }
            AikenCompletionScenario.TypeReference -> {
                if (resolution.qualifiedAccessQualifier != null) {
                    addQualifiedSuggestions(
                        parameters = parameters,
                        result = result,
                        anchor = anchor,
                        offset = offset,
                        prefix = prefix,
                        qualifier = resolution.qualifiedAccessQualifier,
                        allowBareTypes = true,
                        typeOnly = true
                    )
                } else {
                    addOrdinarySemanticSuggestions(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = anchor,
                        fallbackAnchor = parameters.position,
                        offset = offset,
                        prefix = prefix,
                        allowBareTypes = true,
                        typeOnly = true,
                        stopAfter = true,
                        excludedLookups = emptyList()
                    )
                }
                result.stopHere()
                return
            }
            is AikenCompletionScenario.QualifiedAccess -> {
                val argumentSuggestions =
                    if (resolution.hasArgumentContext) {
                        AikenArgumentCompletionSupport.argumentSpecificVariants(anchor, offset)
                    } else {
                        emptyList()
                    }
                addTypedSupplementalSuggestions(parameters, result, argumentSuggestions, prefix, includeNonMatchingSuggestions = true)
                addQualifiedSuggestions(
                    parameters = parameters,
                    result = result,
                    anchor = anchor,
                    offset = offset,
                    prefix = prefix,
                    qualifier = scenario.qualifier,
                    allowBareTypes = resolution.policy.bareTypesAllowed,
                    typeOnly = resolution.policy.typeOnlySuggestions
                )
                result.stopHere()
                return
            }
            AikenCompletionScenario.FunctionArgument -> {
                val bindingInitializerSuggestions =
                    if (anchor != null) {
                        AikenTypeDirectedCompletionSupport.bindingInitializerLookups(anchor, text, offset)
                    } else {
                        emptyList()
                    }
                val bindingInitializerPrefixMatch =
                    typedSuggestionsMatchPrefix(bindingInitializerSuggestions, prefix)
                if (bindingInitializerSuggestions.isNotEmpty()) {
                    if (shouldShowTypedSuggestions(bindingInitializerSuggestions, prefix)) {
                        addTypedSupplementalSuggestions(parameters, result, bindingInitializerSuggestions, prefix)
                    }
                    if (bindingInitializerPrefixMatch) {
                        result.stopHere()
                        return
                    }
                }

                val argumentSuggestions = AikenArgumentCompletionSupport.argumentSpecificVariants(anchor, offset)
                val argumentPrefixMatch = typedSuggestionsMatchPrefix(argumentSuggestions, prefix)
                val addedArgumentTyped = shouldShowTypedSuggestions(argumentSuggestions, prefix)
                if (addedArgumentTyped) {
                    addTypedSupplementalSuggestions(parameters, result, argumentSuggestions, prefix, includeNonMatchingSuggestions = true)
                }
                if (argumentSuggestions.isNotEmpty() && (prefix.isBlank() || argumentPrefixMatch)) {
                    result.stopHere()
                    return
                }
                if (resolution.stopAfterArgumentSuggestions && (addedArgumentTyped || argumentSuggestions.isEmpty())) {
                    result.stopHere()
                    return
                }
                val expressionAnchor = anchor ?: parameters.position
                if (tryAddTypedBlockExpressionContext(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = expressionAnchor,
                        text = text,
                        offset = offset,
                        prefix = prefix,
                        typeOnly = resolution.policy.typeOnlySuggestions
                    )
                ) return
                if (resolution.policy.lexicalFallbackAllowed) {
                    addOrdinarySemanticSuggestions(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = anchor,
                        fallbackAnchor = parameters.position,
                        offset = offset,
                        prefix = prefix,
                        allowBareTypes = resolution.policy.bareTypesAllowed,
                        typeOnly = resolution.policy.typeOnlySuggestions,
                        stopAfter = resolution.policy.typedCompletionStopsFurtherMerging,
                        excludedLookups = argumentSuggestions
                    )
                }
                return
            }
            AikenCompletionScenario.OrdinaryExpression -> {
                val ordinaryAnchor = anchor ?: parameters.position
                AikenWhenCompletionSupport.subjectTailLookup(text, offset)?.let { lookup ->
                    result.withPrefixMatcher(prefix).addElement(lookup)
                    result.stopHere()
                    return
                }

                val bindingInitializerSuggestions =
                    AikenTypeDirectedCompletionSupport.bindingInitializerLookups(ordinaryAnchor, text, offset)
                val bindingInitializerPrefixMatch =
                    typedSuggestionsMatchPrefix(bindingInitializerSuggestions, prefix)
                if (bindingInitializerSuggestions.isNotEmpty()) {
                    if (shouldShowTypedSuggestions(bindingInitializerSuggestions, prefix)) {
                        addTypedSupplementalSuggestions(parameters, result, bindingInitializerSuggestions, prefix)
                    }
                    if (bindingInitializerPrefixMatch) {
                        result.stopHere()
                        return
                    }
                }

                val whenPatternSuggestions =
                    if (!resolution.policy.typeOnlySuggestions) {
                        AikenTypeDirectedCompletionSupport.whenPatternLookups(ordinaryAnchor, text, offset)
                    } else {
                        emptyList()
                    }
                if (whenPatternSuggestions.isNotEmpty()) {
                    if (shouldShowTypedSuggestions(whenPatternSuggestions, prefix)) {
                        addTypedSupplementalSuggestions(parameters, result, whenPatternSuggestions, prefix, includeNonMatchingSuggestions = false)
                        result.stopHere()
                        return
                    }
                }

                if (tryAddTypedBlockExpressionContext(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = ordinaryAnchor,
                        text = text,
                        offset = offset,
                        prefix = prefix,
                        typeOnly = resolution.policy.typeOnlySuggestions
                    )
                ) return

                if (resolution.policy.lexicalFallbackAllowed) {
                    addOrdinarySemanticSuggestions(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = anchor,
                        fallbackAnchor = parameters.position,
                        offset = offset,
                        prefix = prefix,
                        allowBareTypes = resolution.policy.bareTypesAllowed,
                        typeOnly = resolution.policy.typeOnlySuggestions,
                        stopAfter = resolution.policy.typedCompletionStopsFurtherMerging,
                        excludedLookups = emptyList()
                    )
                }
                return
            }
            AikenCompletionScenario.UseModule,
            AikenCompletionScenario.UseSymbol,
            AikenCompletionScenario.NoSuggestions,
            AikenCompletionScenario.TypeReference -> return
        }
    }

    private fun tryAddTypedBlockExpressionContext(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        file: PsiFile,
        anchor: PsiElement,
        text: String,
        offset: Int,
        prefix: String,
        typeOnly: Boolean
    ): Boolean {
        if (typeOnly) return false

        if (prefix.isNotBlank() || parameters.invocationCount > 0) {
            val operatorOperandSuggestions =
                AikenTypeDirectedCompletionSupport.operatorOperandLookups(anchor, text, offset)
            if (tryAddTypedBlockExpressionSuggestions(parameters, result, anchor, offset, prefix, operatorOperandSuggestions)) {
                return true
            }
        }

        val ifBranchSuggestions =
            AikenTypeDirectedCompletionSupport.ifBranchExpressionLookups(anchor, text, offset)
        if (tryAddTypedBlockExpressionSuggestions(parameters, result, anchor, offset, prefix, ifBranchSuggestions)) {
            return true
        }

        val whenArmSuggestions =
            AikenTypeDirectedCompletionSupport.whenArmExpressionLookups(anchor, text, offset)
        if (tryAddTypedBlockExpressionSuggestions(parameters, result, anchor, offset, prefix, whenArmSuggestions)) {
            return true
        }

        if (!AikenTypeDirectedCompletionSupport.hasCallableReturnExpectedTypeContext(text, offset)) {
            return false
        }

        val callableReturnSuggestions =
            AikenTypeDirectedCompletionSupport.callableReturnLookups(anchor, text, offset)
        val typedSuggestionsVisible = shouldShowTypedSuggestions(callableReturnSuggestions, prefix)
        if (typedSuggestionsVisible) {
            addTypedSupplementalSuggestions(parameters, result, callableReturnSuggestions, prefix)
        } else if (prefix.isNotBlank()) {
            addOrdinarySemanticSuggestions(
                parameters = parameters,
                result = result,
                file = file,
                anchor = anchor,
                fallbackAnchor = parameters.position,
                offset = offset,
                prefix = prefix,
                allowBareTypes = false,
                typeOnly = false,
                stopAfter = false,
                excludedLookups = callableReturnSuggestions
            )
            addKeywordFallbackSuggestions(parameters, result, prefix)
            result.stopHere()
            return true
        }
        addBlockExpressionFallbackIdentifiers(
            parameters = parameters,
            result = result,
            anchor = anchor,
            offset = offset,
            prefix = prefix,
            excludedLookups = callableReturnSuggestions
        )
        addKeywordFallbackSuggestions(parameters, result, prefix)
        result.stopHere()
        return true
    }

    private fun tryAddTypedBlockExpressionSuggestions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        anchor: PsiElement,
        offset: Int,
        prefix: String,
        suggestions: List<LookupElement>
    ): Boolean {
        if (suggestions.isEmpty() || !shouldShowTypedSuggestions(suggestions, prefix)) {
            return false
        }

        addTypedSupplementalSuggestions(parameters, result, suggestions, prefix, includeNonMatchingSuggestions = false)
        addBlockExpressionFallbackIdentifiers(
            parameters = parameters,
            result = result,
            anchor = anchor,
            offset = offset,
            prefix = prefix,
            excludedLookups = suggestions
        )
        addBuiltinValueSuggestions(parameters, result, prefix, excludedLookups = suggestions)
        addKeywordFallbackSuggestions(parameters, result, prefix)
        result.stopHere()
        return true
    }

    private fun addTypedSupplementalSuggestions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        suggestions: List<LookupElement>,
        prefix: String,
        includeNonMatchingSuggestions: Boolean = false
    ) {
        if (suggestions.isEmpty()) return
        if (prefix.isBlank()) {
            val specialResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(""))
            for (lookupElement in suggestions) {
                specialResult.addElement(lookupElement)
            }
            return
        }

        val (matchingSuggestions, nonMatchingSuggestions) = partitionTypedSuggestionsByPrefix(suggestions, prefix)
        if (matchingSuggestions.isEmpty()) return

        val matchingResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(prefix))
        for (lookupElement in matchingSuggestions) {
            matchingResult.addElement(AikenCompletionSorting.withPrefixMatchBucket(lookupElement, matched = true))
        }

        if (includeNonMatchingSuggestions && nonMatchingSuggestions.isNotEmpty()) {
            val relaxedResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(""))
            for (lookupElement in nonMatchingSuggestions) {
                relaxedResult.addElement(AikenCompletionSorting.withPrefixMatchBucket(lookupElement, matched = false))
            }
        }
    }

    private fun addKeywordFallbackSuggestions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        prefix: String
    ) {
        val keywordResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(""))
        for (keyword in allKeywords) {
            if (prefix.isNotBlank() && !AikenCompletionPrefixMatching.matches(keyword, PlainPrefixMatcher(prefix), prefix)) continue
            keywordResult.addElement(
                AikenCompletionSorting.annotateTyped(
                    CompletionItemFactory.create(keyword, CompletionSymbolKind.KEYWORD),
                    AikenTypedCompletionCategory.FALLBACK_KEYWORD
                )
            )
        }
    }

    private fun addBuiltinValueSuggestions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        prefix: String,
        excludedLookups: List<LookupElement> = emptyList()
    ) {
        val excludedNames =
            excludedLookups
                .flatMapTo(HashSet()) { lookup -> lookup.allLookupStrings + lookup.lookupString }
        val rankedResult = AikenCompletionSorting.withOrdinarySorter(parameters, result.withPrefixMatcher(prefix))
        for ((name, typeText) in builtinValueSuggestions) {
            if (prefix.isNotBlank() && !AikenCompletionPrefixMatching.matches(name, PlainPrefixMatcher(prefix), prefix)) continue
            if (name in excludedNames) continue
            rankedResult.addElement(
                AikenCompletionSorting.annotate(
                    lookup = CompletionItemFactory.create(name, CompletionSymbolKind.TYPE, typeText, rankingCategory = null),
                    category = AikenOrdinaryCompletionCategory.BUILTIN_VALUE,
                    kind = CompletionSymbolKind.TYPE
                )
            )
        }
    }

    private fun addBlockExpressionFallbackIdentifiers(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        anchor: PsiElement,
        offset: Int,
        prefix: String,
        excludedLookups: List<LookupElement>
    ) {
        val prefixMatcher = PlainPrefixMatcher(prefix)
        val excludedKeys = excludedLookups.mapTo(HashSet(), ::lookupCollisionKey)
        val scopeDistanceByName =
            AikenLocalScopeAnalyzer.collectVisibleBindings(anchor)
                .associate { binding -> binding.name to binding.scopeDistance }
        val fallbackIdentifiers =
            variantsForAnchor(anchor, offset, allowBareTypes = false)
                .asSequence()
                .filter { AikenCompletionSorting.ordinaryKind(it) == CompletionSymbolKind.IDENTIFIER }
                .filter { prefix.isNotBlank() || !isModuleLookup(it) }
                .filterNot { lookupCollisionKey(it) in excludedKeys }
                .filter { prefix.isBlank() || AikenCompletionPrefixMatching.matches(it.lookupString, prefixMatcher, prefix) }
                .distinctBy(::lookupDedupKey)
                .toList()
        if (fallbackIdentifiers.isEmpty()) return

        val fallbackResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(""))
        for (lookup in fallbackIdentifiers) {
            fallbackResult.addElement(
                AikenCompletionSorting.annotateTyped(
                    lookup,
                    AikenTypedCompletionCategory.FALLBACK_IDENTIFIER,
                    scopeDistance = scopeDistanceByName[lookup.lookupString] ?: Int.MAX_VALUE
                )
            )
        }
    }

    private fun isModuleLookup(lookup: LookupElement): Boolean {
        val presentation = LookupElementPresentation()
        lookup.renderElement(presentation)
        return presentation.typeText?.trim() == "module"
    }

    private fun effectivePrefix(
        parameters: CompletionParameters,
        resolvedPrefix: String,
        offset: Int
    ): String {
        val cleanedResolved = cleanCompletionDummy(resolvedPrefix)
        if (cleanedResolved.isNotBlank()) return cleanedResolved

        val fileTextPrefix = cleanCompletionDummy(AikenSyntaxText.identifierPrefix(parameters.originalFile.text, offset))
        if (fileTextPrefix.isNotBlank()) return fileTextPrefix

        val editorOffset = parameters.editor.caretModel.offset.coerceIn(0, parameters.editor.document.textLength)
        return cleanCompletionDummy(AikenSyntaxText.identifierPrefix(parameters.editor.document.charsSequence, editorOffset))
    }

    private fun cleanCompletionDummy(text: String): String =
        text
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")

    private fun typedSuggestionsMatchPrefix(
        suggestions: List<LookupElement>,
        prefix: String
    ): Boolean =
        prefix.isBlank() || partitionTypedSuggestionsByPrefix(suggestions, prefix).first.isNotEmpty()

    private fun shouldShowTypedSuggestions(
        suggestions: List<LookupElement>,
        prefix: String
    ): Boolean =
        prefix.isBlank() || typedSuggestionsMatchPrefix(suggestions, prefix)

    private fun partitionTypedSuggestionsByPrefix(
        suggestions: List<LookupElement>,
        prefix: String
    ): Pair<List<LookupElement>, List<LookupElement>> {
        if (prefix.isBlank()) return suggestions to emptyList()
        val matcher = PlainPrefixMatcher(prefix)
        val matching = ArrayList<LookupElement>()
        val nonMatching = ArrayList<LookupElement>()
        for (lookup in suggestions) {
            val matchesPrefix =
                lookup.allLookupStrings.any { candidate ->
                    AikenCompletionPrefixMatching.matches(candidate, matcher, prefix)
                }
            if (matchesPrefix) {
                matching += lookup
            } else {
                nonMatching += lookup
            }
        }
        return matching to nonMatching
    }

    private fun addQualifiedSuggestions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        anchor: PsiElement?,
        offset: Int,
        prefix: String,
        qualifier: String,
        allowBareTypes: Boolean,
        typeOnly: Boolean
    ) {
        val semanticVariants =
            anchor
                ?.let { currentAnchor ->
                    AikenReferenceVariants.qualifiedVariants(
                        element = currentAnchor,
                        qualifier = qualifier,
                        allowBareTypes = allowBareTypes,
                        offsetExclusive = offset
                    )
                }
                .orEmpty()
                .let { lookups ->
                    if (typeOnly) {
                        anchor?.let { currentAnchor ->
                            lookups.filter { isTypeReferenceLookup(currentAnchor, it) }
                        }.orEmpty()
                    } else {
                        lookups
                    }
                }
        if (shouldSuppressAutoPopupForExactMatch(parameters, prefix, semanticVariants)) {
            result.stopHere()
            return
        }
        val semanticResult = AikenCompletionSorting.withOrdinarySorter(parameters, result.withPrefixMatcher(prefix))
        for (lookupElement in semanticVariants) {
            semanticResult.addElement(lookupElement)
        }
    }

    private fun addOrdinarySemanticSuggestions(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        file: PsiFile,
        anchor: PsiElement?,
        fallbackAnchor: PsiElement,
        offset: Int,
        prefix: String,
        allowBareTypes: Boolean,
        typeOnly: Boolean,
        stopAfter: Boolean,
        excludedLookups: List<LookupElement>
    ) {
        val effectiveAnchor = anchor ?: fallbackAnchor
        val semanticVariants =
            variantsForAnchor(
                anchor = effectiveAnchor,
                offset = offset,
                allowBareTypes = if (typeOnly) true else allowBareTypes
            )
                .let { lookups ->
                    if (typeOnly) {
                        lookups.filter { isTypeReferenceLookup(effectiveAnchor, it) }
                    } else {
                        lookups
                    }
                }
                .filterNot { lookup ->
                    excludedLookups.any { lookupCollisionKey(it) == lookupCollisionKey(lookup) }
                }
                .withLocalBindingScopeDistances(effectiveAnchor)
        val semanticResult = AikenCompletionSorting.withOrdinarySorter(parameters, result.withPrefixMatcher(prefix))
        val unimportedResult = AikenCompletionSorting.withOrdinarySorter(parameters, result.withPrefixMatcher(""))
        val unimportedSemanticVariants =
            AikenReferenceVariants.unimportedExportsMatching(
                effectiveAnchor,
                nameMatches = {
                    if (typeOnly && prefix.isBlank()) {
                        true
                    } else {
                        AikenCompletionPrefixMatching.matches(it, semanticResult.prefixMatcher, prefix)
                    }
                }
            )
                .let { lookups ->
                    if (typeOnly) {
                        lookups.filter { isTypeReferenceLookup(effectiveAnchor, it) }
                    } else {
                        lookups
                    }
                }
        val unimportedModuleVariants =
            if (typeOnly || prefix.isBlank()) {
                emptyList()
            } else {
                AikenReferenceVariants.unimportedModulesMatching(effectiveAnchor) {
                    AikenCompletionPrefixMatching.matches(it, semanticResult.prefixMatcher, prefix)
                }
            }

        val allVariants = semanticVariants + unimportedSemanticVariants + unimportedModuleVariants
        val insideListLiteral = AikenCompletionContexts.insideListLiteralContext(file.text, offset)
        if (shouldSuppressAutoPopupForExactMatch(parameters, prefix, allVariants)) {
            result.stopHere()
            return
        }

        if (semanticVariants.isEmpty() && unimportedSemanticVariants.isEmpty() && unimportedModuleVariants.isEmpty()) {
            if (stopAfter) {
                result.stopHere()
            }
            return
        }

        for (lookupElement in semanticVariants) {
            semanticResult.addElement(lookupElement)
        }
        if (!insideListLiteral && !typeOnly) {
            addBuiltinValueSuggestions(parameters, result, prefix, excludedLookups)
        }
        for (lookupElement in unimportedSemanticVariants) {
            unimportedResult.addElement(lookupElement)
        }
        for (lookupElement in unimportedModuleVariants) {
            unimportedResult.addElement(lookupElement)
        }

        if (stopAfter || insideListLiteral) {
            result.stopHere()
        }
    }

    private fun variantsForAnchor(
        anchor: PsiElement,
        offset: Int,
        allowBareTypes: Boolean
    ): List<LookupElement> {
        val referenceVariants =
            anchor.references
                .asSequence()
                .flatMap { reference -> reference.variants.asSequence() }
                .mapNotNull { it as? LookupElement }
                .distinctBy(::lookupDedupKey)
                .toList()
        val fallbackVariants =
            AikenReferenceVariants.forElement(
                element = anchor,
                caretOffsetOverride = offset,
                allowBareTypesOverride = allowBareTypes
            )
                .mapNotNull { it as? LookupElement }
        val merged = LinkedHashMap<String, LookupElement>()
        for (lookup in fallbackVariants + referenceVariants) {
            merged.putIfAbsent(lookupDedupKey(lookup), lookup)
        }
        return merged.values.toList()
    }

    private fun isTypeReferenceLookup(
        anchor: PsiElement,
        lookup: LookupElement
    ): Boolean =
        AikenCompletionSorting.ordinaryKind(lookup) == CompletionSymbolKind.TYPE &&
            AikenReferenceVariants.isTypeDefinitionName(anchor, lookup.lookupString)

    private fun List<LookupElement>.withLocalBindingScopeDistances(anchor: PsiElement): List<LookupElement> {
        val scopeDistanceByName =
            AikenLocalScopeAnalyzer.collectVisibleBindings(anchor)
                .associate { binding -> binding.name to binding.scopeDistance }
        if (scopeDistanceByName.isEmpty()) return this
        return map { lookup ->
            val scopeDistance = scopeDistanceByName[lookup.lookupString]
            if (scopeDistance == null || AikenCompletionSorting.ordinaryKind(lookup) != CompletionSymbolKind.IDENTIFIER) {
                lookup
            } else {
                AikenCompletionSorting.annotate(
                    lookup = lookup,
                    category = AikenOrdinaryCompletionCategory.LOCAL,
                    kind = CompletionSymbolKind.IDENTIFIER,
                    scopeDistance = scopeDistance
                )
            }
        }
    }

    private fun lookupDedupKey(lookup: LookupElement): String {
        val presentation = LookupElementPresentation()
        lookup.renderElement(presentation)
        return buildString {
            append(lookup.lookupString)
            append('\u0000')
            append(presentation.tailText.orEmpty())
            append('\u0000')
            append(presentation.typeText.orEmpty())
        }
    }

    private fun lookupCollisionKey(lookup: LookupElement): String {
        val presentation = LookupElementPresentation()
        lookup.renderElement(presentation)
        return buildString {
            append(lookup.lookupString)
            append('\u0000')
            append(presentation.tailText.orEmpty())
        }
    }

    private fun shouldSuppressAutoPopupForExactMatch(
        parameters: CompletionParameters,
        prefix: String,
        suggestions: List<LookupElement>
    ): Boolean {
        if (parameters.invocationCount > 0 || prefix.isBlank() || suggestions.isEmpty()) return false
        return AikenAutoPopupGuard.consumeSuppressedExactPrefix(parameters.editor, prefix) ||
            suggestions.any { lookup ->
            lookup.allLookupStrings.any { candidate -> candidate == prefix }
        }
    }
}
