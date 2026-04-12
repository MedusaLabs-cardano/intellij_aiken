package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_CONSTRUCTIBLE_INDEX_NAME
import com.medusalabs.aiken.index.AikenConstructibleEntry
import com.medusalabs.aiken.index.AikenConstructibleExtractor
import com.medusalabs.aiken.index.decodeAikenConstructibleIndexValue
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes

object AikenConstructibleCompletionSupport {
    private val PENDING_CONSTRUCTIBLE_FORM_KEY =
        Key.create<PendingConstructibleInvocation>("aiken.pending.constructible.form")
    private data class ConstructibleLookupSpec(
        val text: String,
        val icon: javax.swing.Icon,
        val typeText: String,
        val insertionFamily: AikenConstructibleInsertionFamily,
        val tailText: String? = null,
        val rankingCategory: AikenConstructibleFormCompletionCategory? = null
    )

    fun createVisibleLookup(
        constructible: AikenConstructibleCompletionInfo,
        typeText: String,
        lookupName: String = constructible.name
    ): LookupElement =
        createLookup(
            ConstructibleLookupSpec(
                text = lookupName,
                icon = AllIcons.Nodes.Class,
                typeText = typeText,
                insertionFamily = rootInsertionFamily(constructible, lookupName)
            )
        )

    fun createAutoImportedLookup(
        constructible: AikenConstructibleCompletionInfo,
        typeText: String,
        lookupName: String = constructible.name
    ): LookupElement =
        createLookup(
            ConstructibleLookupSpec(
                text = lookupName,
                icon = AllIcons.Nodes.Class,
                typeText = typeText,
                insertionFamily = rootInsertionFamily(constructible, lookupName),
                tailText = constructible.modulePath?.let { " from $it" }
            )
        )

    fun invocationFormVariants(
        parameters: CompletionParameters,
        anchor: PsiElement?,
        offset: Int
    ): List<LookupElement> {
        val editor = parameters.editor
        val pending = editor.getUserData(PENDING_CONSTRUCTIBLE_FORM_KEY)
        val pendingInfo =
            if (pending != null && pending.caretOffset == offset) {
                pending.constructible
            } else {
                if (pending != null) {
                    editor.putUserData(PENDING_CONSTRUCTIBLE_FORM_KEY, null)
                }
                detectConstructibleAtCaret(anchor, offset)
            } ?: return emptyList()

        if (pendingInfo.fields.isEmpty()) return emptyList()

        val result = ArrayList<LookupElement>(2)
        if (pendingInfo.supportsNamedSyntax) {
            result += createNamedFormLookup()
        }
        result += createPositionalFormLookup()
        return result
    }

    fun expectedParameterTypes(
        anchor: PsiElement,
        calleeName: String,
        qualifier: String?,
        parameterIndex: Int
    ): Set<String> {
        if (parameterIndex < 0) return emptySet()
        return resolveVisibleConstructibles(anchor, calleeName, qualifier)
            .mapNotNull { constructible -> constructible.fields.getOrNull(parameterIndex)?.type }
            .map(AikenTypeText::normalizeWhitespace)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun findVisibleConstructible(
        anchor: PsiElement,
        name: String,
        modulePath: String?
    ): AikenConstructibleCompletionInfo? =
        resolveVisibleConstructibles(anchor, name, qualifier = null, forcedModulePath = modulePath).firstOrNull()

    fun findConstructibleInModuleText(
        modulePath: String,
        moduleText: String,
        name: String
    ): AikenConstructibleCompletionInfo? =
        AikenConstructibleExtractor.extract(moduleText)
            .firstOrNull { it.ownerName == name }
            ?.toCompletionInfo(modulePath = modulePath, needsImport = true)

    private fun scheduleConstructibleFormPopup(
        editor: Editor,
        project: com.intellij.openapi.project.Project,
        constructible: AikenConstructibleCompletionInfo,
        caretOffset: Int
    ) {
        if (constructible.fields.isEmpty()) return
        editor.putUserData(PENDING_CONSTRUCTIBLE_FORM_KEY, PendingConstructibleInvocation(constructible, caretOffset))
        editor.caretModel.moveToOffset(caretOffset)
        AutoPopupController.getInstance(project).autoPopupMemberLookup(editor, CompletionType.BASIC, null)
    }

    private fun createNamedFormLookup(
    ): LookupElement =
        createLookup(
            ConstructibleLookupSpec(
                text = "{}",
                icon = AllIcons.Nodes.Field,
                typeText = "named fields",
                insertionFamily = namedFormInsertionFamily(),
                rankingCategory = AikenConstructibleFormCompletionCategory.NAMED
            )
        )

    private fun createPositionalFormLookup(
    ): LookupElement =
        createLookup(
            ConstructibleLookupSpec(
                text = "()",
                icon = AllIcons.Nodes.Parameter,
                typeText = "positional args",
                insertionFamily = positionalFormInsertionFamily(),
                rankingCategory = AikenConstructibleFormCompletionCategory.POSITIONAL
            )
        )

    private fun createLookup(spec: ConstructibleLookupSpec): LookupElement {
        var builder =
            LookupElementBuilder
                .create(spec.text)
                .withIcon(spec.icon)
                .withTypeText(spec.typeText, true)
                .withInsertHandler { insertionContext, _ ->
                    AikenAutoPopupGuard.cancelPendingRequests(insertionContext.project)
                    applyInsertionFamily(insertionContext, spec.insertionFamily)
                }

        if (spec.tailText != null) {
            builder = builder.withTailText(spec.tailText, true)
        }

        return if (spec.rankingCategory != null) {
            AikenCompletionSorting.annotateConstructibleForm(builder, spec.rankingCategory)
        } else {
            builder
        }
    }

    private fun applyInsertionFamily(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        insertionFamily: AikenConstructibleInsertionFamily
    ) {
        when (insertionFamily) {
            is AikenConstructibleInsertionFamily.Root -> {
                val insertedOffset = replaceCurrentIdentifierPrefix(insertionContext, insertionFamily.lookupText)
                val popupCaretOffset = insertedOffset + insertionFamily.lookupText.length
                if (insertionFamily.autoImportTarget == null) {
                    insertionContext.commitDocument()
                    scheduleConstructibleFormPopup(
                        insertionContext.editor,
                        insertionContext.project,
                        insertionFamily.constructible,
                        popupCaretOffset
                    )
                    return
                }

                val insertedRangeMarker =
                    insertionContext.document.createRangeMarker(insertedOffset, popupCaretOffset).apply {
                        isGreedyToLeft = false
                        isGreedyToRight = true
                    }
                insertionContext.commitDocument()
                val previousLaterRunnable = insertionContext.laterRunnable
                insertionContext.setLaterRunnable {
                    try {
                        previousLaterRunnable?.run()
                        WriteCommandAction.runWriteCommandAction(insertionContext.project) {
                            insertStandaloneUseImport(
                                insertionContext.document,
                                insertionFamily.autoImportTarget.modulePath,
                                insertionFamily.autoImportTarget.symbolName
                            )
                            insertionContext.commitDocument()
                        }
                        val caretOffset =
                            if (insertedRangeMarker.isValid) {
                                insertedRangeMarker.endOffset
                            } else {
                                insertionContext.editor.caretModel.offset
                            }
                        scheduleConstructibleFormPopup(
                            insertionContext.editor,
                            insertionContext.project,
                            insertionFamily.constructible,
                            caretOffset
                        )
                    } finally {
                        insertedRangeMarker.dispose()
                    }
                }
            }
            AikenConstructibleInsertionFamily.NamedForm -> {
                replaceInsertedLookupText(insertionContext, " {}")
                insertionContext.editor.caretModel.moveToOffset(insertionContext.startOffset + 2)
                insertionContext.editor.putUserData(PENDING_CONSTRUCTIBLE_FORM_KEY, null)
                insertionContext.commitDocument()
                AutoPopupController.getInstance(insertionContext.project)
                    .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
            }
            AikenConstructibleInsertionFamily.PositionalForm -> {
                replaceInsertedLookupText(insertionContext, "()")
                insertionContext.editor.caretModel.moveToOffset(insertionContext.startOffset + 1)
                insertionContext.editor.putUserData(PENDING_CONSTRUCTIBLE_FORM_KEY, null)
                insertionContext.commitDocument()
                AutoPopupController.getInstance(insertionContext.project)
                    .autoPopupMemberLookup(insertionContext.editor, CompletionType.BASIC, null)
            }
        }
    }

    internal fun rootInsertionFamily(
        constructible: AikenConstructibleCompletionInfo,
        lookupName: String = constructible.name
    ): AikenConstructibleInsertionFamily.Root =
        AikenConstructibleInsertionFamily.Root(
            lookupText = lookupName,
            constructible = constructible,
            autoImportTarget =
                if (constructible.needsImport) {
                    constructible.modulePath?.let { AikenConstructibleAutoImportTarget(it, constructible.name) }
                } else {
                    null
                }
        )

    internal fun namedFormInsertionFamily(): AikenConstructibleInsertionFamily =
        AikenConstructibleInsertionFamily.NamedForm

    internal fun positionalFormInsertionFamily(): AikenConstructibleInsertionFamily =
        AikenConstructibleInsertionFamily.PositionalForm

    private fun detectConstructibleAtCaret(
        anchor: PsiElement?,
        offset: Int
    ): AikenConstructibleCompletionInfo? {
        val file = anchor?.containingFile ?: return null
        val text = file.text
        if (!isLikelyConstructibleValueContext(text, offset)) return null

        val identifierRange = identifierRangeEndingAt(text, offset) ?: return null
        val identifier = text.substring(identifierRange.first, identifierRange.last + 1)
        val qualifierRange = qualifierRangeBefore(text, identifierRange.first)
        val qualifier = qualifierRange?.let { text.substring(it.first, it.last + 1) }
        return resolveVisibleConstructibles(anchor, identifier, qualifier).firstOrNull()
    }

    private fun resolveVisibleConstructibles(
        anchor: PsiElement,
        name: String,
        qualifier: String?,
        forcedModulePath: String? = null
    ): List<AikenConstructibleCompletionInfo> {
        if (name.isBlank()) return emptyList()
        val file = anchor.containingFile ?: return emptyList()
        val text = file.text
        val useModel = AikenUseStatementParser.parseModel(text)
        val currentModulePath = AikenModulePath.fromFile(file.virtualFile)
        val seen = LinkedHashSet<Pair<String?, Int>>()
        val result = ArrayList<AikenConstructibleCompletionInfo>()

        fun addEntries(modulePath: String?, sourceText: String, needsImport: Boolean) {
            for (entry in AikenConstructibleExtractor.extract(sourceText)) {
                if (entry.ownerName != name) continue
                val key = modulePath to entry.offset
                if (!seen.add(key)) continue
                result += entry.toCompletionInfo(modulePath, needsImport)
            }
        }

        if (forcedModulePath == null && qualifier == null) {
            addEntries(currentModulePath, text, needsImport = false)
            for (target in useModel.resolveSymbolTargets(name, null)) {
                for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, target.modulePath)) {
                    addEntries(target.modulePath, moduleFile.contentsToByteArray().toString(Charsets.UTF_8), needsImport = false)
                }
            }
        }

        val targetModulePaths =
            when {
                forcedModulePath != null -> linkedSetOf(forcedModulePath)
                !qualifier.isNullOrBlank() ->
                    useModel.resolveModuleTargets(qualifier).mapTo(LinkedHashSet()) { it.modulePath }.ifEmpty { linkedSetOf(qualifier) }
                else -> linkedSetOf()
            }

        for (modulePath in targetModulePaths) {
            if (modulePath == currentModulePath) {
                addEntries(currentModulePath, text, needsImport = false)
                continue
            }

            var addedAny = false
            for (moduleFile in AikenModuleFiles.findFilesForModulePath(file.virtualFile, modulePath)) {
                addEntries(modulePath, moduleFile.contentsToByteArray().toString(Charsets.UTF_8), needsImport = false)
                addedAny = true
            }

            if (!addedAny && !DumbService.isDumb(anchor.project)) {
                val index = FileBasedIndex.getInstance()
                val scope = AikenSearchScopes.forElement(anchor)
                for (value in index.getValues(AIKEN_CONSTRUCTIBLE_INDEX_NAME, modulePath, scope)) {
                    for (entry in decodeAikenConstructibleIndexValue(value)) {
                        if (entry.ownerName != name) continue
                        val key = modulePath to entry.offset
                        if (!seen.add(key)) continue
                        result += entry.toCompletionInfo(modulePath, needsImport = false)
                    }
                }
            }
        }

        return result
    }

    private fun isLikelyConstructibleValueContext(
        text: String,
        offset: Int
    ): Boolean {
        val identifierRange = identifierRangeEndingAt(text, offset) ?: return false
        val nextIndex = skipWhitespaceForward(text, offset)
        if (nextIndex < text.length && (text[nextIndex] == '{' || text[nextIndex] == '(')) return false

        var index = identifierRange.first - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return false

        if (text[index] == ':') {
            return AikenRecordCompletionSupport.isRecordFieldValueContext(text, offset)
        }

        if (text[index] == '=' || text[index] == '(' || text[index] == '[' || text[index] == ',') {
            return true
        }

        return index > 0 && text[index] == '>' && text[index - 1] == '-'
    }

    private fun identifierRangeEndingAt(
        text: String,
        offset: Int
    ): IntRange? {
        var index = offset.coerceIn(0, text.length) - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        val end = index
        while (index >= 0 && isIdentifierChar(text[index])) index--
        val start = index + 1
        return if (start <= end) start..end else null
    }

    private fun qualifierRangeBefore(
        text: String,
        identifierStart: Int
    ): IntRange? {
        var index = identifierStart - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0 || text[index] != '.') return null
        index--
        while (index >= 0 && text[index].isWhitespace()) index--
        val end = index
        while (index >= 0 && isIdentifierChar(text[index])) index--
        val start = index + 1
        return if (start <= end) start..end else null
    }

    private fun skipWhitespaceForward(text: String, start: Int): Int {
        var index = start.coerceIn(0, text.length)
        while (index < text.length && text[index].isWhitespace()) index++
        return index
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

    private fun replaceInsertedLookupText(
        insertionContext: com.intellij.codeInsight.completion.InsertionContext,
        replacementText: String
    ) {
        insertionContext.document.replaceString(
            insertionContext.startOffset,
            insertionContext.tailOffset,
            replacementText
        )
    }

    private fun insertStandaloneUseImport(
        document: com.intellij.openapi.editor.Document,
        modulePath: String,
        symbolName: String
    ) {
        document.insertString(0, "use $modulePath.{$symbolName}\n")
    }
    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun AikenConstructibleEntry.toCompletionInfo(
        modulePath: String?,
        needsImport: Boolean
    ): AikenConstructibleCompletionInfo =
        AikenConstructibleCompletionInfo(
            name = ownerName,
            resultType = resultTypeName,
            fields = fields,
            supportsNamedSyntax = supportsNamedSyntax,
            modulePath = modulePath,
            needsImport = needsImport
        )

    data class AikenConstructibleCompletionInfo(
        val name: String,
        val resultType: String,
        val fields: List<com.medusalabs.aiken.index.AikenConstructibleFieldEntry>,
        val supportsNamedSyntax: Boolean,
        val modulePath: String?,
        val needsImport: Boolean
    )

    private data class PendingConstructibleInvocation(
        val constructible: AikenConstructibleCompletionInfo,
        val caretOffset: Int
    )
}
