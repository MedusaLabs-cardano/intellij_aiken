package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ProcessingContext
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.lang.AikenFileType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

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
        val useContext = UseContext.detect(text, offset) ?: return

        val catalog = AikenImportCatalog.get(file.project)
        var added = 0

        when (useContext.mode) {
            UseMode.MODULE -> {
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

                val moduleSuggestions =
                    catalog.moduleNames
                        .asSequence()
                        .filter { it.startsWith(prefix) }
                        .take(MAX_SUGGESTIONS)
                        .toList()

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
                    val pubResult = result.withPrefixMatcher("")
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
                        pubResult.addElement(PrioritizedLookupElement.withPriority(builder, 3000.0))
                        added++
                    }
                }

                if (!preferPub) {
                    for (module in moduleSuggestions) {
                        result.addElement(
                            LookupElementBuilder.create(module)
                                .withIcon(AllIcons.Nodes.Package)
                                .withTypeText("module", true)
                                .withInsertHandler { insertionContext, _ ->
                                    val docText = insertionContext.document.text
                                    val range = UseEditUtils.moduleSegmentRange(docText, useContext) ?: useContext.replaceRange
                                    insertionContext.document.replaceString(range.startOffset, range.endOffset, module)
                                    insertionContext.commitDocument()
                                }
                        )
                        added++
                    }
                }
            }

            UseMode.ENTITY -> {
                val module = useContext.modulePath.orEmpty().trim().trimEnd('.')
                if (module.isBlank()) return

                val prefix = normalizeEntityPrefix(useContext.currentPrefix(text))
                val alreadyImported = useContext.existingItems
                val entityResult = result.withPrefixMatcher("")

                val pubSuggestions =
                    catalog.publicEntities(module)
                        .asSequence()
                        .filter { it.startsWith(prefix) && !alreadyImported.contains(it) }
                        .take(MAX_SUGGESTIONS)
                        .toList()

                for (entity in pubSuggestions) {
                    val icon =
                        if (entity.firstOrNull()?.isUpperCase() == true) AllIcons.Nodes.Class
                        else AllIcons.Nodes.Method

                    entityResult.addElement(
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
                            }
                    )
                    added++
                }
            }
        }

        if (added > 0 || useContext.mode == UseMode.MODULE || useContext.mode == UseMode.ENTITY) {
            result.stopHere()
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 600
    }
}

private enum class UseMode {
    MODULE,
    ENTITY
}

private data class UseContext(
    val mode: UseMode,
    val statementRange: TextRange,
    val replaceRange: TextRange,
    val modulePath: String?,
    val existingItems: Set<String>
) {
    fun currentPrefix(text: String): String {
        val start = replaceRange.startOffset.coerceIn(0, text.length)
        val end = replaceRange.endOffset.coerceIn(start, text.length)
        return text.substring(start, end)
    }

    companion object {
        fun detect(text: String, offset: Int): UseContext? {
            val lineContext = detectUseLineContext(text, offset)
            if (lineContext?.mode == UseMode.ENTITY) {
                return lineContext
            }

            val parsedStatement =
                AikenUseStatementParser.parse(text).firstOrNull { stmt ->
                    offset >= stmt.statementRange.startOffset &&
                        offset <= (stmt.statementRange.endOffset + 1).coerceAtMost(text.length)
                }

            if (parsedStatement == null) return lineContext

            val inImportList = isInsideImportList(parsedStatement, text, offset)

            return if (inImportList) {
                UseContext(
                    mode = UseMode.ENTITY,
                    statementRange = parsedStatement.statementRange,
                    replaceRange = identifierRange(text, offset, parsedStatement.statementRange),
                    modulePath = parsedStatement.modulePath,
                    existingItems = parsedStatement.items.mapTo(LinkedHashSet()) { it.name }
                )
            } else {
                val bounds =
                    parsedStatement.modulePathRange
                        ?: TextRange(
                            parsedStatement.statementRange.startOffset,
                            parsedStatement.statementRange.endOffset.coerceAtLeast(offset)
                        )

                UseContext(
                    mode = UseMode.MODULE,
                    statementRange = parsedStatement.statementRange,
                    replaceRange = modulePathRange(text, offset, bounds),
                    modulePath = parsedStatement.modulePath,
                    existingItems = emptySet()
                )
            }
        }

        private fun detectUseLineContext(text: String, offset: Int): UseContext? {
            if (text.isEmpty()) return null
            val anchor = offset.coerceIn(0, text.length)
            val lineStart = text.lastIndexOf('\n', (anchor - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', anchor).let { if (it == -1) text.length else it }
            if (lineStart >= lineEnd) return null

            val line = text.substring(lineStart, lineEnd)
            val trimmedStart = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }
            if (!line.regionMatches(trimmedStart, "use", 0, 3)) return null

            val afterUse = lineStart + trimmedStart + 3
            if (anchor < afterUse) return null

            val statementRange = TextRange(lineStart + trimmedStart, lineEnd)
            val dotBraceInLine = line.indexOf(".{")
            val dotBraceGlobal = if (dotBraceInLine >= 0) lineStart + dotBraceInLine else -1

            if (dotBraceGlobal >= 0 && anchor >= dotBraceGlobal + 2) {
                val modulePath =
                    text.substring(afterUse, dotBraceGlobal)
                        .trim()
                        .trimEnd('.')
                        .takeIf { it.isNotBlank() }

                val itemBounds = TextRange((dotBraceGlobal + 2).coerceAtMost(lineEnd), lineEnd)

                return UseContext(
                    mode = UseMode.ENTITY,
                    statementRange = statementRange,
                    replaceRange = identifierRange(text, anchor, itemBounds),
                    modulePath = modulePath,
                    existingItems = extractExistingItemNames(text.substring(itemBounds.startOffset, itemBounds.endOffset))
                )
            }

            val moduleEnd = if (dotBraceGlobal >= 0) dotBraceGlobal else lineEnd
            val moduleBounds = TextRange(afterUse.coerceAtMost(moduleEnd), moduleEnd)
            val modulePath =
                if (moduleBounds.startOffset < moduleBounds.endOffset) {
                    text.substring(moduleBounds.startOffset, moduleBounds.endOffset).trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }

            return UseContext(
                mode = UseMode.MODULE,
                statementRange = statementRange,
                replaceRange = modulePathRange(text, anchor, moduleBounds),
                modulePath = modulePath,
                existingItems = emptySet()
            )
        }

        private fun isInsideImportList(
            statement: AikenUseStatementParser.UseStatement,
            text: String,
            offset: Int
        ): Boolean {
            val start = statement.statementRange.startOffset.coerceIn(0, text.length)
            val end = offset.coerceIn(start, text.length)
            var depth = 0
            for (i in start until end) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> depth = (depth - 1).coerceAtLeast(0)
                }
            }
            return depth > 0
        }

        private fun identifierRange(text: String, offset: Int, bounds: TextRange): TextRange {
            val startBound = bounds.startOffset.coerceAtLeast(0)
            val endBound = bounds.endOffset.coerceAtMost(text.length).coerceAtLeast(startBound)
            val caret = offset.coerceIn(startBound, endBound)

            // When caret is at delimiters (`,` / `{` / `}`) we should start a fresh token,
            // not reuse the previous identifier as a prefix.
            if (caret in startBound until endBound) {
                val ch = text[caret]
                if (ch == ',' || ch == '{' || ch == '}' || ch.isWhitespace()) {
                    return TextRange(caret, caret)
                }
            }
            if (caret > startBound) {
                val prev = text[caret - 1]
                if (prev == ',' || prev == '{' || prev == '}') {
                    return TextRange(caret, caret)
                }
            }

            var start = caret
            while (start > startBound && isIdentifierChar(text[start - 1])) start--
            var end = caret
            while (end < endBound && isIdentifierChar(text[end])) end++
            return TextRange(start, end)
        }

        private fun modulePathRange(text: String, offset: Int, bounds: TextRange): TextRange {
            val startBound = bounds.startOffset.coerceAtLeast(0)
            val endBound = bounds.endOffset.coerceAtMost(text.length).coerceAtLeast(startBound)
            var start = offset.coerceIn(startBound, endBound)
            while (start > startBound && isModulePathChar(text[start - 1])) start--
            var end = offset.coerceIn(startBound, endBound)
            while (end < endBound && isModulePathChar(text[end])) end++
            return TextRange(start, end)
        }

        private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

        private fun isModulePathChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '/'

        private fun extractExistingItemNames(rawItems: String): Set<String> {
            if (rawItems.isBlank()) return emptySet()
            val names = LinkedHashSet<String>()
            for (segment in rawItems.split(',')) {
                val trimmed = segment.trim()
                if (trimmed.isBlank()) continue
                val name = trimmed.substringBefore(" as ").trim()
                if (name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '_' }) {
                    names += name
                }
            }
            return names
        }
    }
}

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
    fun moduleSegmentRange(text: String, context: UseContext): TextRange? {
        if (context.mode != UseMode.MODULE) return null
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
        context: UseContext,
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
    private val entitiesByModule: Map<String, List<String>>
) {
    fun publicEntities(module: String): List<String> = entitiesByModule[module].orEmpty()

    fun containsModule(module: String): Boolean = entitiesByModule.containsKey(module)

    companion object {
        fun get(project: Project): AikenImportCatalog {
            val manager = com.intellij.psi.util.CachedValuesManager.getManager(project)
            return manager.getCachedValue(project) {
                com.intellij.psi.util.CachedValueProvider.Result.create(
                    build(project),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
                )
            }
        }

        private fun build(project: Project): AikenImportCatalog {
            val basePath = project.basePath?.takeIf { it.isNotBlank() }
                ?: return AikenImportCatalog(emptyList(), emptyMap())
            val root = Path(basePath)
            if (!Files.isDirectory(root)) return AikenImportCatalog(emptyList(), emptyMap())

            val moduleToFiles = LinkedHashMap<String, MutableList<Path>>()
            Files.walk(root).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.extension == "ak" }
                    .forEach { file ->
                        val relative = root.relativize(file).invariantSeparatorsPathString
                        val module = modulePathFromRelative(relative) ?: return@forEach
                        moduleToFiles.computeIfAbsent(module) { ArrayList() }.add(file)
                    }
            }

            val entities = LinkedHashMap<String, List<String>>(moduleToFiles.size)
            for ((module, files) in moduleToFiles) {
                val names = LinkedHashSet<String>()
                for (file in files) {
                    for (name in extractPublicSymbols(file)) {
                        names += name
                    }
                }
                entities[module] = names.sorted()
            }

            return AikenImportCatalog(
                moduleNames = moduleToFiles.keys.sorted(),
                entitiesByModule = entities
            )
        }

        private fun modulePathFromRelative(relativePath: String): String? {
            val rel = relativePath.replace('\\', '/')
            if (!rel.endsWith(".ak")) return null
            val withoutExt = rel.removeSuffix(".ak")

            fun tailAfter(root: String, source: String): String? =
                if (source.startsWith(root)) source.removePrefix(root).takeIf { it.isNotBlank() } else null

            val localModule =
                tailAfter("lib/", withoutExt)
                    ?: tailAfter("validators/", withoutExt)
            if (localModule != null) return localModule

            if (withoutExt.startsWith("build/packages/")) {
                val tail = withoutExt.removePrefix("build/packages/")
                val packageAndRest = tail.substringAfter('/', "")
                val depModule =
                    tailAfter("lib/", packageAndRest)
                        ?: tailAfter("validators/", packageAndRest)
                if (depModule != null) return depModule
            }

            return null
        }

        private val PUB_DECLARATION_REGEX =
            Regex("""(?m)^\s*pub\s+(?:opaque\s+)?(?:fn|const|type|validator)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")

        private fun extractPublicSymbols(file: Path): List<String> {
            val source =
                try {
                    file.readText()
                } catch (_: Throwable) {
                    return emptyList()
                }

            return PUB_DECLARATION_REGEX
                .findAll(source)
                .map { it.groupValues.getOrNull(1).orEmpty() }
                .filter { it.isNotBlank() }
                .toCollection(LinkedHashSet())
                .toList()
        }
    }
}
