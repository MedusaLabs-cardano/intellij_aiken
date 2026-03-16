package com.medusalabs.aiken.usages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.index.UPLC_IDENTIFIER_INDEX_NAME
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import com.medusalabs.aiken.navigation.AikenDeclarationResolver
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.search.LegacyIndexedSearchScope

class AikenFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        val fileType = element.containingFile?.fileType ?: return false
        return !(fileType != AikenFileType && fileType != UplcFileType)
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        AikenFindUsagesHandler(
            element as? PsiNamedElement ?: (element.parent as? PsiNamedElement ?: element)
        )
}

private class AikenFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {
    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions
    ): Boolean {
        return runReadAction {
            val resolved = AikenDeclarationResolver.resolve(element) ?: element
            val name = resolved.text
            if (name.isEmpty()) return@runReadAction true
            val declarationRange = resolved.textRange
            val declarationFile = resolved.containingFile?.virtualFile
            if (!processor.process(UsageInfo(resolved))) return@runReadAction false

            if (resolved.containingFile?.fileType == AikenFileType) {
                return@runReadAction super.processElementUsages(resolved, processor, options)
            } else {
                val type = resolved.node?.elementType ?: return@runReadAction true
                val config = UsageConfig.fromElement(type) ?: return@runReadAction true
                val project = resolved.project
                val effectiveSearchScope =
                    LegacyIndexedSearchScope.create(project, AikenSearchScopes.forElement(resolved), options.searchScope)
                val limitInFile: TextRange? = config.limitFactory?.invoke(resolved, name)
                val targetFiles = collectTargetFiles(project, resolved, config, name, effectiveSearchScope)
                if (targetFiles.isEmpty()) return@runReadAction true
                val psiManager = PsiManager.getInstance(project)
                val psiDocumentManager = PsiDocumentManager.getInstance(project)
                for (vf in targetFiles) {
                    val psiFile = psiManager.findFile(vf) ?: continue
                    val document = psiDocumentManager.getDocument(psiFile) ?: continue
                    val limit = if (vf == resolved.containingFile?.virtualFile) limitInFile else null
                    val ranges = collectRanges(document, config, name, limit)
                    for (range in ranges.distinct()) {
                        if (!effectiveSearchScope.contains(psiFile, range)) continue
                        if (vf == declarationFile && declarationRange == range) continue
                        if (!processor.process(UsageInfo(psiFile, range.startOffset, range.endOffset))) return@runReadAction false
                    }
                }
            }

            true
        }
    }

    private fun collectTargetFiles(
        project: Project,
        element: PsiElement,
        config: UsageConfig,
        name: String,
        searchScope: LegacyIndexedSearchScope
    ): Collection<VirtualFile> {
        val currentFile = element.containingFile?.virtualFile
        val files =
            when (config.scope) {
                UsageScope.CURRENT_FILE -> currentFile?.let { listOf(it) } ?: emptyList()
                UsageScope.ALL_PROJECT_FILES -> collectProjectFiles(project, config, name, searchScope.indexScope)
        }
        return files.filter(searchScope::contains)
    }

    private fun collectProjectFiles(
        project: Project,
        config: UsageConfig,
        name: String,
        scope: GlobalSearchScope
    ): Collection<VirtualFile> {
        if (DumbService.getInstance(project).isDumb) return emptyList()
        return try {
            if (name.length < 2) return FileTypeIndex.getFiles(config.fileType, scope)
            FileBasedIndex.getInstance().getContainingFiles(config.indexId, name, scope)
                .filter { it.fileType == config.fileType }
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun collectRanges(
        document: Document,
        config: UsageConfig,
        name: String,
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
            if (tokenType != null && config.tokenTypes.contains(tokenType)) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                if (start >= startLimit && end <= endLimit) {
                    if (text.subSequence(start, end).toString() == name) {
                        ranges.add(TextRange(start, end))
                    }
                }
            }
            lexer.advance()
        }
        return ranges
    }

    private data class UsageConfig(
        val lexerFactory: () -> Lexer,
        val tokenTypes: Set<com.intellij.psi.tree.IElementType>,
        val fileType: com.intellij.openapi.fileTypes.FileType,
        val indexId: ID<String, Int>,
        val scope: UsageScope,
        val limitFactory: ((PsiElement, String) -> TextRange?)? = null
    ) {
        companion object {
            fun fromElement(type: com.intellij.psi.tree.IElementType): UsageConfig? =
                when (type) {
                    UplcTokenTypes.FUNCTION -> UsageConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        tokenTypes = setOf(UplcTokenTypes.FUNCTION),
                        fileType = UplcFileType,
                        indexId = UPLC_IDENTIFIER_INDEX_NAME,
                        scope = UsageScope.ALL_PROJECT_FILES
                    )
                    UplcTokenTypes.TYPE -> UsageConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        tokenTypes = setOf(UplcTokenTypes.TYPE),
                        fileType = UplcFileType,
                        indexId = UPLC_IDENTIFIER_INDEX_NAME,
                        scope = UsageScope.ALL_PROJECT_FILES
                    )
                    UplcTokenTypes.FIELD -> UsageConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        tokenTypes = setOf(UplcTokenTypes.FIELD),
                        fileType = UplcFileType,
                        indexId = UPLC_IDENTIFIER_INDEX_NAME,
                        scope = UsageScope.ALL_PROJECT_FILES
                    )
                    UplcTokenTypes.IDENTIFIER -> UsageConfig(
                        lexerFactory = { UplcLexing.createLexer() },
                        tokenTypes = setOf(UplcTokenTypes.IDENTIFIER),
                        fileType = UplcFileType,
                        indexId = UPLC_IDENTIFIER_INDEX_NAME,
                        scope = UsageScope.CURRENT_FILE
                    )
                    else -> null
                }
        }
    }

    private enum class UsageScope {
        CURRENT_FILE,
        ALL_PROJECT_FILES
    }
}
