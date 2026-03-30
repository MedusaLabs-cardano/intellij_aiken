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
    private const val LIST_LITERAL_PRIORITY = 5200.0
    private const val EXTRA_TYPED_CANDIDATE_PRIORITY = 5000.0
    private const val TYPED_BINDING_PRIORITY = 4900.0
    private const val LOCAL_TYPED_CONST_PRIORITY = 4895.0
    private const val IMPORTED_TYPED_CONST_PRIORITY = 4890.0
    private const val LOCAL_TYPED_FUNCTION_PRIORITY = 4885.0
    private const val IMPORTED_TYPED_FUNCTION_PRIORITY = 4880.0
    private const val UNIMPORTED_CONST_PRIORITY = 4875.0
    private const val UNIMPORTED_FUNCTION_PRIORITY = 4850.0
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
        val effectiveType = effectiveExpectedType(expectedType, currentValueText) ?: return emptyList()
        val normalizedExpectedType = normalizeTypeText(effectiveType)
        if (normalizedExpectedType.isEmpty()) return emptyList()
        val equivalentExpectedTypes = equivalentTypeNames(anchor, normalizedExpectedType)

        val lookups = ArrayList<LookupElement>()
        val seen = LinkedHashSet<String>()
        val typedBindings = collectVisibleTypedBindings(anchor, equivalentExpectedTypes, excludedNames)

        for (candidate in extraCandidates) {
            if (candidate.name in excludedNames) continue
            if (normalizeTypeText(candidate.type) !in equivalentExpectedTypes || !seen.add(candidate.name)) continue
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
        if (listItemType != null && typedBindings.isEmpty() && seen.add("[]")) {
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
                    createAutoImportedConstructibleLookup(anchor, constructible, normalizedExpectedType)
                } else {
                    createTypeDirectedLookup(constructible.name, CompletionSymbolKind.TYPE, CONSTRUCTIBLE_PRIORITY, constructible.resultType)
                }
        }

        for (constructible in collectUnimportedConstructiblesReturning(anchor, equivalentExpectedTypes, excludedNames, seen)) {
            if (!seen.add(constructible.name)) continue
            lookups += createAutoImportedConstructibleLookup(anchor, constructible, normalizedExpectedType)
        }

        return lookups
    }

    private fun effectiveExpectedType(expectedType: String, currentValueText: String): String? {
        var effectiveType = normalizeTypeText(expectedType)
        for (wrapper in openValueWrappers(currentValueText)) {
            effectiveType =
                when (wrapper) {
                    ValueWrapper.LIST_LITERAL -> unwrapListType(effectiveType)
                    ValueWrapper.OPTION_SOME -> unwrapOptionType(effectiveType)
                } ?: return null
        }
        return effectiveType
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
            val declarationOffset =
                AikenLocalScopeAnalyzer.findDeclarationOffset(document, binding.name, caretOffset) ?: continue
            val declaredType = parseBindingTypeAt(file.text, declarationOffset, binding.name, anchor) ?: continue
            if (normalizeTypeText(declaredType) in expectedTypes) {
                result += TypedBinding(binding.name, declaredType)
            }
        }

        return result
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

        for (entry in AikenConstTypeExtractor.extract(file.text)) {
            if (entry.name in excludedNames || !seen.add(entry.name)) continue
            if (normalizeTypeText(entry.type) in expectedTypes) {
                result += TypedConstSuggestion(entry.name, entry.type, LOCAL_TYPED_CONST_PRIORITY)
            }
        }

        val importedNames =
            useModel.importedNames().filter { importedName ->
                importedName.kind != com.medusalabs.aiken.imports.AikenImportedNameKind.MODULE_ALIAS
            }

        for (importedName in importedNames) {
            val constType = findImportedConstType(anchor, importedName.statement.modulePath, importedName.sourceName) ?: continue
            if (normalizeTypeText(constType) !in expectedTypes) continue
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
            if (normalizeTypeText(returnType) !in expectedTypes) continue
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
                if (normalizeTypeText(returnType) !in expectedTypes) continue
                result += VisibleFunctionSuggestion(importedName.exposedName, signature, IMPORTED_TYPED_FUNCTION_PRIORITY)
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
            if (normalizeTypeText(entry.resultTypeName) in expectedTypes && seen.add(entry.ownerName)) {
                result += ConstructibleSuggestion(entry.ownerName, entry.resultTypeName)
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
                    if (normalizeTypeText(entry.resultTypeName) in expectedTypes && seen.add(entry.ownerName)) {
                        result +=
                            ConstructibleSuggestion(
                                name = entry.ownerName,
                                resultType = entry.resultTypeName,
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
                    if (normalizeTypeText(entry.resultTypeName) in expectedTypes && seen.add(entry.ownerName)) {
                        result +=
                            ConstructibleSuggestion(
                                name = entry.ownerName,
                                resultType = entry.resultTypeName,
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
                    if (normalizeTypeText(entry.type) !in expectedTypes) continue
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
                    result +=
                        ConstructibleSuggestion(
                            name = entry.ownerName,
                            resultType = entry.resultTypeName,
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

        if (text.startsWith("True", index) && isTokenBoundary(text, index + "True".length)) return "Bool"
        if (text.startsWith("False", index) && isTokenBoundary(text, index + "False".length)) return "Bool"

        val headRange = readQualifiedIdentifierRange(text, index) ?: return null
        val head = text.substring(headRange.first, headRange.last + 1)
        val nextIndex = skipWhitespace(text, headRange.last + 1)
        val nextChar = text.getOrNull(nextIndex)

        return when (nextChar) {
            '{' -> resolveConstructibleResultType(anchor, head) ?: head.substringAfterLast('.')
            '(' -> {
                resolveConstructibleResultType(anchor, head)
                    ?: resolveFunctionReturnType(anchor, head)
                    ?: resolveConstType(anchor, head)
                    ?: resolveVisibleBindingType(anchor, head, declarationOffset, visitedDeclarationOffsets)
            }
            else -> {
                resolveConstType(anchor, head)
                    ?: resolveVisibleBindingType(anchor, head, declarationOffset, visitedDeclarationOffsets)
                    ?: resolveConstructibleResultType(anchor, head)
                    ?: resolveFunctionReturnType(anchor, head)
                }
        }
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
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

        AikenConstTypeExtractor.extract(file.text).firstOrNull { it.name == name }?.let { return it.type }

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

    private fun createAutoImportedConstructibleLookup(
        anchor: PsiElement,
        suggestion: ConstructibleSuggestion,
        expectedType: String
    ): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder
                .create(suggestion.name)
                .withIcon(AllIcons.Nodes.Class)
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
                                suggestion.modulePath ?: return@runWriteCommandAction,
                                suggestion.name
                            )
                            insertionContext.commitDocument()
                        }
                    }
                },
            CONSTRUCTIBLE_PRIORITY
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

    private fun openValueWrappers(text: String): List<ValueWrapper> {
        val wrappers = ArrayDeque<ValueWrapper>()
        val parenFrames = ArrayDeque<ParenFrame>()
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
                wrappers.addLast(ValueWrapper.OPTION_SOME)
                parenFrames.addLast(ParenFrame.OptionSome)
                index += "Some(".length
                continue
            }

            when (ch) {
                '(' -> parenFrames.addLast(ParenFrame.Other)
                ')' -> {
                    if (parenFrames.removeLastOrNull() == ParenFrame.OptionSome) {
                        removeLastWrapperOccurrence(wrappers, ValueWrapper.OPTION_SOME)
                    }
                }
                '[' -> wrappers.addLast(ValueWrapper.LIST_LITERAL)
                ']' -> removeLastWrapperOccurrence(wrappers, ValueWrapper.LIST_LITERAL)
            }
            index++
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

    private fun removeLastWrapperOccurrence(
        wrappers: ArrayDeque<ValueWrapper>,
        wrapper: ValueWrapper
    ) {
        for (index in wrappers.lastIndex downTo 0) {
            if (wrappers[index] == wrapper) {
                wrappers.removeAt(index)
                break
            }
        }
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

    private data class TypedBinding(
        val name: String,
        val type: String
    )

    private data class ConstructibleSuggestion(
        val name: String,
        val resultType: String,
        val modulePath: String? = null,
        val needsImport: Boolean = false
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

    private data class FunctionCallTemplate(
        val text: String,
        val caretOffset: Int?,
        val shouldTriggerAutoPopup: Boolean
    )

    private enum class ValueWrapper {
        LIST_LITERAL,
        OPTION_SOME
    }

    private enum class ParenFrame {
        OptionSome,
        Other
    }
}
