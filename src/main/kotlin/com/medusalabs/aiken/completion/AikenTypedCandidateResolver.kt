package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.imports.AikenImportedNameKind
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_CONST_TYPE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_CONSTRUCTIBLE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_EXPORT_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_FUNCTION_SIGNATURE_INDEX_NAME
import com.medusalabs.aiken.index.AikenConstTypeExtractor
import com.medusalabs.aiken.index.AikenConstructibleFieldEntry
import com.medusalabs.aiken.index.AikenConstructibleExtractor
import com.medusalabs.aiken.index.AikenPublicExportExtractor
import com.medusalabs.aiken.index.aikenConstTypeModuleKey
import com.medusalabs.aiken.index.aikenConstTypeTypeKey
import com.medusalabs.aiken.index.aikenConstructibleResultTypeKey
import com.medusalabs.aiken.index.aikenFunctionSignatureGenericReturnAnyKey
import com.medusalabs.aiken.index.aikenFunctionSignatureGenericReturnHeadKey
import com.medusalabs.aiken.index.aikenFunctionSignatureModuleKey
import com.medusalabs.aiken.index.aikenFunctionSignatureReturnTypeKey
import com.medusalabs.aiken.index.decodeAikenConstTypeIndexValues
import com.medusalabs.aiken.index.decodeAikenConstructibleIndexValue
import com.medusalabs.aiken.index.decodeAikenConstructibleReturnTypeIndexValues
import com.medusalabs.aiken.index.decodeAikenExportIndexValue
import com.medusalabs.aiken.index.decodeAikenFunctionReturnTypeIndexValues
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenProjectRoots
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

internal object AikenTypedCandidateResolver {
    interface Resolver {
        fun parseBindingTypeAt(
            text: String,
            declarationOffset: Int,
            bindingName: String,
            anchor: PsiElement,
            visitedDeclarationOffsets: MutableSet<Int> = linkedSetOf()
        ): String?

        fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean

        fun expectedTypeDistance(
            anchor: PsiElement,
            candidateType: String,
            expectedType: AikenExpectedTypeProfile
        ): Int? =
            if (matchesExpectedTypes(candidateType, expectedType.compatibleTypes.keys)) 0 else null

        fun normalizeTypeText(text: String): String

        fun resolveSameFileConstType(
            text: String,
            declarationOffset: Int,
            constName: String,
            anchor: PsiElement
        ): String? =
            parseBindingTypeAt(text, declarationOffset, constName, anchor)

        fun inferFunctionReturnType(
            anchor: PsiElement,
            functionName: String,
            modulePath: String? = null
        ): String? = null

        fun resolveFunctionSignature(
            anchor: PsiElement,
            functionName: String,
            modulePath: String? = null
        ): String? = null

        fun unwrapListType(typeText: String): String? =
            AikenTypeText.unwrapSingleGenericType(typeText, "List") { text -> normalizeTypeText(text) }

        fun unwrapOptionType(typeText: String): String? =
            AikenTypeText.unwrapSingleGenericType(typeText, "Option") { text -> normalizeTypeText(text) }
    }

    fun collectCandidatesForExpectedType(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        extraCandidates: List<AikenTypedCompletionCandidate>,
        context: AikenTypedCandidateContext = AikenTypedCandidateContext.None,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate>()

        fun add(candidate: AikenTypedExpectedTypeCandidate) {
            if (seen.add(candidate.dedupeKey)) {
                result += candidate
            }
        }

        for (candidate in extraCandidates) {
            if (candidate.name in excludedNames) continue
            val matchDistance = resolver.expectedTypeDistance(anchor, candidate.type, expectedType) ?: continue
            add(
                AikenTypedExpectedTypeCandidate.Identifier(
                    name = candidate.name,
                    type = candidate.type,
                    kind = candidate.kind,
                    origin = AikenTypedCandidateOrigin.EXTRA,
                    source = AikenTypedCandidateSource.EXTRA,
                    matchDistance = matchDistance
                )
            )
        }

        for (candidate in collectContextualCandidates(anchor, context, expectedType, excludedNames, resolver)) {
            add(candidate)
        }

        for (binding in collectVisibleTypedBindings(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(binding)
        }

        for (constant in collectVisibleTypedConsts(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(constant)
        }

        for (function in collectVisibleTypedFunctions(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(function)
        }

        for (constant in collectUnimportedConstsReturning(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(constant)
        }

        for (function in collectUnimportedFunctionsReturning(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(function)
        }

        val listItemType = expectedType.compatibleTypes.keys.asSequence().mapNotNull(resolver::unwrapListType).firstOrNull()
        if (listItemType != null) {
            add(AikenTypedExpectedTypeCandidate.ListLiteral)
        }

        val optionInnerType = expectedType.compatibleTypes.keys.asSequence().mapNotNull(resolver::unwrapOptionType).firstOrNull()
        if (optionInnerType != null) {
            add(AikenTypedExpectedTypeCandidate.OptionSome)
        }

        for ((name, kind) in builtInInvariantCandidates(expectedType, resolver)) {
            add(
                AikenTypedExpectedTypeCandidate.Identifier(
                    name = name,
                    type = expectedType.primaryType,
                    kind = kind,
                    origin = AikenTypedCandidateOrigin.BUILTIN,
                    source = AikenTypedCandidateSource.BUILTIN_INVARIANT,
                    matchDistance = 0
                )
            )
        }

        for (constructible in collectVisibleConstructibles(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(constructible)
        }

        for (constructible in collectUnimportedConstructiblesReturning(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(constructible)
        }

        return result
    }

    fun collectCandidatesForExpectedType(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        extraCandidates: List<AikenTypedCompletionCandidate>,
        context: AikenTypedCandidateContext = AikenTypedCandidateContext.None,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate> =
        collectCandidatesForExpectedType(
            anchor = anchor,
            expectedType = expectedType,
            extraCandidates = extraCandidates,
            context = context,
            excludedNames = excludedNames,
            prefix = "",
            resolver = resolver
        )

    private fun builtInInvariantCandidates(
        expectedType: AikenExpectedTypeProfile,
        resolver: Resolver
    ): List<Pair<String, CompletionSymbolKind>> =
        when {
            "Bool" in expectedType.compatibleTypes -> listOf("True" to CompletionSymbolKind.TYPE, "False" to CompletionSymbolKind.TYPE)
            expectedType.compatibleTypes.keys.any { resolver.unwrapOptionType(it) != null } -> listOf("None" to CompletionSymbolKind.TYPE)
            else -> emptyList()
        }

    fun collectSpreadCandidatesForExpectedType(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate>()

        fun add(candidate: AikenTypedExpectedTypeCandidate) {
            if (seen.add(candidate.dedupeKey)) {
                result += candidate
            }
        }

        for (binding in collectVisibleTypedBindings(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(binding)
        }

        for (constant in collectVisibleTypedConsts(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(constant)
        }

        for (constant in collectUnimportedConstsReturning(anchor, expectedType, excludedNames, prefix, resolver)) {
            add(constant)
        }

        return result
    }

    fun collectSpreadCandidatesForExpectedType(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate> =
        collectSpreadCandidatesForExpectedType(
            anchor = anchor,
            expectedType = expectedType,
            excludedNames = excludedNames,
            prefix = "",
            resolver = resolver
        )

    fun collectPipeCandidatesForInputType(
        anchor: PsiElement,
        inputType: AikenExpectedTypeProfile,
        qualifier: String?,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate> {
        val normalizedQualifier = qualifier?.trim().orEmpty().ifEmpty { null }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate>()

        fun add(candidate: AikenTypedExpectedTypeCandidate) {
            if (seen.add(candidate.dedupeKey)) {
                result += candidate
            }
        }

        if (normalizedQualifier != null) {
            for (function in collectQualifiedPipeFunctions(anchor, inputType, normalizedQualifier, excludedNames, resolver)) {
                add(function)
            }
            return result
        }

        for (function in collectVisiblePipeFunctions(anchor, inputType, excludedNames, resolver)) {
            add(function)
        }

        for (function in collectUnimportedPipeFunctions(anchor, inputType, excludedNames, resolver)) {
            add(function)
        }

        return result
    }

    fun collectVisibleTypedBindings(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Identifier> {
        val file = anchor.containingFile ?: return emptyList()
        val caretOffset = anchor.textRange.startOffset
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Identifier>()

        for (binding in AikenLocalScopeAnalyzer.collectVisibleBindings(anchor)) {
            if (binding.name in excludedNames || !seen.add(binding.name)) continue
            val declarationOffset = binding.declarationOffset
            if (isInsideOwnBindingInitializer(file.text, declarationOffset, binding.name, caretOffset)) continue
            if (isInsideOwnPatternDeclaration(file.text, declarationOffset, binding.name, caretOffset)) continue
            val declaredType = resolver.parseBindingTypeAt(file.text, declarationOffset, binding.name, anchor) ?: continue
            val matchDistance = resolver.expectedTypeDistance(anchor, declaredType, expectedType) ?: continue
                result +=
                    AikenTypedExpectedTypeCandidate.Identifier(
                        name = binding.name,
                        type = declaredType,
                        kind = CompletionSymbolKind.IDENTIFIER,
                        origin = AikenTypedCandidateOrigin.LOCAL,
                        source = AikenTypedCandidateSource.BINDING,
                        matchDistance = matchDistance
                    ).withScopeDistance(binding.scopeDistance)
        }

        return result
    }

    fun collectVisibleTypedConsts(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Identifier> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Identifier>()

        for (entry in AikenConstTypeExtractor.extractDeclarations(file.text)) {
            if (entry.name in excludedNames || !seen.add(entry.name)) continue
            val constType = resolver.resolveSameFileConstType(file.text, entry.offset, entry.name, anchor) ?: continue
            val matchDistance = resolver.expectedTypeDistance(anchor, constType, expectedType) ?: continue
                result +=
                    AikenTypedExpectedTypeCandidate.Identifier(
                        name = entry.name,
                        type = constType,
                        kind = CompletionSymbolKind.IDENTIFIER,
                        origin = AikenTypedCandidateOrigin.LOCAL,
                        source = AikenTypedCandidateSource.CONST,
                        matchDistance = matchDistance
                    )
        }

        val importedNames =
            useModel.importedNames().filter { importedName ->
                importedName.kind != AikenImportedNameKind.MODULE_ALIAS
            }

        for (importedName in importedNames) {
            val constType = findImportedConstType(anchor, importedName.statement.modulePath, importedName.sourceName) ?: continue
            val matchDistance = resolver.expectedTypeDistance(anchor, constType, expectedType) ?: continue
            if (importedName.exposedName in excludedNames || !seen.add(importedName.exposedName)) continue
            result +=
                AikenTypedExpectedTypeCandidate.Identifier(
                    name = importedName.exposedName,
                    type = constType,
                    kind = CompletionSymbolKind.IDENTIFIER,
                    origin = AikenTypedCandidateOrigin.IMPORTED,
                    source = AikenTypedCandidateSource.CONST,
                    modulePath = importedName.statement.modulePath,
                    matchDistance = matchDistance
                )
        }

        return result
    }

    fun collectVisibleTypedConsts(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Identifier> =
        collectVisibleTypedConsts(
            anchor = anchor,
            expectedType = expectedType,
            excludedNames = excludedNames,
            prefix = "",
            resolver = resolver
        )

    private fun collectContextualCandidates(
        anchor: PsiElement,
        context: AikenTypedCandidateContext,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Identifier> =
        when (context) {
            AikenTypedCandidateContext.None -> emptyList()
            is AikenTypedCandidateContext.RecordFieldValue ->
                context.siblingFields
                    .asSequence()
                    .filter { it.name !in excludedNames }
                    .mapNotNull { field ->
                        val matchDistance = resolver.expectedTypeDistance(anchor, field.type, expectedType) ?: return@mapNotNull null
                        field to matchDistance
                    }
                    .distinctBy { (field, _) -> field.name }
                    .map { (field, matchDistance) ->
                        AikenTypedExpectedTypeCandidate.Identifier(
                            name = field.name,
                            type = field.type,
                            kind = CompletionSymbolKind.FIELD,
                            origin = AikenTypedCandidateOrigin.LOCAL,
                            source = AikenTypedCandidateSource.RECORD_SIBLING_FIELD,
                            matchDistance = matchDistance
                        )
                    }
                    .toList()
        }

    fun collectVisibleTypedFunctions(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Function> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Function>()

        for ((functionName, signature) in AikenFunctionSignatureExtractor.extract(file.text)) {
            if (functionName in excludedNames || !seen.add(functionName)) continue
            val returnType =
                com.medusalabs.aiken.index.aikenFunctionSignatureReturnType(signature)
                    ?: resolver.inferFunctionReturnType(anchor, functionName)
                    ?: continue
            val matchDistance = resolver.expectedTypeDistance(anchor, returnType, expectedType) ?: continue
            result +=
                AikenTypedExpectedTypeCandidate.Function(
                    name = functionName,
                    signature = signature,
                    origin = AikenTypedCandidateOrigin.LOCAL,
                    matchDistance = matchDistance
                )
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
                        .ifBlank {
                            resolver.resolveFunctionSignature(anchor, importedName.sourceName, importedName.statement.modulePath).orEmpty()
                        }
                if (signature.isEmpty()) continue
                val returnType =
                    com.medusalabs.aiken.index.aikenFunctionSignatureReturnType(signature)
                        ?: resolver.inferFunctionReturnType(anchor, importedName.sourceName, importedName.statement.modulePath)
                        ?: continue
                val matchDistance = resolver.expectedTypeDistance(anchor, returnType, expectedType) ?: continue
                result +=
                    AikenTypedExpectedTypeCandidate.Function(
                        name = importedName.exposedName,
                        signature = signature,
                        origin = AikenTypedCandidateOrigin.IMPORTED,
                        modulePath = importedName.statement.modulePath,
                        matchDistance = matchDistance
                    )
            }
        }

        return result
    }

    fun collectVisiblePipeFunctions(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.PipeFunction> {
        val file = anchor.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate.PipeFunction>()

        for ((functionName, signature) in AikenFunctionSignatureExtractor.extract(file.text)) {
            if (functionName in excludedNames || !seen.add(functionName)) continue
            val matchDistance = pipeInputDistance(anchor, signature, expectedType, resolver) ?: continue
            result +=
                AikenTypedExpectedTypeCandidate.PipeFunction(
                    lookupText = functionName,
                    matchName = functionName,
                    signature = signature,
                    origin = AikenTypedCandidateOrigin.LOCAL,
                    matchDistance = matchDistance
                )
        }

        if (!DumbService.isDumb(anchor.project)) {
            val index = FileBasedIndex.getInstance()
            val scope = AikenSearchScopes.forElement(anchor)
            for (importedName in useModel.importedNames()) {
                if (importedName.kind == AikenImportedNameKind.MODULE_ALIAS) continue
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
                val matchDistance = pipeInputDistance(anchor, signature, expectedType, resolver) ?: continue
                result +=
                    AikenTypedExpectedTypeCandidate.PipeFunction(
                        lookupText = importedName.exposedName,
                        matchName = importedName.exposedName,
                        signature = signature,
                        origin = AikenTypedCandidateOrigin.IMPORTED,
                        modulePath = importedName.statement.modulePath,
                        matchDistance = matchDistance
                    )
            }
        }

        return result
    }

    fun collectQualifiedPipeFunctions(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        qualifier: String?,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.PipeFunction> {
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
        val result = ArrayList<AikenTypedExpectedTypeCandidate.PipeFunction>()
        for ((exposedQualifier, modulePath) in qualifiersToModules) {
            val exportedSymbols = exportedSymbols(anchor, modulePath)
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                val moduleText = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
                for (entry in AikenFunctionSignatureExtractor.extractEntries(moduleText)) {
                    if (entry.name !in exportedSymbols || entry.name in excludedNames) continue
                    val matchDistance = pipeInputDistance(anchor, entry.signature, expectedType, resolver) ?: continue
                    val lookupText = if (qualifier == null) "$exposedQualifier.${entry.name}" else entry.name
                    if (!seen.add(lookupText)) continue
                    result +=
                        AikenTypedExpectedTypeCandidate.PipeFunction(
                            lookupText = lookupText,
                            matchName = entry.name,
                            signature = entry.signature,
                            origin = AikenTypedCandidateOrigin.QUALIFIED,
                            modulePath = modulePath,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        return result
    }

    fun collectVisibleConstructibles(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Constructible> {
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
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Constructible>()

        for (entry in AikenConstructibleExtractor.extract(file.text)) {
            if (entry.ownerName in excludedNames) continue
            val matchDistance = resolver.expectedTypeDistance(anchor, entry.resultTypeName, expectedType) ?: continue
            if (seen.add(entry.ownerName)) {
                result +=
                    AikenTypedExpectedTypeCandidate.Constructible(
                        name = entry.ownerName,
                        resultType = entry.resultTypeName,
                        fields = entry.fields,
                        supportsNamedSyntax = entry.supportsNamedSyntax,
                        origin = AikenTypedCandidateOrigin.LOCAL,
                        matchDistance = matchDistance
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
                    val matchDistance = resolver.expectedTypeDistance(anchor, entry.resultTypeName, expectedType) ?: continue
                    if (seen.add(entry.ownerName)) {
                        val needsImport = entry.ownerName !in directlyAvailableImportedNames[modulePath].orEmpty()
                        result +=
                            AikenTypedExpectedTypeCandidate.Constructible(
                                name = entry.ownerName,
                                resultType = entry.resultTypeName,
                                fields = entry.fields,
                                supportsNamedSyntax = entry.supportsNamedSyntax,
                                modulePath = modulePath,
                                origin = AikenTypedCandidateOrigin.IMPORTED,
                                autoImportMode =
                                    if (needsImport) AikenTypedCandidateAutoImportMode.SYMBOL else AikenTypedCandidateAutoImportMode.NONE,
                                matchDistance = matchDistance
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
                    val matchDistance = resolver.expectedTypeDistance(anchor, entry.resultTypeName, expectedType) ?: continue
                    if (seen.add(entry.ownerName)) {
                        val needsImport = entry.ownerName !in directlyAvailableImportedNames[modulePath].orEmpty()
                        result +=
                            AikenTypedExpectedTypeCandidate.Constructible(
                                name = entry.ownerName,
                                resultType = entry.resultTypeName,
                                fields = entry.fields,
                                supportsNamedSyntax = entry.supportsNamedSyntax,
                                modulePath = modulePath,
                                origin = AikenTypedCandidateOrigin.IMPORTED,
                                autoImportMode =
                                    if (needsImport) AikenTypedCandidateAutoImportMode.SYMBOL else AikenTypedCandidateAutoImportMode.NONE,
                                matchDistance = matchDistance
                            )
                    }
                }
            }
        }

        return result
    }

    fun collectUnimportedFunctionsReturning(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Function> {
        val file = anchor.containingFile ?: return emptyList()
        if (DumbService.isDumb(anchor.project)) return emptyList()

        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val root = AikenProjectRoots.findRootForFile(file.virtualFile)
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
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Function>()
        val prefixMatcher = prefixMatcher(prefix)
        var indexedMatchesFound = false

        for (compatibleType in expectedType.compatibleTypes.keys) {
            for (encodedValue in index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, aikenFunctionSignatureReturnTypeKey(compatibleType), scope)) {
                for (entry in decodeAikenFunctionReturnTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.functionName in excludedNames) continue
                    if (!matchesNamePrefix(entry.functionName, prefixMatcher, prefix)) continue
                    if (entry.functionName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.functionName)) continue
                    val returnType = com.medusalabs.aiken.index.aikenFunctionSignatureReturnType(entry.signature) ?: continue
                    val matchDistance = resolver.expectedTypeDistance(anchor, returnType, expectedType)
                        ?: continue
                    indexedMatchesFound = true
                    result +=
                        AikenTypedExpectedTypeCandidate.Function(
                            name = entry.functionName,
                            signature = entry.signature,
                            origin = AikenTypedCandidateOrigin.UNIMPORTED,
                            modulePath = entry.modulePath,
                            autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        for (genericKey in genericReturnLookupKeys(expectedType)) {
            for (encodedValue in index.getValues(AIKEN_FUNCTION_SIGNATURE_INDEX_NAME, genericKey, scope)) {
                for (entry in decodeAikenFunctionReturnTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.functionName in excludedNames) continue
                    if (!matchesNamePrefix(entry.functionName, prefixMatcher, prefix)) continue
                    if (entry.functionName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.functionName)) continue
                    val returnType = com.medusalabs.aiken.index.aikenFunctionSignatureReturnType(entry.signature) ?: continue
                    val matchDistance = resolver.expectedTypeDistance(anchor, returnType, expectedType) ?: continue
                    indexedMatchesFound = true
                    result +=
                        AikenTypedExpectedTypeCandidate.Function(
                            name = entry.functionName,
                            signature = entry.signature,
                            origin = AikenTypedCandidateOrigin.UNIMPORTED,
                            modulePath = entry.modulePath,
                            autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        if (root != null && !indexedMatchesFound) {
            for (moduleFile in collectModuleFiles(root)) {
                val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
                if (modulePath == currentModulePath) continue

                val moduleText = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
                val exportedSymbols = AikenPublicExportExtractor.extract(moduleText).toSet()
                if (prefixMatcher != null && exportedSymbols.none { name -> matchesNamePrefix(name, prefixMatcher, prefix) }) continue
                for (entry in AikenFunctionSignatureExtractor.extractEntries(moduleText)) {
                    if (entry.name !in exportedSymbols) continue
                    if (entry.name in excludedNames) continue
                    if (!matchesNamePrefix(entry.name, prefixMatcher, prefix)) continue
                    if (entry.name in importedSymbolsByModule[modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(modulePath to entry.name)) continue
                    val returnType =
                        com.medusalabs.aiken.index.aikenFunctionSignatureReturnType(entry.signature)
                            ?: resolver.inferFunctionReturnType(anchor, entry.name, modulePath)
                            ?: continue
                    val matchDistance = resolver.expectedTypeDistance(anchor, returnType, expectedType) ?: continue
                    result +=
                        AikenTypedExpectedTypeCandidate.Function(
                            name = entry.name,
                            signature = entry.signature,
                            origin = AikenTypedCandidateOrigin.UNIMPORTED,
                            modulePath = modulePath,
                            autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        return result
    }

    fun collectUnimportedPipeFunctions(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.PipeFunction> {
        val file = anchor.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(file.text)
        val importedSymbolsByModule =
            useModel.statements.associate { statement ->
                statement.modulePath.trim() to
                    statement.items
                        .mapTo(LinkedHashSet()) { item -> item.name.trim() }
                        .filter { it.isNotBlank() }
            }
        val seenModulesAndNames = LinkedHashSet<Pair<String, String>>()
        val result = ArrayList<AikenTypedExpectedTypeCandidate.PipeFunction>()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath == currentModulePath) continue

            val moduleText = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            val exportedSymbols = AikenPublicExportExtractor.extract(moduleText).toSet()
            for (entry in AikenFunctionSignatureExtractor.extractEntries(moduleText)) {
                if (entry.name !in exportedSymbols) continue
                if (entry.name in excludedNames) continue
                if (entry.name in importedSymbolsByModule[modulePath].orEmpty()) continue
                val matchDistance = pipeInputDistance(anchor, entry.signature, expectedType, resolver) ?: continue
                if (!seenModulesAndNames.add(modulePath to entry.name)) continue
                result +=
                    AikenTypedExpectedTypeCandidate.PipeFunction(
                        lookupText = entry.name,
                        matchName = entry.name,
                        signature = entry.signature,
                        origin = AikenTypedCandidateOrigin.UNIMPORTED,
                        modulePath = modulePath,
                        autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                        matchDistance = matchDistance
                    )
            }
        }

        return result
    }

    fun collectUnimportedConstsReturning(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Identifier> {
        val file = anchor.containingFile ?: return emptyList()
        if (DumbService.isDumb(anchor.project)) return emptyList()

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
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Identifier>()

        for (compatibleType in expectedType.compatibleTypes.keys) {
            for (encodedValue in index.getValues(AIKEN_CONST_TYPE_INDEX_NAME, aikenConstTypeTypeKey(compatibleType), scope)) {
                for (entry in decodeAikenConstTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.constName in excludedNames) continue
                    if (entry.constName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.constName)) continue
                    val matchDistance = resolver.expectedTypeDistance(anchor, entry.type, expectedType) ?: continue
                    result +=
                        AikenTypedExpectedTypeCandidate.Identifier(
                            name = entry.constName,
                            type = entry.type,
                            kind = CompletionSymbolKind.IDENTIFIER,
                            origin = AikenTypedCandidateOrigin.UNIMPORTED,
                            source = AikenTypedCandidateSource.CONST,
                            modulePath = entry.modulePath,
                            autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        val importedModules = importedSymbolsByModule.keys.filter { it.isNotBlank() && it != currentModulePath }
        for (modulePath in importedModules) {
            val exportedSymbols = exportedSymbols(anchor, modulePath)
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                for (entry in AikenConstTypeExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8))) {
                    if (entry.name !in exportedSymbols) continue
                    if (entry.name in excludedNames) continue
                    if (entry.name in importedSymbolsByModule[modulePath].orEmpty()) continue
                    val matchDistance = resolver.expectedTypeDistance(anchor, entry.type, expectedType) ?: continue
                    if (!seenModulesAndNames.add(modulePath to entry.name)) continue
                    result +=
                        AikenTypedExpectedTypeCandidate.Identifier(
                            name = entry.name,
                            type = entry.type,
                            kind = CompletionSymbolKind.IDENTIFIER,
                            origin = AikenTypedCandidateOrigin.UNIMPORTED,
                            source = AikenTypedCandidateSource.CONST,
                            modulePath = modulePath,
                            autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        return result
    }

    fun collectUnimportedConstructiblesReturning(
        anchor: PsiElement,
        expectedType: AikenExpectedTypeProfile,
        excludedNames: Set<String>,
        prefix: String = "",
        resolver: Resolver
    ): List<AikenTypedExpectedTypeCandidate.Constructible> {
        val file = anchor.containingFile ?: return emptyList()
        if (DumbService.isDumb(anchor.project)) return emptyList()

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
        val result = ArrayList<AikenTypedExpectedTypeCandidate.Constructible>()

        for (compatibleType in expectedType.compatibleTypes.keys) {
            for (encodedValue in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, aikenConstructibleResultTypeKey(compatibleType), scope)) {
                for (entry in decodeAikenConstructibleReturnTypeIndexValues(encodedValue)) {
                    if (entry.modulePath == currentModulePath) continue
                    if (entry.ownerName in excludedNames) continue
                    if (entry.ownerName in importedSymbolsByModule[entry.modulePath].orEmpty()) continue
                    if (!seenModulesAndNames.add(entry.modulePath to entry.ownerName)) continue
                    val matchDistance = resolver.expectedTypeDistance(anchor, entry.resultTypeName, expectedType) ?: continue
                    val detailedEntry =
                        AikenModuleFiles
                            .findFilesForModulePath(file.virtualFile, entry.modulePath)
                            .asSequence()
                            .flatMap { moduleFile ->
                                AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8)).asSequence()
                            }
                            .firstOrNull { constructible ->
                                constructible.ownerName == entry.ownerName &&
                                    resolver.normalizeTypeText(constructible.resultTypeName) == resolver.normalizeTypeText(entry.resultTypeName)
                            }
                    result +=
                        AikenTypedExpectedTypeCandidate.Constructible(
                            name = entry.ownerName,
                            resultType = entry.resultTypeName,
                            fields = detailedEntry?.fields.orEmpty(),
                            supportsNamedSyntax = detailedEntry?.supportsNamedSyntax == true,
                            modulePath = entry.modulePath,
                            origin = AikenTypedCandidateOrigin.UNIMPORTED,
                            autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL,
                            matchDistance = matchDistance
                        )
                }
            }
        }

        return result
    }

    internal fun findImportedConstType(
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

    private fun isInsideOwnBindingInitializer(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        caretOffset: Int
    ): Boolean =
        AikenBindingInitializerScanner.isInsideOwnInitializer(
            text = text,
            declarationOffset = declarationOffset,
            bindingName = bindingName,
            caretOffset = caretOffset
        )

    private fun isInsideOwnPatternDeclaration(
        text: String,
        declarationOffset: Int,
        bindingName: String,
        caretOffset: Int
    ): Boolean =
        AikenBindingInitializerScanner.isInsideOwnPatternDeclaration(
            text = text,
            declarationOffset = declarationOffset,
            bindingName = bindingName,
            caretOffset = caretOffset
        )

    private fun firstParameterType(signature: String): String? =
        AikenFunctionSignatureText.parameterTypeAt(signature, 0)

    private fun pipeInputDistance(
        anchor: PsiElement,
        signature: String,
        expectedType: AikenExpectedTypeProfile,
        resolver: Resolver
    ): Int? {
        val firstParameterType = firstParameterType(signature) ?: return null
        return resolver.expectedTypeDistance(anchor, firstParameterType, expectedType)
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

    private fun prefixMatcher(prefix: String): PlainPrefixMatcher? =
        prefix.trim().takeIf { it.isNotEmpty() }?.let(::PlainPrefixMatcher)

    private fun matchesNamePrefix(
        candidateName: String,
        prefixMatcher: PlainPrefixMatcher?,
        prefix: String
    ): Boolean =
        prefixMatcher == null || AikenCompletionPrefixMatching.matches(candidateName, prefixMatcher, prefix)

    private fun genericReturnLookupKeys(expectedType: AikenExpectedTypeProfile): Set<String> {
        val keys = LinkedHashSet<String>()
        keys += aikenFunctionSignatureGenericReturnAnyKey()
        for (compatibleType in expectedType.compatibleTypes.keys) {
            val genericHeadKey = genericHeadLookupKey(compatibleType)
            if (genericHeadKey != null) {
                keys += genericHeadKey
            }
        }
        return keys
    }

    private fun genericHeadLookupKey(typeText: String): String? {
        val normalized = typeText.trim()
        if (!normalized.contains('<') || !normalized.endsWith(">")) return null
        val head = normalized.substringBefore('<').trim()
        return head.takeIf { it.isNotEmpty() }?.let(::aikenFunctionSignatureGenericReturnHeadKey)
    }
}
