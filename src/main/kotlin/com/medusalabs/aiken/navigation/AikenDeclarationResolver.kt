package com.medusalabs.aiken.navigation

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AikenIdentifierIndex
import com.medusalabs.aiken.lang.AikenFileType

object AikenDeclarationResolver {
    fun resolve(element: PsiElement): PsiElement? {
        val psiFile = element.containingFile ?: return null
        if (psiFile.fileType != AikenFileType) return null
        val type = element.node?.elementType ?: return null
        val name = element.text
        if (name.isBlank()) return null

        val project = element.project
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        return when (type) {
            AikenTokenTypes.IDENTIFIER,
            AikenTokenTypes.FIELD -> resolveLocalOrGlobal(element, document, name)
            AikenTokenTypes.FUNCTION ->
                resolveImportAlias(psiFile, name) ?: resolveGlobal(element, name, setOf(DeclKind.FUNCTION))
            AikenTokenTypes.TYPE ->
                resolveImportAlias(psiFile, name) ?: resolveGlobal(element, name, setOf(DeclKind.TYPE, DeclKind.CONSTRUCTOR))
            else -> null
        }
    }

    private enum class DeclKind {
        FUNCTION,
        TYPE,
        CONST,
        CONSTRUCTOR
    }

    private fun resolveLocalOrGlobal(element: PsiElement, document: Document, name: String): PsiElement? {
        val caretOffset = element.textRange.startOffset
        val psiFile = element.containingFile ?: return null
        val localOffset = findLocalDeclarationOffset(document, document.charsSequence, name, caretOffset)
        if (localOffset != null) {
            return resolveNamedElementAt(psiFile, localOffset)
        }
        resolveImportAlias(psiFile, name)?.let { return it }
        val targets = findGlobalTargets(element.project, name, setOf(DeclKind.CONST, DeclKind.FUNCTION))
        return preferSameFile(element, targets) ?: targets.firstOrNull()
    }

    private fun resolveImportAlias(psiFile: com.intellij.psi.PsiFile, name: String): PsiElement? {
        if (name.isBlank()) return null
        if (psiFile.fileType != AikenFileType) return null

        val useStatements = AikenUseStatementParser.parse(psiFile.text)
        val aliasOffset =
            useStatements
                .asSequence()
                .flatMap { it.items.asSequence() }
                .mapNotNull { item ->
                    val aliasRange = item.aliasRange ?: return@mapNotNull null
                    if (item.alias == name) aliasRange.startOffset else null
                }
                .firstOrNull()
                ?: return null

        return resolveNamedElementAt(psiFile, aliasOffset)
    }

    private fun resolveGlobal(element: PsiElement, name: String, kinds: Set<DeclKind>): PsiElement? {
        val targets = findGlobalTargets(element.project, name, kinds)
        return preferSameFile(element, targets) ?: targets.firstOrNull()
    }

    private fun preferSameFile(element: PsiElement, targets: List<PsiElement>): PsiElement? {
        val vf = element.containingFile?.virtualFile ?: return null
        return targets.firstOrNull { it.containingFile?.virtualFile == vf }
    }

    private fun findGlobalTargets(project: Project, name: String, kinds: Set<DeclKind>): List<PsiElement> {
        if (DumbService.getInstance(project).isDumb) return emptyList()
        val files = collectCandidateFiles(project, name)
        if (files.isEmpty()) return emptyList()

        val psiManager = PsiManager.getInstance(project)
        val targets = ArrayList<PsiElement>()

        for (vf in files) {
            val psiFile = psiManager.findFile(vf) ?: continue
            if (psiFile.fileType != AikenFileType) continue
            val text = psiFile.text
            val offsets = findGlobalDeclarationOffsets(text, name, kinds)
            for (offset in offsets) {
                val element = resolveNamedElementAt(psiFile, offset)
                if (element != null) targets.add(element)
            }
        }

        return targets
    }

    private fun collectCandidateFiles(project: Project, name: String): Collection<VirtualFile> {
        if (DumbService.getInstance(project).isDumb) return emptyList()
        val scope = GlobalSearchScope.allScope(project)
        return try {
            if (name.length < 2) {
                FileTypeIndex.getFiles(AikenFileType, scope)
            } else {
                FileBasedIndex.getInstance().getContainingFiles(AikenIdentifierIndex.NAME, name, scope)
                    .filter { it.fileType == AikenFileType }
            }
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun findGlobalDeclarationOffsets(text: CharSequence, name: String, kinds: Set<DeclKind>): List<Int> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val results = ArrayList<Int>()
        var braceDepth = 0
        var expected: DeclKind? = null
        var pendingTypeBody = false
        var typeBodyDepth: Int? = null

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }

            if (typeBodyDepth != null && braceDepth < typeBodyDepth) {
                typeBodyDepth = null
            }

            if (pendingTypeBody) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.LBRACE -> {
                        typeBodyDepth = braceDepth
                        pendingTypeBody = false
                        lexer.advance()
                        continue
                    }
                    else -> pendingTypeBody = false
                }
            }

            if (typeBodyDepth != null &&
                kinds.contains(DeclKind.CONSTRUCTOR) &&
                braceDepth == typeBodyDepth &&
                tokenType == AikenTokenTypes.TYPE &&
                isAtLogicalLineStart(text, lexer.tokenStart)
            ) {
                val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                if (word == name) {
                    results.add(lexer.tokenStart)
                }
            }

            if (expected != null) {
                when (tokenType) {
                    TokenType.WHITE_SPACE,
                    AikenTokenTypes.WHITESPACE,
                    AikenTokenTypes.COMMENT -> {
                        lexer.advance()
                        continue
                    }
                    AikenTokenTypes.IDENTIFIER,
                    AikenTokenTypes.FUNCTION,
                    AikenTokenTypes.TYPE -> {
                        val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                        if (word == name && kinds.contains(expected)) {
                            results.add(lexer.tokenStart)
                        }
                        if (expected == DeclKind.TYPE) {
                            pendingTypeBody = true
                        }
                        expected = null
                        lexer.advance()
                        continue
                    }
                    else -> {
                        expected = null
                    }
                }
            }

            if (tokenType == AikenTokenTypes.KEYWORD) {
                val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                expected =
                    when (word) {
                        "fn",
                        "test",
                        "bench",
                        "validator" -> DeclKind.FUNCTION
                        "type" -> DeclKind.TYPE
                        "const" -> if (braceDepth == 0) DeclKind.CONST else null
                        else -> null
                    }
            }

            lexer.advance()
        }

        return results
    }

    private fun isAtLogicalLineStart(text: CharSequence, offset: Int): Boolean {
        var index = offset - 1
        while (index >= 0) {
            val ch = text[index]
            if (ch == '\n' || ch == '\r') return true
            if (ch != ' ' && ch != '\t') return false
            index--
        }
        return true
    }

    private fun resolveNamedElementAt(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? {
        val leaf = psiFile.findElementAt(offset) ?: return null
        val parent = leaf.parent
        return if (parent is com.intellij.psi.PsiNamedElement) parent else leaf
    }

    private data class ScopeCandidate(val declarationOffset: Int, val scope: TextRange)

    private fun findLocalDeclarationOffset(
        document: Document,
        text: CharSequence,
        name: String,
        caretOffset: Int
    ): Int? {
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
                        break
                    }
                    lexer.advance()
                }
                continue
            }

            lexer.advance()
        }

        val match =
            candidates
                .asSequence()
                .filter {
                    it.declarationOffset <= caretOffset &&
                        (it.scope.containsOffset(caretOffset) || it.declarationOffset == caretOffset)
                }
                .maxByOrNull { it.declarationOffset }

        return match?.declarationOffset
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
}
