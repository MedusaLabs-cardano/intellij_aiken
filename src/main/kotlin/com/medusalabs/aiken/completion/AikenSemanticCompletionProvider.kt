package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import com.medusalabs.aiken.lang.AikenFileType

class AikenSemanticCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val file = parameters.originalFile
        if (file.fileType != AikenFileType) return

        val offset = parameters.offset.coerceIn(0, file.textLength)
        val resolution = AikenCompletionScenarioResolver.resolve(file, offset)
        when (resolution.scenario) {
            AikenCompletionScenario.UseModule,
            AikenCompletionScenario.UseSymbol -> return
            else -> Unit
        }

        val anchor = resolution.anchor
        val prefix = resolution.prefix
        val text = file.text

        val constructibleInvocationSuggestions =
            AikenConstructibleCompletionSupport.invocationFormVariants(parameters, anchor, offset)

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
                        allowBareTypes = resolution.policy.bareTypesAllowed
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
                        allowBareTypes = resolution.policy.bareTypesAllowed
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
                        offset = offset,
                        prefix = prefix,
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
                    allowBareTypes = resolution.policy.bareTypesAllowed
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
                val addedArgumentTyped = shouldShowTypedSuggestions(argumentSuggestions, prefix)
                if (addedArgumentTyped) {
                    addTypedSupplementalSuggestions(parameters, result, argumentSuggestions, prefix, includeNonMatchingSuggestions = true)
                }
                if (resolution.stopAfterArgumentSuggestions && (addedArgumentTyped || argumentSuggestions.isEmpty())) {
                    result.stopHere()
                    return
                }
                if (resolution.policy.lexicalFallbackAllowed) {
                    addOrdinarySemanticSuggestions(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = anchor,
                        offset = offset,
                        prefix = prefix,
                        stopAfter = resolution.policy.typedCompletionStopsFurtherMerging,
                        excludedLookups = argumentSuggestions
                    )
                }
                return
            }
            AikenCompletionScenario.OrdinaryExpression -> {
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

                if (resolution.policy.lexicalFallbackAllowed) {
                    addOrdinarySemanticSuggestions(
                        parameters = parameters,
                        result = result,
                        file = file,
                        anchor = anchor,
                        offset = offset,
                        prefix = prefix,
                        stopAfter = resolution.policy.typedCompletionStopsFurtherMerging,
                        excludedLookups = emptyList()
                    )
                }
                return
            }
            AikenCompletionScenario.UseModule,
            AikenCompletionScenario.UseSymbol -> return
        }
    }

    private fun addSupplementalSuggestions(
        result: CompletionResultSet,
        suggestions: List<LookupElement>
    ) {
        if (suggestions.isEmpty()) return
        val specialResult = result.withPrefixMatcher("")
        for (lookupElement in suggestions) {
            specialResult.addElement(lookupElement)
        }
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
            matchingResult.addElement(lookupElement)
        }

        if (includeNonMatchingSuggestions && nonMatchingSuggestions.isNotEmpty()) {
            val relaxedResult = AikenCompletionSorting.withTypedSorter(parameters, result.withPrefixMatcher(""))
            for (lookupElement in nonMatchingSuggestions) {
                relaxedResult.addElement(lookupElement)
            }
        }
    }

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
        allowBareTypes: Boolean
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
        offset: Int,
        prefix: String,
        stopAfter: Boolean,
        excludedLookups: List<LookupElement>
    ) {
        val semanticVariants =
            anchor
                ?.let { variantsForAnchor(it, offset) }
                .orEmpty()
                .filterNot { lookup ->
                    excludedLookups.any { lookupCollisionKey(it) == lookupCollisionKey(lookup) }
                }
        val semanticResult = AikenCompletionSorting.withOrdinarySorter(parameters, result.withPrefixMatcher(prefix))
        val unimportedResult = AikenCompletionSorting.withOrdinarySorter(parameters, result.withPrefixMatcher(""))
        val unimportedSemanticVariants =
            anchor
                ?.let { currentAnchor ->
                    AikenReferenceVariants.unimportedExportsMatching(
                        currentAnchor,
                        nameMatches = { AikenCompletionPrefixMatching.matches(it, semanticResult.prefixMatcher, prefix) }
                    )
                }
                .orEmpty()
        val unimportedModuleVariants =
            if (prefix.isBlank()) {
                emptyList()
            } else {
                anchor
                    ?.let { currentAnchor ->
                        AikenReferenceVariants.unimportedModulesMatching(currentAnchor) {
                            AikenCompletionPrefixMatching.matches(it, semanticResult.prefixMatcher, prefix)
                        }
                    }
                    .orEmpty()
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
        for (lookupElement in unimportedSemanticVariants) {
            unimportedResult.addElement(lookupElement)
        }
        for (lookupElement in unimportedModuleVariants) {
            unimportedResult.addElement(lookupElement)
        }

        if (stopAfter || AikenCompletionContexts.insideListLiteralContext(file.text, offset)) {
            result.stopHere()
        }
    }

    private fun variantsForAnchor(
        anchor: PsiElement,
        offset: Int
    ): List<LookupElement> {
        val referenceVariants =
            anchor.references
                .asSequence()
                .flatMap { reference -> reference.variants.asSequence() }
                .mapNotNull { it as? LookupElement }
                .distinctBy(::lookupDedupKey)
                .toList()
        val fallbackVariants =
            AikenReferenceVariants.forElement(anchor, offset)
                .mapNotNull { it as? LookupElement }
        return (referenceVariants + fallbackVariants)
            .distinctBy(::lookupDedupKey)
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
}
