package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenImportedNameKind
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_EXPORT_INDEX_NAME
import com.medusalabs.aiken.index.AikenPublicExportExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolEntry
import com.medusalabs.aiken.index.AikenTopLevelSymbolExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.decodeAikenExportIndexValue
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.navigation.AikenTopLevelSymbolLookup
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenProjectRoots
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

object AikenReferenceVariants {
    private data class AutoImportedLookupSpec(
        val text: String,
        val icon: javax.swing.Icon,
        val kind: CompletionSymbolKind,
        val typeText: String,
        val tailText: String,
        val insertionFamily: AikenReferenceInsertionFamily,
        val rankingCategory: AikenOrdinaryCompletionCategory
    )
    private val lookupKinds =
        setOf(
            AikenTopLevelSymbolKind.FUNCTION,
            AikenTopLevelSymbolKind.TYPE,
            AikenTopLevelSymbolKind.CONST,
            AikenTopLevelSymbolKind.CONSTRUCTOR
        )
    private val builtInTypeNames =
        listOf(
            "Bool",
            "ByteArray",
            "Data",
            "Fuzzer",
            "G1Element",
            "G2Element",
            "Int",
            "List",
            "MillerLoopResult",
            "Never",
            "Option",
            "Ordering",
            "PRNG",
            "Pair",
            "Sampler",
            "String",
            "Void"
        )

    fun forElement(element: PsiElement): Array<Any> = forElement(element, null)

    fun forElement(
        element: PsiElement,
        caretOffsetOverride: Int? = null
    ): Array<Any> {
        val file = element.containingFile ?: return emptyArray()
        if (file.fileType != AikenFileType) return emptyArray()

        val text = file.text
        val offset = caretOffsetOverride?.coerceIn(0, text.length) ?: element.textRange.startOffset
        val candidateOffsets =
            linkedSetOf(
                offset.coerceIn(0, text.length),
                element.textRange.endOffset.coerceIn(0, text.length),
                (element.textRange.endOffset + 1).coerceIn(0, text.length)
            )
        if (candidateOffsets.any { AikenRecordCompletionSupport.isRecordFieldNameContext(text, it) }) return emptyArray()
        val allowBareTypes = AikenCompletionScenarioPolicies.forFile(file, offset).bareTypesAllowed
        val useModel = AikenUseStatementParser.parseModel(text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val qualifier = AikenSyntaxText.qualifierBeforeOffset(text, offset)
        val seen = LinkedHashSet<String>()
        val variants = ArrayList<Any>()

        if (qualifier != null) {
            return qualifiedVariants(element, qualifier, allowBareTypes, offset).toTypedArray()
        }

        val document = PsiDocumentManager.getInstance(element.project).getDocument(file)
        val caretOffset = offset
        for (binding in AikenLocalScopeAnalyzer.collectVisibleBindings(element)) {
            if (document != null && isInsideOwnBindingInitializer(text, binding.declarationOffset, binding.name, caretOffset)) {
                continue
            }
            addVariant(
                element,
                variants,
                seen,
                binding.name,
                binding.name,
                CompletionSymbolKind.IDENTIFIER,
                null,
                allowBareTypes,
                AikenOrdinaryCompletionCategory.LOCAL
            )
        }

        for (importedName in useModel.importedNames()) {
            val kind =
                when (importedName.kind) {
                    AikenImportedNameKind.MODULE_ALIAS -> CompletionSymbolKind.IDENTIFIER
                    AikenImportedNameKind.ITEM,
                    AikenImportedNameKind.ITEM_ALIAS ->
                        inferTopLevelKind(element, importedName.statement.modulePath, importedName.sourceName)
                }
            val rankingCategory =
                when (importedName.kind) {
                    AikenImportedNameKind.MODULE_ALIAS -> AikenOrdinaryCompletionCategory.IMPORTED_MODULE
                    AikenImportedNameKind.ITEM,
                    AikenImportedNameKind.ITEM_ALIAS -> AikenOrdinaryCompletionCategory.IMPORTED_SYMBOL
                }
            addVariant(
                element,
                variants,
                seen,
                importedName.exposedName,
                importedName.sourceName,
                kind,
                importedName.statement.modulePath,
                allowBareTypes,
                rankingCategory
            )
        }

        for (statement in useModel.statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isBlank() || statement.items.isNotEmpty() || !statement.moduleAlias.isNullOrBlank()) continue
            val exposedModuleName = modulePath.substringAfterLast('/').trim()
            if (exposedModuleName.length < 2 || !seen.add(exposedModuleName)) continue
            variants +=
                AikenCompletionSorting.annotate(
                    com.intellij.codeInsight.lookup.LookupElementBuilder
                        .create(exposedModuleName)
                        .withIcon(com.intellij.icons.AllIcons.Nodes.Package)
                        .withTypeText("module", true),
                    AikenOrdinaryCompletionCategory.IMPORTED_MODULE,
                    CompletionSymbolKind.IDENTIFIER
                )
        }

        for (entry in AikenTopLevelSymbolExtractor.extract(text)) {
            addVariant(
                element,
                variants,
                seen,
                entry.name,
                entry.name,
                mapTopLevelKind(entry),
                currentModulePath,
                allowBareTypes,
                AikenOrdinaryCompletionCategory.SAME_FILE
            )
        }

        if (allowBareTypes) {
            for (typeName in builtInTypeNames) {
                addVariant(
                    anchor = element,
                    variants = variants,
                    seen = seen,
                    lookupName = typeName,
                    symbolName = typeName,
                    kind = CompletionSymbolKind.TYPE,
                    modulePath = null,
                    allowBareTypes = true,
                    rankingCategory = AikenOrdinaryCompletionCategory.SAME_FILE
                )
            }
        }

        val prefix = AikenSyntaxText.identifierPrefix(text, offset)
        if (prefix.isNotEmpty()) {
            for (lookup in unimportedExportsForPrefix(element, prefix, allowBareTypes = allowBareTypes)) {
                variants += lookup
            }
        }

        return variants.toTypedArray()
    }

    fun unimportedExportsForPrefix(
        element: PsiElement,
        prefix: String,
        excludedNames: Set<String> = emptySet(),
        allowBareTypes: Boolean = AikenCompletionScenarioPolicies.forElement(element).bareTypesAllowed
    ): List<LookupElement> =
        unimportedExportsMatching(
            element = element,
            nameMatches = { it.startsWith(prefix, ignoreCase = true) },
            excludedNames = excludedNames,
            allowBareTypes = allowBareTypes
        )

    fun unimportedModulesMatching(
        element: PsiElement,
        nameMatches: (String) -> Boolean
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        if (file.fileType != AikenFileType) return emptyList()

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val importedModuleNames =
            useModel.statements
                .mapNotNull { statement ->
                    val modulePath = statement.modulePath.trim()
                    if (modulePath.isBlank()) return@mapNotNull null
                    statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: modulePath.substringAfterLast('/')
                }
                .filter { it.isNotBlank() }
                .toSet()
        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()
        val result = ArrayList<LookupElement>()
        val seenModulePaths = LinkedHashSet<String>()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath.isBlank() || modulePath == currentModulePath || !seenModulePaths.add(modulePath)) continue
            val exposedModuleName = modulePath.substringAfterLast('/').trim()
            if (exposedModuleName.isBlank() || exposedModuleName in importedModuleNames) continue
            if (!nameMatches(exposedModuleName) && !nameMatches(modulePath)) continue
            result += createAutoImportedModuleLookup(modulePath, exposedModuleName)
        }

        return result
    }

    fun qualifiedVariants(
        element: PsiElement,
        qualifier: String,
        allowBareTypes: Boolean = AikenCompletionScenarioPolicies.forElement(element).bareTypesAllowed,
        offsetExclusive: Int? = null
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        if (file.fileType != AikenFileType) return emptyList()

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val seen = LinkedHashSet<String>()
        val variants = ArrayList<Any>()
        val effectiveOffset =
            offsetExclusive?.coerceIn(0, file.text.length)
                ?: element.textRange.endOffset.coerceIn(0, file.text.length)
        val qualifierChain =
            AikenSyntaxText.qualifiedChainBeforeOffset(file.text, effectiveOffset)
                ?.takeIf { it.isNotBlank() }
                ?: qualifier
        val prefix = AikenSyntaxText.identifierPrefix(file.text, effectiveOffset)
        val resolvedTargets = useModel.resolveModuleTargets(qualifier).mapTo(LinkedHashSet()) { it.modulePath }
        val importedChainTargets = importedModulePathsForQualifierChain(useModel, qualifierChain)
        val importedTargets = (resolvedTargets + importedChainTargets).toList()
        val importedTargetSet = importedTargets.toCollection(LinkedHashSet())

        for (modulePath in importedTargets) {
            val canonicalQualifier =
                canonicalQualifierForImportedModule(
                    useModel = useModel,
                    modulePath = modulePath,
                    qualifierChain = qualifierChain,
                    fallbackQualifier = qualifier
                )
            val needsQualifierRewrite = canonicalQualifier != qualifierChain
            for (symbol in exportedSymbols(element, modulePath)) {
                addImportedQualifiedVariant(
                    anchor = element,
                    variants = variants,
                    seen = seen,
                    lookupName = symbol,
                    symbolName = symbol,
                    kind = inferTopLevelKind(element, modulePath, symbol),
                    modulePath = modulePath,
                    allowBareTypes = allowBareTypes,
                    canonicalQualifier = canonicalQualifier,
                    needsQualifierRewrite = needsQualifierRewrite
                )
            }
        }

        variants.addAll(
            unimportedQualifiedVariants(
                element = element,
                qualifier = qualifierChain,
                allowBareTypes = allowBareTypes,
                excludedModulePaths = importedTargetSet
            )
        )

        variants.addAll(
            qualifiedModuleContinuations(
                element = element,
                useModel = useModel,
                qualifierChain = qualifierChain,
                prefix = prefix
            )
        )

        return variants.mapNotNull { it as? LookupElement }
    }

    fun unimportedExportsMatching(
        element: PsiElement,
        nameMatches: (String) -> Boolean,
        excludedNames: Set<String> = emptySet(),
        allowBareTypes: Boolean = AikenCompletionScenarioPolicies.forElement(element).bareTypesAllowed
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        if (file.fileType != AikenFileType) return emptyList()

        val useModel = AikenUseStatementParser.parseModel(file.text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val importedSymbolsByModule =
            useModel.statements.associate { statement ->
                statement.modulePath.trim() to
                    statement.items
                        .mapTo(LinkedHashSet()) { item -> item.name.trim() }
                        .filter { it.isNotBlank() }
            }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<LookupElement>()
        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath.isBlank() || modulePath == currentModulePath) continue
            val text = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            val exportedNames = AikenPublicExportExtractor.extract(text).toSet()
            for (entry in AikenTopLevelSymbolExtractor.extract(text)) {
                if (entry.name !in exportedNames) continue
                if (!nameMatches(entry.name)) continue
                if (entry.name in excludedNames || entry.name in importedSymbolsByModule[modulePath].orEmpty() || !seen.add("$modulePath::${entry.name}")) continue
                createAutoImportedExportLookup(modulePath, text, entry.name, mapTopLevelKind(entry), allowBareTypes)?.let(result::add)
            }
        }

        return result
    }

    internal fun autoImportedExportInsertionFamily(
        modulePath: String,
        symbolName: String
    ): AikenReferenceInsertionFamily =
        AikenReferenceInsertionFamily.AutoImportedSymbol(
            modulePath = modulePath,
            symbolName = symbolName
        )

    internal fun autoImportedQualifiedMemberInsertionFamily(
        modulePath: String,
        symbolName: String,
        canonicalQualifier: String,
        triggerMemberAutoPopup: Boolean = false
    ): AikenReferenceInsertionFamily =
        AikenReferenceInsertionFamily.AutoImportedQualifiedMember(
            modulePath = modulePath,
            symbolName = symbolName,
            canonicalQualifier = canonicalQualifier,
            triggerMemberAutoPopup = triggerMemberAutoPopup
        )

    internal fun autoImportedModuleInsertionFamily(
        modulePath: String,
        exposedModuleName: String
    ): AikenReferenceInsertionFamily =
        AikenReferenceInsertionFamily.AutoImportedModuleQualifier(
            modulePath = modulePath,
            exposedModuleName = exposedModuleName
        )

    private fun addVariant(
        anchor: PsiElement,
        variants: MutableList<Any>,
        seen: MutableSet<String>,
        lookupName: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        modulePath: String?,
        allowBareTypes: Boolean,
        rankingCategory: AikenOrdinaryCompletionCategory
    ) {
        if (lookupName.isBlank() || lookupName.length < 2 || !seen.add(lookupName)) return
        createVariantLookup(anchor, lookupName, symbolName, kind, modulePath, allowBareTypes, rankingCategory)
            ?.let(variants::add)
    }

    private fun addImportedQualifiedVariant(
        anchor: PsiElement,
        variants: MutableList<Any>,
        seen: MutableSet<String>,
        lookupName: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        modulePath: String,
        allowBareTypes: Boolean,
        canonicalQualifier: String,
        needsQualifierRewrite: Boolean
    ) {
        if (lookupName.isBlank() || lookupName.length < 2 || !seen.add(lookupName)) return
        val baseLookup =
            createVariantLookup(
                anchor = anchor,
                lookupName = lookupName,
                symbolName = symbolName,
                kind = kind,
                modulePath = modulePath,
                allowBareTypes = allowBareTypes,
                rankingCategory = AikenOrdinaryCompletionCategory.IMPORTED_SYMBOL
            ) ?: return
        if (!needsQualifierRewrite) {
            variants += baseLookup
            return
        }
        variants +=
            LookupElementDecorator.withInsertHandler(baseLookup) { insertionContext, _ ->
                replaceCurrentQualifiedPrefix(
                    insertionContext = insertionContext,
                    replacementText = "$canonicalQualifier.$symbolName"
                )
                insertionContext.commitDocument()
            }
    }

    private fun createVariantLookup(
        anchor: PsiElement,
        lookupName: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        modulePath: String?,
        allowBareTypes: Boolean,
        rankingCategory: AikenOrdinaryCompletionCategory
    ): LookupElement? {
        if (kind != CompletionSymbolKind.TYPE) {
            return CompletionItemFactory.create(lookupName, kind, rankingCategory = rankingCategory)
        }

        val constructible =
            AikenConstructibleCompletionSupport.findVisibleConstructible(anchor, symbolName, modulePath)
        if (constructible == null) {
            return if (allowBareTypes) {
                CompletionItemFactory.create(lookupName, kind, rankingCategory = rankingCategory)
            } else {
                null
            }
        }

        return AikenCompletionSorting.annotate(
            AikenConstructibleCompletionSupport.createVisibleLookup(
                constructible = constructible,
                typeText = "type",
                lookupName = lookupName
            ),
            rankingCategory,
            CompletionSymbolKind.TYPE
        )
    }

    private fun mapTopLevelKind(entry: AikenTopLevelSymbolEntry): CompletionSymbolKind =
        when (entry.kind) {
            AikenTopLevelSymbolKind.FUNCTION -> CompletionSymbolKind.FUNCTION
            AikenTopLevelSymbolKind.TYPE,
            AikenTopLevelSymbolKind.CONSTRUCTOR -> CompletionSymbolKind.TYPE
            AikenTopLevelSymbolKind.CONST -> CompletionSymbolKind.IDENTIFIER
        }

    private fun inferTopLevelKind(
        anchor: PsiElement,
        modulePath: String,
        symbolName: String
    ): CompletionSymbolKind {
        val target =
            AikenTopLevelSymbolLookup.findTargets(anchor, symbolName, lookupKinds, setOf(modulePath)).firstOrNull()
                ?: return heuristicKind(symbolName)

        return when (target.node?.elementType) {
            AikenTokenTypes.FUNCTION -> CompletionSymbolKind.FUNCTION
            AikenTokenTypes.TYPE -> CompletionSymbolKind.TYPE
            AikenTokenTypes.FIELD -> CompletionSymbolKind.FIELD
            else -> heuristicKind(symbolName)
        }
    }

    private fun heuristicKind(symbolName: String): CompletionSymbolKind =
        if (symbolName.firstOrNull()?.isUpperCase() == true) CompletionSymbolKind.TYPE else CompletionSymbolKind.FUNCTION

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

    private fun exportedSymbols(anchor: PsiElement, modulePath: String): List<String> {
        val project = anchor.project
        val names = LinkedHashSet<String>()

        for (moduleFile in AikenModuleFiles.findFilesForModulePath(anchor.containingFile?.virtualFile, modulePath)) {
            val text = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            names += AikenPublicExportExtractor.extract(text)
        }

        if (names.isNotEmpty()) return names.toList()
        if (DumbService.isDumb(project)) return emptyList()

        return try {
            val scope = AikenSearchScopes.forElement(anchor)
            val index = FileBasedIndex.getInstance()
            for (value in index.getValues(AIKEN_EXPORT_INDEX_NAME, modulePath, scope)) {
                names += decodeAikenExportIndexValue(value)
            }
            names.toList()
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun collectModuleFiles(root: VirtualFile): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()

        fun walk(directory: VirtualFile?) {
            if (directory == null || !directory.isValid || !directory.isDirectory) return
            for (child in directory.children) {
                when {
                    child.isDirectory -> walk(child)
                    child.fileType == AikenFileType -> result += child
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

    private fun createAutoImportedExportLookup(
        modulePath: String,
        moduleText: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        allowBareTypes: Boolean
    ): LookupElement? {
        if (kind == CompletionSymbolKind.TYPE) {
            val constructible =
                AikenConstructibleCompletionSupport.findConstructibleInModuleText(modulePath, moduleText, symbolName)
            if (constructible != null) {
                return AikenConstructibleCompletionSupport.createAutoImportedLookup(
                    constructible = constructible,
                    typeText = "type"
                ).let {
                    AikenCompletionSorting.annotate(
                        it,
                        AikenOrdinaryCompletionCategory.UNIMPORTED_SYMBOL,
                        CompletionSymbolKind.TYPE
                    )
                }
            }
            if (!allowBareTypes) return null
        }

        return createAutoImportedLookup(
            AutoImportedLookupSpec(
                text = symbolName,
                icon =
                    when (kind) {
                        CompletionSymbolKind.TYPE -> com.intellij.icons.AllIcons.Nodes.Class
                        CompletionSymbolKind.FUNCTION -> com.intellij.icons.AllIcons.Nodes.Method
                        CompletionSymbolKind.FIELD -> com.intellij.icons.AllIcons.Nodes.Field
                        CompletionSymbolKind.IDENTIFIER -> com.intellij.icons.AllIcons.Nodes.Variable
                        CompletionSymbolKind.KEYWORD -> com.intellij.icons.AllIcons.Nodes.Static
                    },
                kind = kind,
                typeText =
                    when (kind) {
                        CompletionSymbolKind.TYPE -> "type"
                        CompletionSymbolKind.FUNCTION -> "fn"
                        CompletionSymbolKind.FIELD -> "field"
                        CompletionSymbolKind.IDENTIFIER -> "var"
                        CompletionSymbolKind.KEYWORD -> "keyword"
                    },
                tailText = " from $modulePath",
                insertionFamily = autoImportedExportInsertionFamily(modulePath, symbolName),
                rankingCategory = AikenOrdinaryCompletionCategory.UNIMPORTED_SYMBOL
            )
        )
    }

    private fun createAutoImportedQualifiedLookup(
        modulePath: String,
        moduleText: String,
        symbolName: String,
        kind: CompletionSymbolKind,
        allowBareTypes: Boolean
    ): LookupElement? {
        if (kind == CompletionSymbolKind.TYPE) {
            val constructible =
                AikenConstructibleCompletionSupport.findConstructibleInModuleText(modulePath, moduleText, symbolName)
            if (constructible != null) {
                return createAutoImportedLookup(
                    AutoImportedLookupSpec(
                        text = symbolName,
                        icon = com.intellij.icons.AllIcons.Nodes.Class,
                        kind = CompletionSymbolKind.TYPE,
                        typeText = "type",
                        tailText = " from $modulePath",
                        insertionFamily =
                            autoImportedQualifiedMemberInsertionFamily(
                                modulePath = modulePath,
                                symbolName = symbolName,
                                canonicalQualifier = modulePath.substringAfterLast('/'),
                                triggerMemberAutoPopup = true
                            ),
                        rankingCategory = AikenOrdinaryCompletionCategory.UNIMPORTED_SYMBOL
                    )
                )
            }
            if (!allowBareTypes) return null
        }

        return createAutoImportedLookup(
            AutoImportedLookupSpec(
                text = symbolName,
                icon =
                    when (kind) {
                        CompletionSymbolKind.TYPE -> com.intellij.icons.AllIcons.Nodes.Class
                        CompletionSymbolKind.FUNCTION -> com.intellij.icons.AllIcons.Nodes.Method
                        CompletionSymbolKind.FIELD -> com.intellij.icons.AllIcons.Nodes.Field
                        CompletionSymbolKind.IDENTIFIER -> com.intellij.icons.AllIcons.Nodes.Variable
                        CompletionSymbolKind.KEYWORD -> com.intellij.icons.AllIcons.Nodes.Static
                    },
                kind = kind,
                typeText =
                    when (kind) {
                        CompletionSymbolKind.TYPE -> "type"
                        CompletionSymbolKind.FUNCTION -> "fn"
                        CompletionSymbolKind.FIELD -> "field"
                        CompletionSymbolKind.IDENTIFIER -> "var"
                        CompletionSymbolKind.KEYWORD -> "keyword"
                    },
                tailText = " from $modulePath",
                insertionFamily =
                    autoImportedQualifiedMemberInsertionFamily(
                        modulePath = modulePath,
                        symbolName = symbolName,
                        canonicalQualifier = modulePath.substringAfterLast('/')
                    ),
                rankingCategory = AikenOrdinaryCompletionCategory.UNIMPORTED_SYMBOL
            )
        )
    }

    private fun createAutoImportedModuleLookup(
        modulePath: String,
        exposedModuleName: String
    ): LookupElement =
        createAutoImportedLookup(
            AutoImportedLookupSpec(
                text = exposedModuleName,
                icon = com.intellij.icons.AllIcons.Nodes.Package,
                kind = CompletionSymbolKind.IDENTIFIER,
                typeText = "module",
                tailText = " from $modulePath",
                insertionFamily = autoImportedModuleInsertionFamily(modulePath, exposedModuleName),
                rankingCategory = AikenOrdinaryCompletionCategory.UNIMPORTED_MODULE
            )
        )

    private fun unimportedQualifiedVariants(
        element: PsiElement,
        qualifier: String,
        allowBareTypes: Boolean,
        excludedModulePaths: Set<String> = emptySet()
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return emptyList()
        val result = ArrayList<LookupElement>()
        val seen = LinkedHashSet<String>()

        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath.isBlank() || modulePath == currentModulePath) continue
            if (modulePath in excludedModulePaths) continue
            if (qualifier !in moduleQualifierForms(modulePath)) continue

            val text = moduleFile.contentsToByteArray().toString(Charsets.UTF_8)
            val exportedNames = AikenPublicExportExtractor.extract(text).toSet()
            for (entry in AikenTopLevelSymbolExtractor.extract(text)) {
                if (entry.name !in exportedNames) continue
                if (!seen.add("$modulePath::${entry.name}")) continue
                createAutoImportedQualifiedLookup(
                    modulePath = modulePath,
                    moduleText = text,
                    symbolName = entry.name,
                    kind = mapTopLevelKind(entry),
                    allowBareTypes = allowBareTypes
                )?.let(result::add)
            }
        }

        return result
    }

    private fun importedModulePathsForQualifierChain(
        useModel: com.medusalabs.aiken.imports.AikenUseModel,
        qualifierChain: String
    ): Set<String> {
        val normalized = qualifierChain.trim()
        if (normalized.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (statement in useModel.statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isBlank()) continue
            if (normalized in moduleQualifierForms(modulePath)) {
                result += modulePath
            }
        }
        return result
    }

    private fun canonicalQualifierForImportedModule(
        useModel: com.medusalabs.aiken.imports.AikenUseModel,
        modulePath: String,
        qualifierChain: String,
        fallbackQualifier: String
    ): String {
        val qualifiers =
            useModel.statements
                .asSequence()
                .filter { statement -> statement.modulePath.trim() == modulePath }
                .mapNotNull { statement ->
                    statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: modulePath.substringAfterLast('/').trim().takeIf { it.isNotEmpty() }
                }
                .distinct()
                .toList()
        if (qualifiers.isEmpty()) return modulePath.substringAfterLast('/')
        val typedLeaf =
            qualifierChain.substringAfterLast('.').trim().ifEmpty { fallbackQualifier.trim() }
        return qualifiers.firstOrNull { it == typedLeaf } ?: qualifiers.first()
    }

    private fun qualifiedModuleContinuations(
        element: PsiElement,
        useModel: com.medusalabs.aiken.imports.AikenUseModel,
        qualifierChain: String,
        prefix: String
    ): List<LookupElement> {
        val file = element.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val result = ArrayList<LookupElement>()
        val seen = LinkedHashSet<Pair<String, String>>()
        val importedModulePaths =
            useModel.statements
                .mapNotNull { statement ->
                    statement.modulePath.trim().takeIf { it.isNotBlank() }
                }
                .toSet()

        fun addContinuation(
            modulePath: String,
            imported: Boolean
        ) {
            val nextSegment = nextQualifierSegment(modulePath, qualifierChain) ?: return
            if (prefix.isNotEmpty() && !nextSegment.startsWith(prefix, ignoreCase = true)) return
            val dedupeKey = modulePath to nextSegment
            if (!seen.add(dedupeKey)) return
            result +=
                createQualifiedModuleContinuationLookup(
                    modulePath = modulePath,
                    nextSegment = nextSegment,
                    imported = imported,
                    canonicalQualifier =
                        if (imported) {
                            canonicalQualifierForImportedModule(
                                useModel = useModel,
                                modulePath = modulePath,
                                qualifierChain = qualifierChain,
                                fallbackQualifier = nextSegment
                            )
                        } else {
                            modulePath.substringAfterLast('/')
                        }
                )
        }

        for (modulePath in importedModulePaths) {
            if (modulePath != currentModulePath) {
                addContinuation(modulePath, imported = true)
            }
        }

        val root = AikenProjectRoots.findRootForFile(file.virtualFile) ?: return result
        for (moduleFile in collectModuleFiles(root)) {
            val modulePath = AikenModulePath.fromFile(moduleFile) ?: continue
            if (modulePath.isBlank() || modulePath == currentModulePath || modulePath in importedModulePaths) continue
            addContinuation(modulePath, imported = false)
        }

        return result
    }

    private fun nextQualifierSegment(
        modulePath: String,
        qualifierChain: String
    ): String? {
        val chainParts = qualifierChain.split('.').map(String::trim).filter { it.isNotBlank() }
        if (chainParts.isEmpty()) return null
        val segments = modulePath.split('/').map(String::trim).filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        for (start in segments.indices) {
            val suffix = segments.subList(start, segments.size)
            if (chainParts.size >= suffix.size) continue
            if (suffix.take(chainParts.size) == chainParts) {
                return suffix[chainParts.size]
            }
        }

        return null
    }

    private fun createQualifiedModuleContinuationLookup(
        modulePath: String,
        nextSegment: String,
        imported: Boolean,
        canonicalQualifier: String
    ): LookupElement {
        val lookupIdentity = "qualified-module-continuation:$modulePath:$nextSegment:$imported"
        val builder =
            com.intellij.codeInsight.lookup.LookupElementBuilder
                .create(lookupIdentity, nextSegment)
                .withIcon(com.intellij.icons.AllIcons.Nodes.Package)
                .withTypeText("module", true)
                .withTailText(" from $modulePath", true)
                .withInsertHandler { insertionContext, _ ->
                    val replacementText = "$canonicalQualifier."
                    val insertedOffset = replaceCurrentQualifiedPrefix(insertionContext, replacementText)
                    if (imported) {
                        insertionContext.commitDocument()
                        insertionContext.editor.caretModel.moveToOffset(insertedOffset + replacementText.length)
                        AutoPopupController.getInstance(insertionContext.project)
                            .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
                    } else {
                        val insertedRangeMarker =
                            insertionContext.document.createRangeMarker(
                                insertedOffset,
                                insertedOffset + replacementText.length
                            ).apply {
                                isGreedyToLeft = false
                                isGreedyToRight = true
                            }
                        insertionContext.commitDocument()
                        val previousLaterRunnable = insertionContext.laterRunnable
                        insertionContext.setLaterRunnable {
                            try {
                                previousLaterRunnable?.run()
                                WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                                    insertStandaloneModuleUseImport(
                                        insertionContext.document.charsSequence.toString(),
                                        insertionContext.document,
                                        modulePath
                                    )
                                    insertionContext.commitDocument()
                                }
                                val caretOffset =
                                    if (insertedRangeMarker.isValid) {
                                        insertedRangeMarker.endOffset
                                    } else {
                                        insertionContext.editor.caretModel.offset
                                    }
                                insertionContext.editor.caretModel.moveToOffset(caretOffset)
                                AutoPopupController.getInstance(insertionContext.project)
                                    .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
                            } finally {
                                insertedRangeMarker.dispose()
                            }
                        }
                    }
                }

        return AikenCompletionSorting.annotate(
            builder,
            if (imported) AikenOrdinaryCompletionCategory.IMPORTED_MODULE else AikenOrdinaryCompletionCategory.UNIMPORTED_MODULE,
            CompletionSymbolKind.IDENTIFIER
        )
    }

    private fun moduleQualifierForms(modulePath: String): Set<String> {
        val segments = modulePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptySet()

        val result = LinkedHashSet<String>()
        for (start in segments.indices) {
            result += segments.subList(start, segments.size).joinToString(".")
        }
        return result
    }

    private fun createAutoImportedLookup(spec: AutoImportedLookupSpec): LookupElement {
        val lookupIdentity =
            when (val insertionFamily = spec.insertionFamily) {
                is AikenReferenceInsertionFamily.AutoImportedSymbol ->
                    "symbol:${insertionFamily.modulePath}:${insertionFamily.symbolName}"
                is AikenReferenceInsertionFamily.AutoImportedQualifiedMember ->
                    "qualified:${insertionFamily.modulePath}:${insertionFamily.symbolName}:${insertionFamily.triggerMemberAutoPopup}"
                is AikenReferenceInsertionFamily.AutoImportedModuleQualifier ->
                    "module:${insertionFamily.modulePath}:${insertionFamily.exposedModuleName}"
            }
        val builder =
            com.intellij.codeInsight.lookup.LookupElementBuilder
                .create(lookupIdentity, spec.text)
                .withIcon(spec.icon)
                .withTypeText(spec.typeText, true)
                .withTailText(spec.tailText, true)
                .withInsertHandler { insertionContext, _ ->
                    applyInsertionFamily(insertionContext, spec.insertionFamily)
                }
        return AikenCompletionSorting.annotate(
            builder,
            spec.rankingCategory,
            spec.kind
        )
    }

    private fun applyInsertionFamily(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        insertionFamily: AikenReferenceInsertionFamily
    ) {
        when (insertionFamily) {
            is AikenReferenceInsertionFamily.AutoImportedSymbol -> {
                replaceCurrentIdentifierPrefix(insertionContext, insertionFamily.symbolName)
                insertionContext.commitDocument()
                val previousLaterRunnable = insertionContext.laterRunnable
                insertionContext.setLaterRunnable {
                    previousLaterRunnable?.run()
                    WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                        insertStandaloneUseImport(
                            insertionContext.document,
                            insertionFamily.modulePath,
                            insertionFamily.symbolName
                        )
                        insertionContext.commitDocument()
                    }
                }
            }
            is AikenReferenceInsertionFamily.AutoImportedQualifiedMember -> {
                val insertedOffset =
                    replaceCurrentQualifiedPrefix(
                        insertionContext,
                        "${insertionFamily.canonicalQualifier}.${insertionFamily.symbolName}"
                    )
                val insertedRangeMarker =
                    insertionContext.document.createRangeMarker(
                        insertedOffset,
                        insertedOffset + insertionFamily.canonicalQualifier.length + insertionFamily.symbolName.length + 1
                    ).apply {
                        isGreedyToLeft = false
                        isGreedyToRight = true
                    }
                insertionContext.commitDocument()
                val previousLaterRunnable = insertionContext.laterRunnable
                insertionContext.setLaterRunnable {
                    try {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneModuleUseImport(
                                insertionContext.document.charsSequence.toString(),
                                insertionContext.document,
                                insertionFamily.modulePath
                            )
                            insertionContext.commitDocument()
                        }
                        val caretOffset =
                            if (insertedRangeMarker.isValid) {
                                insertedRangeMarker.endOffset
                            } else {
                                insertionContext.editor.caretModel.offset
                            }
                        insertionContext.editor.caretModel.moveToOffset(caretOffset)
                        if (insertionFamily.triggerMemberAutoPopup) {
                            AutoPopupController.getInstance(insertionContext.project)
                                .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
                        }
                    } finally {
                        insertedRangeMarker.dispose()
                    }
                }
            }
            is AikenReferenceInsertionFamily.AutoImportedModuleQualifier -> {
                val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, "${insertionFamily.exposedModuleName}.")
                val insertedRangeMarker =
                    insertionContext.document.createRangeMarker(
                        insertedOffset,
                        insertedOffset + insertionFamily.exposedModuleName.length + 1
                    ).apply {
                        isGreedyToLeft = false
                        isGreedyToRight = true
                    }
                insertionContext.commitDocument()
                val previousLaterRunnable = insertionContext.laterRunnable
                insertionContext.setLaterRunnable {
                    try {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneModuleUseImport(
                                insertionContext.document.charsSequence.toString(),
                                insertionContext.document,
                                insertionFamily.modulePath
                            )
                            insertionContext.commitDocument()
                        }
                        val caretOffset =
                            if (insertedRangeMarker.isValid) {
                                insertedRangeMarker.endOffset
                            } else {
                                insertionContext.editor.caretModel.offset
                            }
                        insertionContext.editor.caretModel.moveToOffset(caretOffset)
                        AutoPopupController.getInstance(insertionContext.project)
                            .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
                    } finally {
                        insertedRangeMarker.dispose()
                    }
                }
            }
        }
    }

    private fun replaceCurrentIdentifierPrefix(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        replacementText: String
    ): Int {
        val document = insertionContext.document
        val chars = document.charsSequence
        var replaceStart = insertionContext.startOffset.coerceIn(0, chars.length)
        while (replaceStart > 0 && (chars[replaceStart - 1].isLetterOrDigit() || chars[replaceStart - 1] == '_')) {
            replaceStart--
        }
        document.replaceString(replaceStart, insertionContext.tailOffset, replacementText)
        return replaceStart
    }

    private fun replaceCurrentQualifiedPrefix(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        replacementText: String
    ): Int {
        val document = insertionContext.document
        val chars = document.charsSequence
        var replaceStart = insertionContext.startOffset.coerceIn(0, chars.length)
        while (replaceStart > 0 && (AikenSyntaxText.isIdentifierChar(chars[replaceStart - 1]) || chars[replaceStart - 1] == '.')) {
            replaceStart--
        }
        document.replaceString(replaceStart, insertionContext.tailOffset, replacementText)
        return replaceStart
    }

    private fun insertStandaloneUseImport(
        document: com.intellij.openapi.editor.Document,
        modulePath: String,
        symbolName: String
    ) {
        document.insertString(0, "use $modulePath.{$symbolName}\n")
    }

    private fun insertStandaloneModuleUseImport(
        currentText: String,
        document: com.intellij.openapi.editor.Document,
        modulePath: String
    ) {
        val alreadyImported =
            AikenUseStatementParser.parseModel(currentText)
                .statements
                .any { statement ->
                    statement.modulePath.trim() == modulePath &&
                        statement.items.isEmpty() &&
                        statement.moduleAlias.isNullOrBlank()
                }
        if (alreadyImported) return
        document.insertString(0, "use $modulePath\n")
    }
}
