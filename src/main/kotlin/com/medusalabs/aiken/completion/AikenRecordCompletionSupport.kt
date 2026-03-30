package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_CONSTRUCTIBLE_INDEX_NAME
import com.medusalabs.aiken.index.AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME
import com.medusalabs.aiken.index.AikenConstructibleEntry
import com.medusalabs.aiken.index.AikenConstructibleExtractor
import com.medusalabs.aiken.index.AikenConstructibleKind
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.aikenTopLevelSymbolNameKey
import com.medusalabs.aiken.index.decodeAikenConstructibleIndexValue
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes

object AikenRecordCompletionSupport {
    fun isRecordFieldValueContext(text: String, offset: Int): Boolean =
        RecordCompletionContext.detect(text, offset)?.mode == RecordCompletionMode.FIELD_VALUE

    fun recordSpecificVariants(anchor: PsiElement?, offset: Int): RecordCompletionSuggestions? {
        val file = anchor?.containingFile ?: return null
        val text = file.text
        val context = RecordCompletionContext.detect(text, offset) ?: return null
        val useModel = AikenUseStatementParser.parseModel(text)
        val visibleEntries = visibleConstructibles(anchor, useModel)
        val ownerEntries = resolveOwnerEntries(anchor, context.ownerExpression, visibleEntries, useModel)
        if (ownerEntries.isEmpty()) return null

        val lookups =
            when (context.mode) {
            RecordCompletionMode.FIELD_NAME -> fieldNameLookups(ownerEntries, context.existingFieldNames)
            RecordCompletionMode.FIELD_VALUE -> fieldValueLookups(anchor, ownerEntries, context.currentFieldName, context.currentValueText)
        }
        if (lookups.isEmpty()) return null
        return RecordCompletionSuggestions(context.mode, lookups)
    }

    private fun fieldNameLookups(
        ownerEntries: List<VisibleConstructibleEntry>,
        existingFieldNames: Set<String>
    ): List<LookupElement> {
        val seen = LinkedHashSet<String>()
        val lookups = ArrayList<LookupElement>()

        for (field in ownerEntries.asSequence().flatMap { it.entry.fields.asSequence() }) {
            if (field.name in existingFieldNames || !seen.add(field.name)) continue
            val builder =
                LookupElementBuilder
                    .create(field.name)
                    .withIcon(com.intellij.icons.AllIcons.Nodes.Field)
                    .withTypeText(field.type, true)
                    .withInsertHandler { insertionContext, _ ->
                        val document = insertionContext.document
                        val tailOffset = insertionContext.tailOffset
                        val nextChar = document.charsSequence.getOrNull(tailOffset)
                        if (nextChar != ':') {
                            document.insertString(tailOffset, ": ")
                            insertionContext.editor.caretModel.moveToOffset(tailOffset + 2)
                        }
                        insertionContext.commitDocument()
                        val previousLaterRunnable = insertionContext.laterRunnable
                        insertionContext.setLaterRunnable {
                            previousLaterRunnable?.run()
                            AutoPopupController.getInstance(insertionContext.project)
                                .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
                        }
                    }
            lookups += PrioritizedLookupElement.withPriority(builder, 4200.0)
        }

        return lookups
    }

    private fun fieldValueLookups(
        anchor: PsiElement,
        ownerEntries: List<VisibleConstructibleEntry>,
        currentFieldName: String,
        currentValueText: String
    ): List<LookupElement> {
        val expectedType =
            ownerEntries
                .asSequence()
                .flatMap { entry -> entry.entry.fields.asSequence() }
                .firstOrNull { it.name == currentFieldName }
                ?.type
                ?: return emptyList()

        val siblingFieldCandidates =
            ownerEntries
                .asSequence()
                .flatMap { entry -> entry.entry.fields.asSequence() }
                .filter { field -> field.name != currentFieldName }
                .map { field ->
                    AikenTypedCompletionCandidate(field.name, field.type, CompletionSymbolKind.FIELD, 3700.0)
                }
                .distinctBy { it.name }
                .toList()

        return AikenTypeDirectedCompletionSupport.lookupsForExpectedType(
            anchor = anchor,
            expectedType = expectedType,
            currentValueText = currentValueText,
            extraCandidates = siblingFieldCandidates,
            excludedNames = setOf(currentFieldName)
        )
    }

    private fun visibleConstructibles(anchor: PsiElement, useModel: com.medusalabs.aiken.imports.AikenUseModel): List<VisibleConstructibleEntry> {
        val file = anchor.containingFile ?: return emptyList()
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val entries = ArrayList<VisibleConstructibleEntry>()
        entries +=
            AikenConstructibleExtractor.extract(file.text).map { entry ->
                VisibleConstructibleEntry(currentModulePath, entry, isCurrentFile = true)
            }

        val importedModules =
            useModel.statements
                .mapTo(LinkedHashSet()) { it.modulePath }
                .filter { it.isNotBlank() && it != currentModulePath }

        if (importedModules.isEmpty()) return entries

        for (modulePath in importedModules) {
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                entries +=
                    AikenConstructibleExtractor.extract(moduleFile.contentsToByteArray().toString(Charsets.UTF_8)).map { entry ->
                        VisibleConstructibleEntry(modulePath, entry, isCurrentFile = false)
                    }
            }
        }

        val project = anchor.project
        if (DumbService.isDumb(project)) {
            return entries.distinctBy { Triple(it.modulePath, it.entry.ownerName, it.entry.offset) }
        }

        val scope = AikenSearchScopes.forElement(anchor)
        val index = FileBasedIndex.getInstance()
        try {
            for (modulePath in importedModules) {
                for (value in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, modulePath, scope)) {
                    entries +=
                        decodeAikenConstructibleIndexValue(value).map { entry ->
                            VisibleConstructibleEntry(modulePath, entry, isCurrentFile = false)
                        }
                }
            }
        } catch (_: IndexNotReadyException) {
            return entries
        }

        return entries.distinctBy { Triple(it.modulePath, it.entry.ownerName, it.entry.offset) }
    }

    private fun resolveOwnerEntries(
        anchor: PsiElement,
        ownerExpression: String,
        visibleEntries: List<VisibleConstructibleEntry>,
        useModel: com.medusalabs.aiken.imports.AikenUseModel
    ): List<VisibleConstructibleEntry> {
        val segments = ownerExpression.split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptyList()

        return when (segments.size) {
            1 -> resolveUnqualifiedOwner(segments.single(), anchor, visibleEntries, useModel)
            2 -> {
                val qualifier = segments[0]
                val ownerName = segments[1]
                val modulePaths = useModel.resolveModuleTargets(qualifier).mapTo(LinkedHashSet()) { it.modulePath }
                if (modulePaths.isNotEmpty()) {
                    visibleEntries.filter { it.entry.ownerName == ownerName && it.modulePath in modulePaths }.ifEmpty {
                        visibleEntries.filter { it.entry.ownerName == ownerName }
                    }
                } else {
                    visibleEntries.filter { it.entry.ownerName == ownerName && it.entry.resultTypeName == qualifier }
                }
            }
            else -> {
                val moduleQualifier = segments.dropLast(2).joinToString(".")
                val resultTypeName = segments[segments.size - 2]
                val ownerName = segments.last()
                val modulePaths = useModel.resolveModuleTargets(moduleQualifier).mapTo(LinkedHashSet()) { it.modulePath }
                visibleEntries.filter { it.entry.ownerName == ownerName && it.entry.resultTypeName == resultTypeName && it.modulePath in modulePaths }
            }
        }
    }

    private fun resolveUnqualifiedOwner(
        ownerName: String,
        anchor: PsiElement,
        visibleEntries: List<VisibleConstructibleEntry>,
        useModel: com.medusalabs.aiken.imports.AikenUseModel
    ): List<VisibleConstructibleEntry> {
        val sameModuleEntries = visibleEntries.filter { it.isCurrentFile && it.entry.ownerName == ownerName }
        if (sameModuleEntries.isNotEmpty()) {
            return sameModuleEntries
        }

        val entries = ArrayList<VisibleConstructibleEntry>()
        entries += visibleEntries.filter { it.entry.ownerName == ownerName }

        val importedTargets = useModel.resolveSymbolTargets(ownerName, null)
        if (importedTargets.isEmpty()) {
            return if (entries.isNotEmpty()) {
                entries.distinctBy { Triple(it.modulePath, it.entry.ownerName, it.entry.offset) }
            } else {
                globallyVisibleOwnerEntries(anchor, ownerName)
            }
        }

        for (target in importedTargets) {
            entries += visibleEntries.filter { it.modulePath == target.modulePath && it.entry.ownerName == target.symbolName }
        }
        if (entries.isNotEmpty()) {
            return entries.distinctBy { Triple(it.modulePath, it.entry.ownerName, it.entry.offset) }
        }

        return globallyVisibleOwnerEntries(anchor, ownerName)
    }

    private fun globallyVisibleOwnerEntries(
        anchor: PsiElement,
        ownerName: String
    ): List<VisibleConstructibleEntry> {
        if (ownerName.isBlank() || DumbService.isDumb(anchor.project)) return emptyList()

        val scope = AikenSearchScopes.forElement(anchor)
        val index = FileBasedIndex.getInstance()
        val seenFiles = LinkedHashSet<VirtualFile>()
        val result = ArrayList<VisibleConstructibleEntry>()

        try {
            index.processValues(
                AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME,
                aikenTopLevelSymbolNameKey(AikenTopLevelSymbolKind.TYPE, ownerName),
                null,
                ValueProcessor<Int> { file, _ ->
                    if (!seenFiles.add(file)) return@ValueProcessor true
                    val modulePath = AikenModulePath.fromFile(file)
                    val text = file.contentsToByteArray().toString(Charsets.UTF_8)
                    result +=
                        AikenConstructibleExtractor.extract(text)
                            .filter { entry ->
                                entry.ownerName == ownerName &&
                                    (entry.kind == AikenConstructibleKind.TYPE || entry.kind == AikenConstructibleKind.CONSTRUCTOR)
                            }
                            .map { entry ->
                                VisibleConstructibleEntry(modulePath, entry, isCurrentFile = false)
                            }
                    true
                },
                scope
            )
        } catch (_: IndexNotReadyException) {
            return emptyList()
        }

        return result.distinctBy { Triple(it.modulePath, it.entry.ownerName, it.entry.offset) }
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

    private data class RecordCompletionContext(
        val ownerExpression: String,
        val mode: RecordCompletionMode,
        val currentFieldName: String,
        val existingFieldNames: Set<String>,
        val currentValueText: String
    ) {
        companion object {
            fun detect(text: String, offset: Int): RecordCompletionContext? {
                val site = findRecordLiteralSite(text, offset) ?: return null
                val bodyText = text.substring(site.bodyRange.startOffset, offset.coerceAtMost(text.length))
                val parsed = parseCurrentFieldContext(bodyText) ?: return null

                return RecordCompletionContext(
                    ownerExpression = site.ownerExpression,
                    mode = parsed.mode,
                    currentFieldName = parsed.currentFieldName,
                    existingFieldNames = parsed.existingFieldNames,
                    currentValueText = parsed.currentValueText
                )
            }

            private fun findRecordLiteralSite(text: String, offset: Int): RecordLiteralSite? {
                var braceDepth = 0
                var parenDepth = 0
                var bracketDepth = 0
                var index = offset.coerceAtMost(text.length) - 1

                while (index >= 0) {
                    when (text[index]) {
                        '}' -> braceDepth++
                        '{' -> {
                            if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                                val ownerExpression = readOwnerExpression(text, index) ?: return null
                                return RecordLiteralSite(
                                    ownerExpression = ownerExpression,
                                    bodyRange = TextRange(index + 1, offset.coerceAtMost(text.length))
                                )
                            }
                            braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        }
                        ')' -> parenDepth++
                        '(' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        ']' -> bracketDepth++
                        '[' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    }
                    index--
                }

                return null
            }

            private fun readOwnerExpression(text: String, braceOffset: Int): String? {
                var index = braceOffset - 1
                while (index >= 0 && text[index].isWhitespace()) index--
                if (index < 0) return null

                val end = index + 1
                while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '.')) index--
                val start = index + 1
                if (start >= end) return null

                val expression = text.substring(start, end).trim()
                val lastSegment = expression.substringAfterLast('.')
                return expression.takeIf { lastSegment.firstOrNull()?.isUpperCase() == true }
            }

            private fun parseCurrentFieldContext(bodyText: String): ParsedFieldContext? {
                var currentEntryStart = 0
                val existingFieldNames = LinkedHashSet<String>()
                var parenDepth = 0
                var bracketDepth = 0
                var braceDepth = 0
                var angleDepth = 0

                for (index in bodyText.indices) {
                    val ch = bodyText[index]
                    when (ch) {
                        '(' -> parenDepth++
                        ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '<' -> angleDepth++
                        '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                        ',' -> {
                            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0) {
                                extractFieldName(bodyText.substring(currentEntryStart, index))?.let(existingFieldNames::add)
                                currentEntryStart = index + 1
                            }
                        }
                    }
                }

                val currentEntry = bodyText.substring(currentEntryStart)
                val currentFieldName = extractFieldName(currentEntry).orEmpty()
                val colonIndex = topLevelColonIndex(currentEntry)

                return if (colonIndex >= 0) {
                    ParsedFieldContext(
                        mode = RecordCompletionMode.FIELD_VALUE,
                        currentFieldName = currentFieldName,
                        existingFieldNames = existingFieldNames,
                        currentValueText = currentEntry.substring(colonIndex + 1)
                    )
                } else {
                    ParsedFieldContext(
                        mode = RecordCompletionMode.FIELD_NAME,
                        currentFieldName = currentFieldName,
                        existingFieldNames = existingFieldNames,
                        currentValueText = ""
                    )
                }
            }

            private fun extractFieldName(entryText: String): String? {
                val colonIndex = topLevelColonIndex(entryText)
                val head = if (colonIndex >= 0) entryText.substring(0, colonIndex) else entryText
                val trimmed = head.trim()
                if (trimmed.isEmpty()) return null
                val identifier = trimmed.takeLastWhile { it.isLetterOrDigit() || it == '_' }
                return identifier.takeIf { it.isNotBlank() }
            }

            private fun topLevelColonIndex(text: String): Int {
                var parenDepth = 0
                var bracketDepth = 0
                var braceDepth = 0
                var angleDepth = 0
                for (index in text.indices) {
                    when (text[index]) {
                        '(' -> parenDepth++
                        ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '<' -> angleDepth++
                        '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                        ':' -> {
                            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0) {
                                return index
                            }
                        }
                    }
                }
                return -1
            }
        }
    }

    private data class RecordLiteralSite(
        val ownerExpression: String,
        val bodyRange: TextRange
    )

    private data class VisibleConstructibleEntry(
        val modulePath: String?,
        val entry: AikenConstructibleEntry,
        val isCurrentFile: Boolean
    )

    private data class ParsedFieldContext(
        val mode: RecordCompletionMode,
        val currentFieldName: String,
        val existingFieldNames: Set<String>,
        val currentValueText: String
    )
}

data class RecordCompletionSuggestions(
    val mode: RecordCompletionMode,
    val lookups: List<LookupElement>
)

enum class RecordCompletionMode {
    FIELD_NAME,
    FIELD_VALUE
}
