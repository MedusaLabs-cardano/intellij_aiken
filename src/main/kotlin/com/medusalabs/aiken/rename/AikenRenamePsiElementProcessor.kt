package com.medusalabs.aiken.rename

import com.intellij.lexer.Lexer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.TokenType
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AikenImportIndex
import com.medusalabs.aiken.index.AikenIdentifierIndex
import com.medusalabs.aiken.index.UplcIdentifierIndex
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

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
        val type = target.node?.elementType ?: return emptyList()
        val name = target.text
        if (name.isEmpty()) return emptyList()

        val config = RenameConfig.fromElement(target, type) ?: return emptyList()
        val project = target.project
        val targetFiles = collectTargetFiles(project, target, config, name)
        if (targetFiles.isEmpty()) return emptyList()

        val limitInFile: TextRange? = config.limitFactory?.invoke(target, name)
        val psiManager = PsiManager.getInstance(project)
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val references = ArrayList<PsiReference>()

        for (vf in targetFiles) {
            val psiFile = psiManager.findFile(vf) ?: continue
            val document = psiDocumentManager.getDocument(psiFile) ?: continue
            val limit = if (vf == target.containingFile?.virtualFile) limitInFile else null
            val ranges = collectRenameRanges(document, config, name, limit) +
                collectImportedNameRanges(document, config, name)
            for (range in ranges.distinct()) {
                references.add(AikenRenameReference(target, psiFile, range))
            }
        }

        return references
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val target = if (element is PsiNamedElement) element else element.parent as? PsiNamedElement ?: return
        val oldName = target.text
        if (oldName == newName) return

        val type = target.node?.elementType ?: return
        val project = target.project

        val config = RenameConfig.fromElement(target, type) ?: return
        val targetFiles = collectTargetFiles(project, target, config, oldName)
        if (targetFiles.isEmpty()) return
        val limitInFile: TextRange? = config.limitFactory?.invoke(target, oldName)

        WriteCommandAction.runWriteCommandAction(project) {
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            for (vf in targetFiles) {
                val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                val document = psiDocumentManager.getDocument(psiFile) ?: continue
                val limit = if (vf == target.containingFile?.virtualFile) limitInFile else null
                val ranges = collectRenameRanges(document, config, oldName, limit) +
                    collectImportedNameRanges(document, config, oldName)
                if (ranges.isEmpty()) continue

                for (range in ranges.distinct().sortedByDescending { it.startOffset }) {
                    document.replaceString(range.startOffset, range.endOffset, newName)
                }
                psiDocumentManager.commitDocument(document)
            }
        }

        listener?.elementRenamed(target)
    }

    private fun collectTargetFiles(
        project: Project,
        element: PsiElement,
        config: RenameConfig,
        name: String
    ): Collection<VirtualFile> {
        val scope = GlobalSearchScope.allScope(project)
        val currentFile = element.containingFile?.virtualFile
        return when (config.scope) {
            RenameScope.CURRENT_FILE -> currentFile?.let { listOf(it) } ?: emptyList()
            RenameScope.ALL_PROJECT_FILES -> collectProjectFiles(project, config, name)
            RenameScope.IMPORTED_FILES -> {
                val imported = collectImportedFiles(project, config, name, scope).toMutableSet()
                if (currentFile != null) imported.add(currentFile)
                imported
            }
        }
    }

    private fun collectImportedFiles(
        project: Project,
        config: RenameConfig,
        name: String,
        scope: GlobalSearchScope
    ): Collection<VirtualFile> {
        if (config.fileType != AikenFileType) {
            return emptyList()
        }

        // Import index is content-based and doesn't enforce a minimum key length.
        return FileBasedIndex.getInstance().getContainingFiles(AikenImportIndex.NAME, name, scope)
    }

    private fun collectProjectFiles(project: Project, config: RenameConfig, name: String): Collection<VirtualFile> {
        val scope = GlobalSearchScope.allScope(project)

        // Our identifier index only stores names with length >= 2.
        if (name.length < 2) {
            return FileTypeIndex.getFiles(config.fileType, scope)
        }

        return FileBasedIndex.getInstance().getContainingFiles(config.indexId, name, scope)
            .filter { it.fileType == config.fileType }
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
        val limitFactory: ((PsiElement, String) -> TextRange?)? = null,
        val includeImportedNameRanges: Boolean = false
    ) {
        companion object {
            fun fromElement(element: PsiElement, type: com.intellij.psi.tree.IElementType): RenameConfig? =
                when (type) {
                    AikenTokenTypes.FUNCTION -> RenameConfig(
                        lexerFactory = { AikenLexing.createLexer() },
                        renameTokenTypes = setOf(AikenTokenTypes.FUNCTION),
                        fileType = AikenFileType,
                        indexId = AikenIdentifierIndex.NAME,
                        scope = RenameScope.IMPORTED_FILES,
                        includeImportedNameRanges = true
                    )
                    AikenTokenTypes.TYPE -> RenameConfig(
                        lexerFactory = { AikenLexing.createLexer() },
                        renameTokenTypes = setOf(AikenTokenTypes.TYPE),
                        fileType = AikenFileType,
                        indexId = AikenIdentifierIndex.NAME,
                        scope = RenameScope.IMPORTED_FILES
                    )
                    AikenTokenTypes.IDENTIFIER -> {
                        val isConst = isConstDeclaration(element)
                        if (isConst) {
                            RenameConfig(
                                lexerFactory = { AikenLexing.createLexer() },
                                renameTokenTypes = setOf(AikenTokenTypes.IDENTIFIER),
                                fileType = AikenFileType,
                                indexId = AikenIdentifierIndex.NAME,
                                scope = RenameScope.IMPORTED_FILES
                            )
                        } else {
                            RenameConfig(
                                lexerFactory = { AikenLexing.createLexer() },
                                renameTokenTypes = setOf(AikenTokenTypes.IDENTIFIER, AikenTokenTypes.FIELD),
                                fileType = AikenFileType,
                                indexId = AikenIdentifierIndex.NAME,
                                scope = RenameScope.CURRENT_FILE,
                                limitFactory = ::computeAikenVariableScope
                            )
                        }
                    }
                    AikenTokenTypes.FIELD -> RenameConfig(
                        lexerFactory = { AikenLexing.createLexer() },
                        renameTokenTypes = setOf(AikenTokenTypes.IDENTIFIER, AikenTokenTypes.FIELD),
                        fileType = AikenFileType,
                        indexId = AikenIdentifierIndex.NAME,
                        scope = RenameScope.CURRENT_FILE,
                        limitFactory = ::computeAikenVariableScope
                    )
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

            private fun computeAikenVariableScope(element: PsiElement, oldName: String): TextRange? {
                val psiFile = element.containingFile ?: return null
                val document = PsiDocumentManager.getInstance(element.project).getDocument(psiFile) ?: return null
                val text = document.charsSequence
                val caretOffset = element.textRange.startOffset
                return findAikenVariableScope(document, text, oldName, caretOffset)
            }

            private data class ScopeCandidate(val declarationOffset: Int, val scope: TextRange)

            private fun findAikenVariableScope(
                document: Document,
                text: CharSequence,
                name: String,
                caretOffset: Int
            ): TextRange? {
                val bracePairs = collectScopeBracePairs(document, text)
                val bracePairsByStart = bracePairs.associate { it.openOffset to it.closeOffset }
                val bracePairsByEnd = bracePairs.associate { it.closeOffset to it.openOffset }

                val candidates = ArrayList<ScopeCandidate>()
                val lexer = AikenLexing.createLexer()
                lexer.start(text)

                val openBraces = ArrayList<Int>()
                var collectingBindings = false
                var collectingParams = false
                var afterParams = false
                var parenDepth = 0
                var pendingParamNames = ArrayList<Pair<Int, String>>()
                while (lexer.tokenType != null) {
                    val t = lexer.tokenType
                    val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

                    when (t) {
                        AikenTokenTypes.LBRACE -> {
                            if (bracePairsByStart.containsKey(lexer.tokenStart)) {
                                openBraces.add(lexer.tokenStart)
                            }
                        }
                        AikenTokenTypes.RBRACE -> {
                            val open = bracePairsByEnd[lexer.tokenEnd]
                            if (open != null) {
                                val idx = openBraces.lastIndexOf(open)
                                if (idx != -1) openBraces.removeAt(idx)
                            }
                        }
                    }

                    if (collectingParams) {
                        when (t) {
                            AikenTokenTypes.LPAREN -> parenDepth += 1
                            AikenTokenTypes.RPAREN -> {
                                parenDepth -= 1
                                if (parenDepth <= 0) {
                                    collectingParams = false
                                    afterParams = true
                                }
                            }
                            AikenTokenTypes.IDENTIFIER,
                            AikenTokenTypes.FIELD -> pendingParamNames.add(lexer.tokenStart to tokenText)
                        }
                        lexer.advance()
                        continue
                    }

                    if (afterParams) {
                        if (t == AikenTokenTypes.LBRACE && bracePairsByStart.containsKey(lexer.tokenStart)) {
                            val start = lexer.tokenStart
                            val end = bracePairsByStart[start] ?: text.length
                            val scope = TextRange(start, end)
                            for ((offset, paramName) in pendingParamNames) {
                                if (paramName == name) {
                                    candidates.add(ScopeCandidate(offset, scope))
                                }
                            }
                            pendingParamNames = ArrayList()
                            afterParams = false
                        }
                        lexer.advance()
                        continue
                    }

                    if (collectingBindings) {
                        when {
                            t == TokenType.WHITE_SPACE -> {}
                            t == AikenTokenTypes.OPERATOR && tokenText == "=" -> collectingBindings = false
                            t == AikenTokenTypes.IDENTIFIER || t == AikenTokenTypes.FIELD -> {
                                if (tokenText == name) {
                                    val scope =
                                        openBraces.lastOrNull()?.let { start ->
                                            val end = bracePairsByStart[start] ?: text.length
                                            TextRange(start, end)
                                        } ?: TextRange(0, text.length)
                                    candidates.add(ScopeCandidate(lexer.tokenStart, scope))
                                }
                            }
                        }
                        lexer.advance()
                        continue
                    }

                    if (t == AikenTokenTypes.KEYWORD && (tokenText == "let" || tokenText == "expect")) {
                        collectingBindings = true
                        lexer.advance()
                        continue
                    }

                    if (t == AikenTokenTypes.KEYWORD &&
                        (tokenText == "fn" || tokenText == "test" || tokenText == "bench" || tokenText == "validator")
                    ) {
                        pendingParamNames = ArrayList()
                        parenDepth = 0
                        collectingParams = false
                        afterParams = false

                        lexer.advance()
                        while (lexer.tokenType != null) {
                            val tt = lexer.tokenType
                            val tText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                            if (tt == TokenType.WHITE_SPACE) {
                                if (tText.contains('\n')) break
                                lexer.advance()
                                continue
                            }
                            if (tt == AikenTokenTypes.LPAREN) {
                                collectingParams = true
                                parenDepth = 0
                                break
                            }
                            if (tt == AikenTokenTypes.LBRACE) {
                                // No parameter list for this declaration.
                                break
                            }
                            lexer.advance()
                        }
                        continue
                    }

                    lexer.advance()
                }

                val matchingDeclaration =
                    candidates
                        .asSequence()
                        .filter {
                            it.declarationOffset <= caretOffset &&
                                (it.scope.containsOffset(caretOffset) || it.declarationOffset == caretOffset)
                        }
                        .maxByOrNull { it.declarationOffset }
                if (matchingDeclaration != null) return matchingDeclaration.scope

                return bracePairs
                    .asSequence()
                    .filter { (start, end) -> start < caretOffset && end > caretOffset }
                    .minByOrNull { (start, end) -> end - start }
                    ?.let { (start, end) -> TextRange(start, end) }
            }

            private data class ScopeBracePair(val openOffset: Int, val closeOffset: Int)

            private fun collectScopeBracePairs(document: Document, text: CharSequence): List<ScopeBracePair> {
                val pairs = ArrayList<ScopeBracePair>()
                val stack = ArrayDeque<ScopeOpen>()

                var i = 0
                var inLineComment = false
                var inString = false

                while (i < text.length) {
                    val ch = text[i]

                    if (inLineComment) {
                        if (ch == '\n' || ch == '\r') inLineComment = false
                        i++
                        continue
                    }

                    if (inString) {
                        if (ch == '\\' && i + 1 < text.length) {
                            i += 2
                            continue
                        }
                        if (ch == '"') inString = false
                        i++
                        continue
                    }

                    if (ch == '/' && i + 1 < text.length && text[i + 1] == '/') {
                        inLineComment = true
                        i += 2
                        continue
                    }

                    if (ch == '"') {
                        inString = true
                        i++
                        continue
                    }

                    when (ch) {
                        '{' -> {
                            val isScope = isScopeBrace(document, text, i)
                            stack.addLast(ScopeOpen(i, isScope))
                        }
                        '}' -> {
                            if (stack.isNotEmpty()) {
                                val open = stack.removeLast()
                                if (open.isScope) {
                                    pairs.add(ScopeBracePair(open.offset, i + 1))
                                }
                            }
                        }
                    }

                    i++
                }

                return pairs
            }

            private data class ScopeOpen(val offset: Int, val isScope: Boolean)

            private fun isScopeBrace(document: Document, text: CharSequence, openOffset: Int): Boolean {
                val line = document.getLineNumber(openOffset)
                val lineStart = document.getLineStartOffset(line)
                val prefix = text.subSequence(lineStart, openOffset).toString()

                if (prefix.contains("->")) return true
                if (containsWord(prefix, "if") || containsWord(prefix, "else")) return true
                if (containsWord(prefix, "when") && containsWord(prefix, "is")) return true
                if (containsAnyWord(prefix, setOf("fn", "test", "bench", "validator", "type"))) return true
                if (containsAnyWord(prefix, setOf("let", "const", "expect")) && !prefix.contains("->")) return false

                if (prefix.contains(")")) {
                    val startLine = (line - 20).coerceAtLeast(0)
                    for (i in line downTo startLine) {
                        val start = document.getLineStartOffset(i)
                        val end = document.getLineEndOffset(i)
                        val raw = text.subSequence(start, end).toString().trim()
                        if (raw.isEmpty() || raw.startsWith("//")) continue
                        if (containsAnyWord(raw, setOf("let", "const", "expect", "when", "if", "else"))) return false
                        if (containsAnyWord(raw, setOf("fn", "test", "bench", "validator", "type"))) return true
                    }
                }

                return false
            }

            private fun containsAnyWord(text: String, words: Set<String>): Boolean =
                words.any { containsWord(text, it) }

            private fun containsWord(text: String, word: String): Boolean {
                if (text.length < word.length) return false
                var i = 0
                while (i + word.length <= text.length) {
                    var j = 0
                    while (j < word.length && text[i + j] == word[j]) j++
                    if (j == word.length) {
                        val beforeOk = i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_')
                        val afterIndex = i + word.length
                        val afterOk = afterIndex >= text.length || !(text[afterIndex].isLetterOrDigit() || text[afterIndex] == '_')
                        if (beforeOk && afterOk) return true
                    }
                    i++
                }
                return false
            }

            private fun isConstDeclaration(element: PsiElement): Boolean {
                val psiFile = element.containingFile ?: return false
                if (psiFile.fileType != AikenFileType) return false
                val document = PsiDocumentManager.getInstance(element.project).getDocument(psiFile) ?: return false

                val targetRange = element.textRange
                val text = document.charsSequence
                val lexer = AikenLexing.createLexer()
                lexer.start(text)

                var prevType: com.intellij.psi.tree.IElementType? = null
                var prevText: String? = null
                var braceDepth = 0
                while (lexer.tokenType != null) {
                    when (lexer.tokenType) {
                        AikenTokenTypes.LBRACE -> braceDepth += 1
                        AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    }
                    val start = lexer.tokenStart
                    val end = lexer.tokenEnd
                    if (start == targetRange.startOffset && end == targetRange.endOffset) {
                        return braceDepth == 0 && prevType == AikenTokenTypes.KEYWORD && prevText == "const"
                    }
                    val t = lexer.tokenType
                    if (t != null && t != TokenType.WHITE_SPACE && t != AikenTokenTypes.COMMENT) {
                        prevType = t
                        prevText = text.subSequence(start, end).toString()
                    }
                    lexer.advance()
                }
                return false
            }
        }
    }

    private enum class RenameScope {
        CURRENT_FILE,
        ALL_PROJECT_FILES,
        IMPORTED_FILES
    }

    private class AikenRenameReference(
        private val target: PsiElement,
        element: PsiElement,
        range: TextRange
    ) : PsiReferenceBase<PsiElement>(element, range, false) {
        override fun resolve(): PsiElement = target
        override fun getVariants(): Array<Any> = emptyArray()
    }

    private fun collectImportedNameRanges(document: Document, config: RenameConfig, oldName: String): List<TextRange> {
        if (!config.includeImportedNameRanges) return emptyList()
        if (config.fileType != AikenFileType) return emptyList()

        val text = document.charsSequence
        val statements = AikenUseStatementParser.parse(text)
        val ranges = ArrayList<TextRange>()
        for (stmt in statements) {
            for (item in stmt.items) {
                if (item.name == oldName) {
                    ranges.add(item.nameRange)
                }
            }
        }
        return ranges
    }
}
