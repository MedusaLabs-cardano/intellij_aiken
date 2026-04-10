package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.index.AIKEN_EXPORT_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.aikenTopLevelSymbolModuleKey
import com.medusalabs.aiken.index.decodeAikenExportIndexValue
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes

class AikenUseCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val file = parameters.originalFile
        if (file.fileType != AikenFileType) return

        val text = file.text
        val offset = parameters.offset.coerceIn(0, text.length)
        val useContext = AikenUseCompletionContext.detect(text, offset) ?: return

        val catalog = AikenImportCatalog.get(file)
        var added = 0

        when (useContext.mode) {
            AikenUseCompletionMode.MODULE -> {
                val prefix = normalizeModulePrefix(useContext.currentPrefix(text))
                val normalizedPrefix = prefix.trim().trimEnd('.')
                val moduleFromContextRaw = useContext.modulePath.orEmpty().trim()
                val moduleFromContext = moduleFromContextRaw.trimEnd('.')
                val moduleFromLine =
                    UseEditUtils.moduleSegmentRange(text, useContext)
                        ?.let { text.substring(it.startOffset, it.endOffset).trim().trimEnd('.') }
                        ?.takeIf { it.isNotBlank() }
                val looksLikeAfterDot =
                    moduleFromContextRaw.endsWith('.') ||
                        prefix.trimEnd().endsWith('.') ||
                        (
                            useContext.replaceRange.startOffset > 0 &&
                                text.getOrNull(useContext.replaceRange.startOffset - 1) == '.'
                            )

                val moduleMatcher = result.prefixMatcher.cloneWithPrefix(prefix)
                val reverseExportMatcher = result.prefixMatcher.cloneWithPrefix(normalizedPrefix)
                val moduleSuggestions =
                    catalog.moduleNames
                        .asSequence()
                        .filter { AikenCompletionPrefixMatching.matches(it, moduleMatcher, prefix) }
                        .take(MAX_SUGGESTIONS)
                        .toList()
                val reverseExportSuggestions =
                    if (normalizedPrefix.isBlank()) {
                        emptyList()
                    } else {
                        catalog.modulesExporting { AikenCompletionPrefixMatching.matches(it, reverseExportMatcher, normalizedPrefix) }
                            .asSequence()
                            .filter { it.module !in moduleSuggestions }
                            .take(MAX_SUGGESTIONS)
                            .toList()
                    }

                val exactSingleModuleMatch =
                    moduleSuggestions.size == 1 && moduleSuggestions.first() == normalizedPrefix
                val moduleForPub =
                    when {
                        normalizedPrefix.isNotBlank() && catalog.containsModule(normalizedPrefix) -> normalizedPrefix
                        moduleFromLine != null && catalog.containsModule(moduleFromLine) -> moduleFromLine
                        moduleFromContext.isNotBlank() && catalog.containsModule(moduleFromContext) -> moduleFromContext
                        exactSingleModuleMatch -> moduleSuggestions.first()
                        else -> ""
                    }
                val exactModuleMatch = normalizedPrefix.isNotBlank() && moduleSuggestions.any { it == normalizedPrefix }
                val preferPub = moduleForPub.isNotBlank() && (exactModuleMatch || exactSingleModuleMatch || looksLikeAfterDot)

                if (moduleForPub.isNotBlank()) {
                    val pubResult = AikenCompletionSorting.withUseSorter(parameters, result.withPrefixMatcher(""))
                    val pubSuggestions =
                        catalog.publicEntities(moduleForPub)
                            .asSequence()
                            .take(MAX_SUGGESTIONS)
                            .toList()

                    for (entity in pubSuggestions) {
                        val icon =
                            if (entity.firstOrNull()?.isUpperCase() == true) AllIcons.Nodes.Class
                            else AllIcons.Nodes.Method

                        val builder =
                            LookupElementBuilder.create(entity)
                                .withIcon(icon)
                                .withTypeText("$moduleForPub (pub)", true)
                                .withInsertHandler { insertionContext, _ ->
                                    // Remove default completion text first, then apply our deterministic rewrite.
                                    val insertedStart = insertionContext.startOffset
                                    val insertedEnd = insertionContext.tailOffset
                                    if (insertedEnd > insertedStart) {
                                        insertionContext.document.deleteString(insertedStart, insertedEnd)
                                    }
                                    val op = UseEditUtils.buildPubInsertionOperation(
                                        text = insertionContext.document.text,
                                        context = useContext,
                                        fallbackModule = moduleForPub,
                                        entity = entity
                                    )
                                    insertionContext.document.replaceString(
                                        op.replaceRange.startOffset,
                                        op.replaceRange.endOffset,
                                        op.replacement
                                    )
                                    insertionContext.commitDocument()
                                }
                        pubResult.addElement(
                            annotateUseLookup(
                                builder,
                                AikenUseCompletionCategory.PUB_ENTITY
                            )
                        )
                        added++
                    }
                }

                if (!preferPub) {
                    val moduleResult = AikenCompletionSorting.withUseSorter(parameters, result.withPrefixMatcher(""))
                    val reverseResult = AikenCompletionSorting.withUseSorter(parameters, result.withPrefixMatcher(""))

                    for (module in moduleSuggestions) {
                        moduleResult.addElement(
                            annotateUseLookup(
                                LookupElementBuilder.create(module)
                                    .withIcon(AllIcons.Nodes.Package)
                                    .withTypeText("module", true)
                                    .withInsertHandler { insertionContext, _ ->
                                        val docText = insertionContext.document.text
                                        val range = UseEditUtils.moduleSegmentRange(docText, useContext) ?: useContext.replaceRange
                                        insertionContext.document.replaceString(range.startOffset, range.endOffset, module)
                                        insertionContext.commitDocument()
                                    },
                                AikenUseCompletionCategory.MODULE
                            )
                        )
                        added++
                    }

                    for (match in reverseExportSuggestions) {
                        val fullImportSuggestion = "${match.module}.{${match.matchedExport}}"
                        val builder =
                            LookupElementBuilder.create(fullImportSuggestion)
                                .withIcon(AllIcons.Nodes.Package)
                                .withTypeText("${match.kind.label} ${match.matchedExport}", true)
                                .withInsertHandler { insertionContext, _ ->
                                    val insertedStart = insertionContext.startOffset
                                    val insertedEnd = insertionContext.tailOffset
                                    if (insertedEnd > insertedStart) {
                                        insertionContext.document.deleteString(insertedStart, insertedEnd)
                                    }
                                    val docText = insertionContext.document.text
                                    val range = UseEditUtils.moduleSegmentRange(docText, useContext) ?: useContext.replaceRange
                                    insertionContext.document.replaceString(range.startOffset, range.endOffset, fullImportSuggestion)
                                    insertionContext.commitDocument()
                                }
                        reverseResult.addElement(
                            annotateUseLookup(
                                builder,
                                if (match.matchedExport == normalizedPrefix) {
                                    AikenUseCompletionCategory.REVERSE_EXACT
                                } else {
                                    AikenUseCompletionCategory.REVERSE_FUZZY
                                }
                            )
                        )
                        added++
                    }
                }
            }

            AikenUseCompletionMode.ENTITY -> {
                val module = useContext.modulePath.orEmpty().trim().trimEnd('.')
                if (module.isBlank()) return

                val prefix = normalizeEntityPrefix(useContext.currentPrefix(text))
                val alreadyImported = useContext.existingItems
                val entityResult = AikenCompletionSorting.withUseSorter(parameters, result.withPrefixMatcher(""))
                val entityMatcher = result.prefixMatcher.cloneWithPrefix(prefix)

                val pubSuggestions =
                    catalog.publicEntities(module)
                        .asSequence()
                        .filter { AikenCompletionPrefixMatching.matches(it, entityMatcher, prefix) && !alreadyImported.contains(it) }
                        .take(MAX_SUGGESTIONS)
                        .toList()

                for (entity in pubSuggestions) {
                    val icon =
                        if (entity.firstOrNull()?.isUpperCase() == true) AllIcons.Nodes.Class
                        else AllIcons.Nodes.Method

                    entityResult.addElement(
                        annotateUseLookup(
                            LookupElementBuilder.create(entity)
                                .withIcon(icon)
                                .withTypeText(module, true)
                                .withInsertHandler { insertionContext, _ ->
                                    // Remove default completion text first, then apply our deterministic rewrite.
                                    val insertedStart = insertionContext.startOffset
                                    val insertedEnd = insertionContext.tailOffset
                                    if (insertedEnd > insertedStart) {
                                        insertionContext.document.deleteString(insertedStart, insertedEnd)
                                    }
                                    val op = UseEditUtils.buildPubInsertionOperation(
                                        text = insertionContext.document.text,
                                        context = useContext,
                                        fallbackModule = module,
                                        entity = entity
                                    )
                                    insertionContext.document.replaceString(
                                        op.replaceRange.startOffset,
                                        op.replaceRange.endOffset,
                                        op.replacement
                                    )
                                    insertionContext.commitDocument()
                                },
                            AikenUseCompletionCategory.PUB_ENTITY
                        )
                    )
                    added++
                }
            }
        }

        if (added > 0 || useContext.mode == AikenUseCompletionMode.MODULE || useContext.mode == AikenUseCompletionMode.ENTITY) {
            result.stopHere()
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 600
    }
}

private fun annotateUseLookup(
    lookup: LookupElement,
    category: AikenUseCompletionCategory
): LookupElement = AikenCompletionSorting.annotateUse(lookup, category)

private fun normalizeEntityPrefix(raw: String): String {
    if (raw.isBlank()) return ""
    val withoutDummy =
        raw.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
    if (withoutDummy.isBlank()) return ""
    val trimmedRight = withoutDummy.trimEnd()
    if (trimmedRight.isEmpty()) return ""

    // Entity lists are comma-separated; treat commas/spaces/braces as hard token separators.
    if (trimmedRight.last() == ',' || trimmedRight.last() == '{' || trimmedRight.last() == '}') {
        return ""
    }

    val lastSeparator =
        trimmedRight.indexOfLast { it == ',' || it == '{' || it == '}' || it.isWhitespace() }
    val token = if (lastSeparator >= 0) trimmedRight.substring(lastSeparator + 1) else trimmedRight
    return token.takeWhile { isIdentifierCharStatic(it) }
}

private fun normalizeModulePrefix(raw: String): String {
    if (raw.isBlank()) return ""
    val withoutDummy =
        raw.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
    if (withoutDummy.isBlank()) return ""
    val trimmedRight = withoutDummy.trimEnd()
    if (trimmedRight.isEmpty()) return ""
    if (trimmedRight.last() == ',' || trimmedRight.last() == '{' || trimmedRight.last() == '}') {
        return ""
    }

    val lastSeparator =
        trimmedRight.indexOfLast { it == ',' || it == '{' || it == '}' || it.isWhitespace() }
    val token = if (lastSeparator >= 0) trimmedRight.substring(lastSeparator + 1) else trimmedRight
    return token.takeWhile { isModulePathCharStatic(it) }
}

private fun isIdentifierCharStatic(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

private fun isModulePathCharStatic(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '/' || ch == '.'

private data class UseReplacementOperation(
    val replaceRange: TextRange,
    val replacement: String
)

private object UseEditUtils {
    fun moduleSegmentRange(text: String, context: AikenUseCompletionContext): TextRange? {
        if (context.mode != AikenUseCompletionMode.MODULE) return null
        val line = useStatementLineBounds(text, context.statementRange.startOffset) ?: return null
        val lineText = text.substring(line.startOffset, line.endOffset)

        val useLocal = lineText.indexOf("use").takeIf { it >= 0 } ?: return null
        var moduleStart = line.startOffset + useLocal + 3
        while (moduleStart < line.endOffset && text[moduleStart].isWhitespace()) moduleStart++

        val dotBraceLocal = lineText.indexOf(".{").takeIf { it >= 0 }
        var moduleEnd = if (dotBraceLocal != null) line.startOffset + dotBraceLocal else line.endOffset
        while (moduleEnd > moduleStart && text[moduleEnd - 1].isWhitespace()) moduleEnd--

        return if (moduleEnd >= moduleStart) TextRange(moduleStart, moduleEnd) else null
    }

    fun buildPubInsertionOperation(
        text: String,
        context: AikenUseCompletionContext,
        fallbackModule: String,
        entity: String
    ): UseReplacementOperation {
        val line = useStatementLineBounds(text, context.statementRange.startOffset)
            ?: return UseReplacementOperation(context.statementRange, "use $fallbackModule.{$entity}")

        val moduleRange = moduleSegmentRange(text, context)
        val normalizedModule =
            moduleRange
                ?.let { text.substring(it.startOffset, it.endOffset).trim().trimEnd('.') }
                ?.takeIf { it.isNotBlank() }
                ?: fallbackModule

        val lineText = text.substring(line.startOffset, line.endOffset)
        val dotBraceLocal = lineText.indexOf(".{")
        if (dotBraceLocal < 0) {
            return UseReplacementOperation(line, "use $normalizedModule.{$entity}")
        }

        val openBrace = line.startOffset + dotBraceLocal + 1
        val closeBrace =
            text.indexOf('}', startIndex = openBrace + 1)
                .takeIf { it in (openBrace + 1)..line.endOffset }
                ?: return UseReplacementOperation(line, "use $normalizedModule.{$entity}")

        val itemsRange = TextRange(openBrace + 1, closeBrace)
        val existing =
            text.substring(itemsRange.startOffset, itemsRange.endOffset)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableList()

        val existingNames =
            existing.map { it.substringBefore(" as ").trim() }
                .filter { it.isNotBlank() }
                .toSet()

        if (!existingNames.contains(entity)) {
            existing += entity
        }

        return UseReplacementOperation(itemsRange, existing.joinToString(", "))
    }

    private fun useStatementLineBounds(text: String, anchorOffset: Int): TextRange? {
        if (text.isEmpty()) return null
        val anchor = anchorOffset.coerceIn(0, text.length - 1)
        val lineStart = text.lastIndexOf('\n', anchor).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
        if (lineStart >= lineEnd) return null
        return TextRange(lineStart, lineEnd)
    }
}

private data class AikenImportCatalog(
    val moduleNames: List<String>,
    private val entitiesByModule: Map<String, List<String>>,
    private val moduleScope: com.intellij.psi.search.GlobalSearchScope? = null
) {
    enum class ExportKind(val label: String) {
        TYPE("type"),
        FUNCTION("fn"),
        CONST("const")
    }

    data class ReverseExportMatch(
        val module: String,
        val matchedExport: String,
        val kind: ExportKind
    )

    fun publicEntities(module: String): List<String> = entitiesByModule[module].orEmpty()

    fun containsModule(module: String): Boolean = entitiesByModule.containsKey(module)

    fun modulesExporting(nameMatches: (String) -> Boolean): List<ReverseExportMatch> =
        entitiesByModule
            .asSequence()
            .mapNotNull { (module, exports) ->
                val match =
                    exports.firstOrNull(nameMatches)
                        ?: return@mapNotNull null
                val kind = exportKind(module, match) ?: return@mapNotNull null
                ReverseExportMatch(module = module, matchedExport = match, kind = kind)
            }
            .sortedBy { it.module }
            .toList()

    private fun exportKind(module: String, symbolName: String): ExportKind? {
        val lookupKinds =
            listOf(
                AikenTopLevelSymbolKind.TYPE to ExportKind.TYPE,
                AikenTopLevelSymbolKind.FUNCTION to ExportKind.FUNCTION,
                AikenTopLevelSymbolKind.CONST to ExportKind.CONST
            )
        val index = FileBasedIndex.getInstance()
        val scope = moduleScope ?: return null

        for ((symbolKind, exportKind) in lookupKinds) {
            val values = index.getValues(AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME, aikenTopLevelSymbolModuleKey(symbolKind, module, symbolName), scope)
            if (values.isNotEmpty()) return exportKind
        }
        return null
    }

    companion object {
        fun get(anchorFile: PsiFile): AikenImportCatalog {
            val manager = com.intellij.psi.util.CachedValuesManager.getManager(anchorFile.project)
            return manager.getCachedValue(anchorFile) {
                com.intellij.psi.util.CachedValueProvider.Result.create(
                    build(anchorFile),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
                )
            }
        }

        private fun build(anchorFile: PsiFile): AikenImportCatalog {
            val project = anchorFile.project
            if (DumbService.isDumb(project)) return AikenImportCatalog(emptyList(), emptyMap())

            val scope = AikenSearchScopes.forFile(project, anchorFile.virtualFile)
            val index = FileBasedIndex.getInstance()

            return try {
                val moduleNames =
                    FileTypeIndex.getFiles(AikenFileType, scope)
                        .asSequence()
                        .mapNotNull(AikenModulePath::fromFile)
                        .toCollection(LinkedHashSet())
                val entities = LinkedHashMap<String, List<String>>(moduleNames.size)
                for (module in moduleNames.sorted()) {
                    val names = LinkedHashSet<String>()
                    for (value in index.getValues(AIKEN_EXPORT_INDEX_NAME, module, scope)) {
                        names += decodeAikenExportIndexValue(value)
                    }
                    entities[module] = names.sorted()
                }

                AikenImportCatalog(
                    moduleNames = entities.keys.toList(),
                    entitiesByModule = entities,
                    moduleScope = scope
                )
            } catch (_: IndexNotReadyException) {
                AikenImportCatalog(emptyList(), emptyMap(), null)
            }
        }
    }
}
