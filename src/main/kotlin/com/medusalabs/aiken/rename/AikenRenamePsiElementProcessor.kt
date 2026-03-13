package com.medusalabs.aiken.rename

import com.intellij.lexer.Lexer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.index.UplcIdentifierIndex
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import com.medusalabs.aiken.navigation.AikenDeclarationResolver
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.search.AikenUseScopeProvider
import com.medusalabs.aiken.search.AikenUsageSearchSupport
import com.medusalabs.aiken.search.LegacyIndexedSearchScope

class AikenRenamePsiElementProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean {
        val target = if (element is PsiNamedElement) element else element.parent as? PsiNamedElement ?: return false
        val type = target.node?.elementType ?: return false
        return when (type) {
            AikenTokenTypes.IDENTIFIER,
            AikenTokenTypes.TYPE,
            AikenTokenTypes.FUNCTION,
            AikenTokenTypes.FIELD,
            UplcTokenTypes.IDENTIFIER,
            UplcTokenTypes.TYPE,
            UplcTokenTypes.FUNCTION,
            UplcTokenTypes.FIELD -> true
            else -> false
        }
    }

    override fun isInplaceRenameSupported(): Boolean = false

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        if (element is PsiNamedElement) return element
        val parent = element.parent
        return if (parent is PsiNamedElement) parent else element
    }

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<PsiReference> {
        val target = if (element is PsiNamedElement) element else element.parent as? PsiNamedElement ?: return emptyList()
        val resolvedTarget = AikenDeclarationResolver.resolve(target) ?: target
        val type = resolvedTarget.node?.elementType ?: return emptyList()
        val name = resolvedTarget.text
        if (name.isEmpty()) return emptyList()

        val project = resolvedTarget.project
        val psiManager = PsiManager.getInstance(project)
        val references = LinkedHashMap<Pair<VirtualFile, TextRange>, PsiReference>()
        val declarationFile = resolvedTarget.containingFile?.virtualFile
        val declarationRange = resolvedTarget.textRange
        if (declarationFile != null) {
            val psiFile = psiManager.findFile(declarationFile)
            if (psiFile != null) {
                val key = declarationFile to declarationRange
                references[key] = AikenRenameReference(resolvedTarget, psiFile, declarationRange)
            }
        }

        if (resolvedTarget.containingFile?.fileType == AikenFileType) {
            if (searchScope is LocalSearchScope) {
                val hits =
                    AikenUsageSearchSupport.findResolvedAikenReferencesInLocalScope(
                        resolvedTarget,
                        searchScope,
                        searchInCommentsAndStrings
                    )
                for (hit in hits) {
                    val key = hit.virtualFile to hit.range
                    if (!references.containsKey(key)) {
                        references[key] = hit.reference
                    }
                }
            } else {
                val platformReferences = super.findReferences(resolvedTarget, searchScope, searchInCommentsAndStrings)
                for (reference in platformReferences) {
                    val file = reference.element.containingFile?.virtualFile ?: continue
                    val range = reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)
                    val key = file to range
                    if (!references.containsKey(key)) {
                        references[key] = reference
                    }
                }
            }
            return references.values.toList()
        } else {
            val config = RenameConfig.fromElement(type) ?: return emptyList()
            val effectiveSearchScope =
                LegacyIndexedSearchScope.create(project, AikenSearchScopes.forElement(resolvedTarget), searchScope)
            val limitInFile = config.limitFactory?.invoke(resolvedTarget, name)
            val targetFiles = collectTargetFiles(project, resolvedTarget, config, name, effectiveSearchScope)
            if (targetFiles.isEmpty()) return references.values.toList()
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            for (vf in targetFiles) {
                val psiFile = psiManager.findFile(vf) ?: continue
                val document = psiDocumentManager.getDocument(psiFile) ?: continue
                val limit = if (vf == resolvedTarget.containingFile?.virtualFile) limitInFile else null
                val ranges = collectRenameRanges(document, config, name, limit)
                for (range in ranges.distinct()) {
                    if (!effectiveSearchScope.contains(psiFile, range)) continue

                    val key = vf to range
                    if (!references.containsKey(key)) {
                        references[key] = AikenRenameReference(resolvedTarget, psiFile, range)
                    }
                }
            }
        }

        return references.values.toList()
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val target = if (element is PsiNamedElement) element else element.parent as? PsiNamedElement ?: return
        val resolvedTarget = AikenDeclarationResolver.resolve(target) ?: target
        val oldName = resolvedTarget.text
        if (oldName == newName) return

        val type = resolvedTarget.node?.elementType ?: return
        val project = resolvedTarget.project

        if (resolvedTarget.containingFile?.fileType == AikenFileType) {
            val platformUsages =
                if (usages.isNotEmpty()) {
                    filterPlatformUsages(resolvedTarget, usages)
                } else {
                    val generatedUsages =
                        findReferences(resolvedTarget, AikenUseScopeProvider.effectiveForElement(resolvedTarget), false)
                            .map(::UsageInfo)
                            .toTypedArray()
                    filterPlatformUsages(resolvedTarget, generatedUsages)
                }

            super.renameElement(resolvedTarget, newName, platformUsages, listener)
            return
        }

        val config = RenameConfig.fromElement(type) ?: return
        val explicitUsageRanges = collectUsageRanges(resolvedTarget, usages)

        WriteCommandAction.runWriteCommandAction(project) {
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            if (explicitUsageRanges.isNotEmpty()) {
                for ((vf, ranges) in explicitUsageRanges) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                    val document = psiDocumentManager.getDocument(psiFile) ?: continue
                    for (range in ranges.distinct().sortedByDescending { it.startOffset }) {
                        document.replaceString(range.startOffset, range.endOffset, newName)
                    }
                    psiDocumentManager.commitDocument(document)
                }
            } else {
                val fallbackSearchScope =
                    LegacyIndexedSearchScope.create(
                        project,
                        AikenSearchScopes.forElement(resolvedTarget),
                        AikenSearchScopes.forElement(resolvedTarget)
                    )
                val limitInFile = config.limitFactory?.invoke(resolvedTarget, oldName)
                val targetFiles = collectTargetFiles(project, resolvedTarget, config, oldName, fallbackSearchScope)
                if (targetFiles.isEmpty()) return@runWriteCommandAction
                for (vf in targetFiles) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                    val document = psiDocumentManager.getDocument(psiFile) ?: continue
                    val limit = if (vf == resolvedTarget.containingFile?.virtualFile) limitInFile else null
                    val ranges = collectRenameRanges(document, config, oldName, limit)
                    if (ranges.isEmpty()) continue

                    for (range in ranges.distinct().sortedByDescending { it.startOffset }) {
                        document.replaceString(range.startOffset, range.endOffset, newName)
                    }
                    psiDocumentManager.commitDocument(document)
                }
            }
        }
        listener?.elementRenamed(resolvedTarget)
    }

    private fun collectUsageRanges(
        resolvedTarget: PsiElement,
        usages: Array<UsageInfo>
    ): Map<VirtualFile, List<TextRange>> {
        if (usages.isEmpty()) return emptyMap()

        val rangesByFile = LinkedHashMap<VirtualFile, LinkedHashSet<TextRange>>()

        fun addRange(file: VirtualFile?, range: TextRange?) {
            if (file == null || range == null) return
            rangesByFile.computeIfAbsent(file) { LinkedHashSet() }.add(range)
        }

        addRange(resolvedTarget.containingFile?.virtualFile, resolvedTarget.textRange)

        for (usage in usages) {
            val file = usage.virtualFile ?: usage.file?.virtualFile ?: usage.element?.containingFile?.virtualFile
            val segment = usage.segment
            val range =
                when {
                    segment != null -> TextRange(segment.startOffset, segment.endOffset)
                    usage.element != null -> usage.element!!.textRange
                    else -> null
                }
            addRange(file, range)
        }

        return rangesByFile.mapValues { entry -> entry.value.toList() }
    }

    private fun filterPlatformUsages(
        resolvedTarget: PsiElement,
        usages: Array<UsageInfo>
    ): Array<UsageInfo> {
        if (usages.isEmpty()) return usages

        val declarationFile = resolvedTarget.containingFile?.virtualFile
        val declarationRange = resolvedTarget.textRange

        return usages.filterNot { usage ->
            val reference = usage.reference
            if (reference !is AikenRenameReference) return@filterNot false

            val usageFile = usage.virtualFile ?: usage.file?.virtualFile ?: usage.element?.containingFile?.virtualFile
            usageFile == declarationFile && reference.renameRange == declarationRange
        }.toTypedArray()
    }

    private fun collectTargetFiles(
        project: Project,
        element: PsiElement,
        config: RenameConfig,
        name: String,
        searchScope: LegacyIndexedSearchScope
    ): Collection<VirtualFile> {
        val currentFile = element.containingFile?.virtualFile
        val files =
            when (config.scope) {
                RenameScope.CURRENT_FILE -> currentFile?.let { listOf(it) } ?: emptyList()
                RenameScope.ALL_PROJECT_FILES -> collectProjectFiles(project, config, name, searchScope.indexScope)
        }
        return files.filter(searchScope::contains)
    }

    private fun collectProjectFiles(
        project: Project,
        config: RenameConfig,
        name: String,
        scope: GlobalSearchScope
    ): Collection<VirtualFile> {
        if (DumbService.getInstance(project).isDumb) return emptyList()
        return try {
            // Our identifier index only stores names with length >= 2.
            if (name.length < 2) {
                return FileTypeIndex.getFiles(config.fileType, scope)
            }

            FileBasedIndex.getInstance().getContainingFiles(config.indexId, name, scope)
                .filter { it.fileType == config.fileType }
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun collectRenameRanges(
        document: Document,
        config: RenameConfig,
        oldName: String,
        limit: TextRange?
    ): List<TextRange> {
        val text = document.charsSequence
        val lexer = config.lexerFactory()
        lexer.start(text)

        val startLimit = limit?.startOffset ?: 0
        val endLimit = limit?.endOffset ?: text.length

        val ranges = ArrayList<TextRange>()
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            if (tokenType != null && config.renameTokenTypes.contains(tokenType)) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                if (start >= startLimit && end <= endLimit) {
                    val word = text.subSequence(start, end).toString()
                    if (word == oldName) {
                        ranges.add(TextRange(start, end))
                    }
                }
            }
            lexer.advance()
        }
        return ranges
    }

    private data class RenameConfig(
        val lexerFactory: () -> Lexer,
        val renameTokenTypes: Set<com.intellij.psi.tree.IElementType>,
        val fileType: com.intellij.openapi.fileTypes.FileType,
        val indexId: com.intellij.util.indexing.ID<String, Int>,
        val scope: RenameScope,
        val limitFactory: ((PsiElement, String) -> TextRange?)? = null
    ) {
        companion object {
            fun fromElement(type: com.intellij.psi.tree.IElementType): RenameConfig? =
                when (type) {
                    UplcTokenTypes.FUNCTION -> RenameConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        renameTokenTypes = setOf(UplcTokenTypes.FUNCTION),
                        fileType = UplcFileType,
                        indexId = UplcIdentifierIndex.NAME,
                        scope = RenameScope.ALL_PROJECT_FILES
                    )
                    UplcTokenTypes.TYPE -> RenameConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        renameTokenTypes = setOf(UplcTokenTypes.TYPE),
                        fileType = UplcFileType,
                        indexId = UplcIdentifierIndex.NAME,
                        scope = RenameScope.ALL_PROJECT_FILES
                    )
                    UplcTokenTypes.FIELD -> RenameConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        renameTokenTypes = setOf(UplcTokenTypes.FIELD),
                        fileType = UplcFileType,
                        indexId = UplcIdentifierIndex.NAME,
                        scope = RenameScope.ALL_PROJECT_FILES
                    )
                    UplcTokenTypes.IDENTIFIER -> RenameConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        renameTokenTypes = setOf(UplcTokenTypes.IDENTIFIER),
                        fileType = UplcFileType,
                        indexId = UplcIdentifierIndex.NAME,
                        scope = RenameScope.CURRENT_FILE
                    )
                    else -> null
                }
        }
    }

    private enum class RenameScope {
        CURRENT_FILE,
        ALL_PROJECT_FILES
    }

    private class AikenRenameReference(
        private val target: PsiElement,
        element: PsiElement,
        range: TextRange
    ) : PsiReferenceBase<PsiElement>(element, range, false) {
        val renameRange: TextRange
            get() = rangeInElement.shiftRight(element.textRange.startOffset)

        override fun resolve(): PsiElement = target

        override fun handleElementRename(newElementName: String): PsiElement {
            val project = element.project
            val file = element.containingFile ?: return element
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return element
            val range = renameRange

            document.replaceString(range.startOffset, range.endOffset, newElementName)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            return element
        }

        override fun getVariants(): Array<Any> = emptyArray()
    }
}
