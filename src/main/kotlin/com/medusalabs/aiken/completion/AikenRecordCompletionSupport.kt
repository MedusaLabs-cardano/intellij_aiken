package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
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
    fun isRecordFieldContext(text: String, offset: Int): Boolean =
        RecordCompletionContext.detect(text, offset) != null

    fun isRecordFieldNameContext(text: String, offset: Int): Boolean =
        RecordCompletionContext.detect(text, offset)?.mode == RecordCompletionMode.FIELD_NAME

    fun isRecordFieldValueContext(text: String, offset: Int): Boolean =
        RecordCompletionContext.detect(text, offset)?.mode == RecordCompletionMode.FIELD_VALUE

    fun currentFieldValueText(text: String, offset: Int): String? =
        RecordCompletionContext.detect(text, offset)
            ?.takeIf { it.mode == RecordCompletionMode.FIELD_VALUE }
            ?.currentValueText

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
            RecordCompletionMode.FIELD_VALUE ->
                if (context.siteKind == RecordSiteKind.PATTERN) {
                    patternFieldValueLookups(anchor, ownerEntries, context.currentFieldName, context.currentValueText)
                } else {
                    fieldValueLookups(anchor, ownerEntries, context.currentFieldName, context.currentValueText)
                }
            RecordCompletionMode.SPREAD ->
                if (context.siteKind == RecordSiteKind.PATTERN) {
                    emptyList()
                } else {
                    spreadLookups(anchor, ownerEntries)
                }
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
            lookups += builder
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

        val hasTypedPrefix = AikenSyntaxText.identifierPrefix(currentValueText, currentValueText.length).isNotBlank()
        val siblingFieldCandidates =
            ownerEntries
                .asSequence()
                .filter { ownerEntry ->
                    hasTypedPrefix || (ownerEntry.isCurrentFile && ownerEntry.entry.ownerName != ownerEntry.entry.resultTypeName)
                }
                .flatMap { entry -> entry.entry.fields.asSequence() }
                .filter { field -> field.name != currentFieldName }
                .map { field ->
                    AikenTypedCandidateContext.RecordFieldValue.SiblingField(
                        name = field.name,
                        type = field.type
                    )
                }
                .distinctBy(AikenTypedCandidateContext.RecordFieldValue.SiblingField::name)
                .toList()

        return AikenTypeDirectedCompletionSupport.lookupsForExpectedType(
            anchor = anchor,
            expectedType = expectedType,
            currentValueText = currentValueText,
            context = AikenTypedCandidateContext.RecordFieldValue(siblingFieldCandidates)
        )
    }

    private fun patternFieldValueLookups(
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

        val prefix = AikenSyntaxText.identifierPrefix(currentValueText, currentValueText.length).trim()
        if (prefix.isNotBlank()) {
            // Once user starts typing, switch back to full typed matching flow.
            return AikenTypeDirectedCompletionSupport.lookupsForExpectedType(
                anchor = anchor,
                expectedType = expectedType,
                currentValueText = currentValueText
            )
        }

        val result = ArrayList<LookupElement>()
        result +=
            AikenCompletionSorting.annotateTyped(
                AikenTypedLookupFactory.createTypeDirectedLookup(
                    text = currentFieldName,
                    kind = CompletionSymbolKind.IDENTIFIER,
                    typeText = AikenTypeText.normalizeWhitespace(expectedType)
                ),
                AikenTypedCompletionCategory.LOCAL_BINDING
            )
        result += AikenTypeDirectedCompletionSupport.constructibleLookupsForExpectedType(anchor, expectedType)
        return result.distinctBy { it.lookupString }
    }

    private fun spreadLookups(
        anchor: PsiElement,
        ownerEntries: List<VisibleConstructibleEntry>
    ): List<LookupElement> {
        val expectedType =
            ownerEntries
                .asSequence()
                .map { AikenTypeText.normalizeWhitespace(it.entry.resultTypeName) }
                .firstOrNull { it.isNotEmpty() }
                ?: return emptyList()

        return AikenTypeDirectedCompletionSupport.spreadLookupsForExpectedType(anchor, expectedType)
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

    private data class RecordCompletionContext(
        val ownerExpression: String,
        val siteKind: RecordSiteKind,
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
                    siteKind = site.siteKind,
                    mode = parsed.mode,
                    currentFieldName = parsed.currentFieldName,
                    existingFieldNames = parsed.existingFieldNames,
                    currentValueText = parsed.currentValueText
                )
            }

            private fun findRecordLiteralSite(text: String, offset: Int): RecordLiteralSite? {
                val braceOffset =
                    AikenTopLevelText.findEnclosingOpening(
                        text = text,
                        opening = '{',
                        offsetExclusive = offset.coerceAtMost(text.length)
                    )
                        ?: return null
                val ownerInfo = readOwnerExpression(text, braceOffset) ?: return null
                val ownerExpression = ownerInfo.first
                val ownerStart = ownerInfo.second
                return RecordLiteralSite(
                    ownerExpression = ownerExpression,
                    siteKind = detectRecordSiteKind(text, ownerStart = ownerStart),
                    bodyRange = TextRange(braceOffset + 1, offset.coerceAtMost(text.length))
                )
            }

            private fun readOwnerExpression(text: String, braceOffset: Int): Pair<String, Int>? {
                var index = braceOffset - 1
                while (index >= 0 && text[index].isWhitespace()) index--
                if (index < 0) return null

                val end = index + 1
                while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '.')) index--
                val start = index + 1
                if (start >= end) return null

                val expression = text.substring(start, end).trim()
                val lastSegment = expression.substringAfterLast('.')
                if (lastSegment.firstOrNull()?.isUpperCase() != true) return null

                var boundaryIndex = index
                while (boundaryIndex >= 0 && text[boundaryIndex].isWhitespace()) boundaryIndex--
                if (boundaryIndex >= 0) {
                    val boundaryChar = text[boundaryIndex]
                    if (boundaryChar == '{' && isTypeDeclarationOpeningBrace(text, boundaryIndex)) {
                        return null
                    }
                    if (boundaryChar.isLetterOrDigit() || boundaryChar == '_' || boundaryChar == '>') {
                        val precedingWord = readIdentifierEndingAt(text, boundaryIndex).orEmpty()
                        if (precedingWord != "let" && precedingWord != "expect") {
                            return null
                        }
                    }
                }

                return expression to start
            }

            private fun detectRecordSiteKind(
                text: String,
                ownerStart: Int
            ): RecordSiteKind {
                val precedingWord = readIdentifierEndingAt(text, ownerStart - 1).orEmpty()
                if (precedingWord == "let" || precedingWord == "expect") return RecordSiteKind.PATTERN
                if (isInsideWhenArmPattern(text, ownerStart)) return RecordSiteKind.PATTERN
                return RecordSiteKind.VALUE
            }

            private data class WhenBodyRange(
                val openBrace: Int,
                val closeBrace: Int
            )

            private fun isInsideWhenArmPattern(
                text: String,
                ownerOffset: Int
            ): Boolean {
                val lexer = com.medusalabs.aiken.highlight.lexer.AikenLexing.createLexer()
                lexer.start(text)

                while (lexer.tokenType != null) {
                    val tokenType = lexer.tokenType
                    val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    if (tokenType != com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.KEYWORD || tokenText != "when") {
                        lexer.advance()
                        continue
                    }

                    val whenBody = findWhenBody(text, lexer.tokenEnd)
                    if (whenBody == null) {
                        lexer.advance()
                        continue
                    }
                    if (ownerOffset <= whenBody.openBrace || ownerOffset >= whenBody.closeBrace) {
                        lexer.start(text, (whenBody.closeBrace + 1).coerceAtMost(text.length), text.length, 0)
                        continue
                    }

                    var cursor = whenBody.openBrace + 1
                    while (cursor < whenBody.closeBrace) {
                        val arrowOffset = findTopLevelArrowInRange(text, cursor, whenBody.closeBrace) ?: break
                        val patternStart = skipWhitespaceForward(text, cursor)
                        if (ownerOffset in patternStart until arrowOffset) return true

                        val expressionStart = skipWhitespaceForward(text, arrowOffset + 2)
                        if (expressionStart >= whenBody.closeBrace) break
                        val expressionEnd = consumeExpressionEndWithin(text, expressionStart, whenBody.closeBrace)
                        cursor = if (expressionEnd > cursor) expressionEnd else (arrowOffset + 2)
                    }

                    lexer.start(text, (whenBody.closeBrace + 1).coerceAtMost(text.length), text.length, 0)
                }

                return false
            }

            private fun findWhenBody(
                text: String,
                afterWhenOffset: Int
            ): WhenBodyRange? {
                val lexer = com.medusalabs.aiken.highlight.lexer.AikenLexing.createLexer()
                lexer.start(text, afterWhenOffset.coerceIn(0, text.length), text.length, 0)

                var parenDepth = 0
                var bracketDepth = 0
                var braceDepth = 0
                var angleDepth = 0
                var sawTopLevelIs = false

                while (lexer.tokenType != null) {
                    val tokenType = lexer.tokenType
                    val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    if (
                        tokenType == com.intellij.psi.TokenType.WHITE_SPACE ||
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.WHITESPACE ||
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.COMMENT
                    ) {
                        lexer.advance()
                        continue
                    }

                    val atTopLevel = parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0
                    if (tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.KEYWORD && tokenText == "is" && atTopLevel) {
                        sawTopLevelIs = true
                        lexer.advance()
                        continue
                    }
                    if (tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.LBRACE && sawTopLevelIs && atTopLevel) {
                        val open = lexer.tokenStart
                        val close = AikenSyntaxText.findMatchingDelimiter(text, open, '{', '}') ?: return null
                        return WhenBodyRange(openBrace = open, closeBrace = close)
                    }

                    when {
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.LPAREN -> parenDepth++
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.RPAREN -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.LBRACKET -> bracketDepth++
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.RBRACKET -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.LBRACE -> braceDepth++
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.OPERATOR && tokenText == "<" -> angleDepth++
                        tokenType == com.medusalabs.aiken.highlight.lexer.AikenTokenTypes.OPERATOR && tokenText == ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                    }
                    lexer.advance()
                }

                return null
            }

            private fun findTopLevelArrowInRange(
                text: String,
                start: Int,
                endExclusive: Int
            ): Int? {
                var parenDepth = 0
                var bracketDepth = 0
                var braceDepth = 0
                var angleDepth = 0
                var inString = false
                var inLineComment = false
                var index = start.coerceIn(0, text.length)
                val limit = endExclusive.coerceIn(index, text.length)

                while (index + 1 < limit) {
                    val ch = text[index]

                    if (inLineComment) {
                        if (ch == '\n' || ch == '\r') inLineComment = false
                        index++
                        continue
                    }
                    if (inString) {
                        if (ch == '\\' && index + 1 < limit) {
                            index += 2
                            continue
                        }
                        if (ch == '"') inString = false
                        index++
                        continue
                    }
                    if (ch == '/' && index + 1 < limit && text[index + 1] == '/') {
                        inLineComment = true
                        index += 2
                        continue
                    }
                    if (ch == '"') {
                        inString = true
                        index++
                        continue
                    }

                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0 && ch == '-' && text[index + 1] == '>') {
                        return index
                    }

                    when (ch) {
                        '(' -> parenDepth++
                        ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '<' -> angleDepth++
                        '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                    }
                    index++
                }
                return null
            }

            private fun consumeExpressionEndWithin(
                text: String,
                start: Int,
                limitExclusive: Int
            ): Int {
                var parenDepth = 0
                var bracketDepth = 0
                var braceDepth = 0
                var inString = false
                var inLineComment = false
                var index = start.coerceIn(0, text.length)
                val limit = limitExclusive.coerceIn(index, text.length)

                while (index < limit) {
                    val ch = text[index]
                    if (inLineComment) {
                        if (ch == '\n' || ch == '\r') inLineComment = false
                        index++
                        continue
                    }
                    if (inString) {
                        if (ch == '\\' && index + 1 < limit) {
                            index += 2
                            continue
                        }
                        if (ch == '"') inString = false
                        index++
                        continue
                    }
                    if (ch == '/' && index + 1 < limit && text[index + 1] == '/') {
                        inLineComment = true
                        index += 2
                        continue
                    }
                    if (ch == '"') {
                        inString = true
                        index++
                        continue
                    }
                    when (ch) {
                        '(' -> parenDepth++
                        ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '\n', '\r' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
                    }
                    index++
                }
                return limit
            }

            private fun skipWhitespaceForward(
                text: String,
                start: Int
            ): Int {
                var index = start.coerceIn(0, text.length)
                while (index < text.length && text[index].isWhitespace()) index++
                return index
            }

            private fun readIdentifierEndingAt(
                text: String,
                endIndex: Int
            ): String? {
                var index = endIndex.coerceAtMost(text.lastIndex)
                while (index >= 0 && text[index].isWhitespace()) index--
                if (index < 0 || !(text[index].isLetterOrDigit() || text[index] == '_')) return null
                val endExclusive = index + 1
                while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) index--
                val start = index + 1
                if (start >= endExclusive) return null
                return text.substring(start, endExclusive)
            }

            private fun isTypeDeclarationOpeningBrace(
                text: String,
                braceIndex: Int
            ): Boolean {
                var index = braceIndex - 1
                while (index >= 0 && text[index].isWhitespace()) index--
                if (index < 0 || !(text[index].isLetterOrDigit() || text[index] == '_')) return false

                while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) index--
                while (index >= 0 && text[index].isWhitespace()) index--
                val precedingWord = readIdentifierEndingAt(text, index).orEmpty()
                return precedingWord == "type"
            }

            private fun parseCurrentFieldContext(bodyText: String): ParsedFieldContext? {
                val existingFieldNames = LinkedHashSet<String>()
                val entryRanges = AikenTopLevelText.splitRanges(bodyText, ',', trackAngles = true)

                for (range in entryRanges.dropLast(1)) {
                    extractFieldName(bodyText.substring(range.startOffset, range.endOffset))?.let(existingFieldNames::add)
                }

                val currentEntryRange = entryRanges.lastOrNull() ?: TextRange(0, bodyText.length)
                val currentEntry = bodyText.substring(currentEntryRange.startOffset, currentEntryRange.endOffset)
                val currentFieldName = extractFieldName(currentEntry).orEmpty()
                val colonIndex = AikenTopLevelText.indexOf(currentEntry, ':', trackAngles = true)
                val currentSegment = AikenCurrentExpressionSegment.fromText(currentEntry)

                return if (currentSegment.isSpread) {
                    ParsedFieldContext(
                        mode = RecordCompletionMode.SPREAD,
                        currentFieldName = "",
                        existingFieldNames = existingFieldNames,
                        currentValueText = currentSegment.effectiveValueText
                    )
                } else if (colonIndex >= 0) {
                    val valueSegment = AikenCurrentExpressionSegment.fromText(currentEntry.substring(colonIndex + 1))
                    ParsedFieldContext(
                        mode = RecordCompletionMode.FIELD_VALUE,
                        currentFieldName = currentFieldName,
                        existingFieldNames = existingFieldNames,
                        currentValueText = valueSegment.text
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
                val colonIndex = AikenTopLevelText.indexOf(entryText, ':', trackAngles = true)
                val head = if (colonIndex >= 0) entryText.substring(0, colonIndex) else entryText
                val trimmed = head.trim()
                if (trimmed.isEmpty()) return null
                val identifier = trimmed.takeLastWhile { it.isLetterOrDigit() || it == '_' }
                return identifier.takeIf { it.isNotBlank() }
            }
        }
    }

    private data class RecordLiteralSite(
        val ownerExpression: String,
        val siteKind: RecordSiteKind,
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

private enum class RecordSiteKind {
    VALUE,
    PATTERN
}

data class RecordCompletionSuggestions(
    val mode: RecordCompletionMode,
    val lookups: List<LookupElement>
)

enum class RecordCompletionMode {
    FIELD_NAME,
    FIELD_VALUE,
    SPREAD
}
