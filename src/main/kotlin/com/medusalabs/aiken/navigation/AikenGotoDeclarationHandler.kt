package com.medusalabs.aiken.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.index.AikenIdentifierIndex
import com.medusalabs.aiken.lang.AikenFileType

class AikenGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val psiFile = element.containingFile ?: return null
        if (psiFile.fileType != AikenFileType) return null

        val elementType = element.node?.elementType ?: return null
        if (elementType !in setOf(
                AikenTokenTypes.IDENTIFIER,
                AikenTokenTypes.FIELD,
                AikenTokenTypes.FUNCTION,
                AikenTokenTypes.TYPE
            )
        ) {
            return null
        }

        val name = element.text
        if (name.isBlank()) return null

        val targets =
            when (elementType) {
                AikenTokenTypes.IDENTIFIER,
                AikenTokenTypes.FIELD ->
                    findLocalOrGlobalTargets(element, name, editor)
                AikenTokenTypes.FUNCTION ->
                    findGlobalTargets(element.project, name, setOf(DeclKind.FUNCTION))
                AikenTokenTypes.TYPE ->
                    findGlobalTargets(element.project, name, setOf(DeclKind.TYPE))
                else -> emptyList()
            }

        if (targets.isEmpty()) return null
        return qualifyTargets(name, targets).toTypedArray()
    }

    private enum class DeclKind {
        FUNCTION,
        TYPE,
        CONST
    }

    private fun findLocalOrGlobalTargets(element: PsiElement, name: String, editor: Editor): List<PsiElement> {
        val document = editor.document
        val caretOffset = element.textRange.startOffset
        val psiFile = element.containingFile ?: return emptyList()

        val localOffset = findLocalDeclarationOffset(document, document.charsSequence, name, caretOffset)
        if (localOffset != null) {
            val target = resolveNamedElementAt(psiFile, localOffset)
            return listOfNotNull(target)
        }

        return findGlobalTargets(element.project, name, setOf(DeclKind.CONST, DeclKind.FUNCTION))
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
        val files =
            if (name.length < 2) {
                FileTypeIndex.getFiles(AikenFileType, scope)
            } else {
                FileBasedIndex.getInstance().getContainingFiles(AikenIdentifierIndex.NAME, name, scope)
                    .filter { it.fileType == AikenFileType }
            }
        return files
    }

    /**
     * Decorate targets with qualifier so popup shows e.g. `list.length` instead of many `length`.
     */
    private fun qualifyTargets(name: String, targets: List<PsiElement>): List<PsiElement> {
        if (targets.size <= 1) return targets
        return targets.map { target ->
            val qualifier = buildQualifier(target)
            if (qualifier.isNullOrBlank()) target else QualifiedTarget(target, qualifier, name)
        }
    }

    private fun buildQualifier(target: PsiElement): String? {
        val file = target.containingFile ?: return null
        val vf = file.virtualFile
        val fileQualifier = vf?.nameWithoutExtension
        if (!fileQualifier.isNullOrBlank()) return fileQualifier
        return file.name.substringBeforeLast('.').ifBlank { null }
    }

    private class QualifiedTarget(
        private val delegate: PsiElement,
        private val qualifier: String,
        private val name: String
    ) : FakePsiElement(), NavigationItem {
        override fun getParent(): PsiElement? = delegate.parent
        override fun getName(): String = "$qualifier.$name"
        override fun getPresentation(): ItemPresentation? {
            val presentation = (delegate as? NavigationItem)?.presentation
            return presentation?.let {
                object : ItemPresentation by it {
                    override fun getPresentableText(): String = "$qualifier.$name"
                }
            }
        }
        override fun navigate(requestFocus: Boolean) =
            (delegate as? NavigationItem)?.navigate(requestFocus) ?: Unit

        override fun canNavigate(): Boolean = (delegate as? NavigationItem)?.canNavigate() == true
        override fun canNavigateToSource(): Boolean = (delegate as? NavigationItem)?.canNavigateToSource() == true
    }

    private fun findGlobalDeclarationOffsets(text: CharSequence, name: String, kinds: Set<DeclKind>): List<Int> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val results = ArrayList<Int>()
        var braceDepth = 0
        var expected: DeclKind? = null

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType

            when (tokenType) {
                AikenTokenTypes.LBRACE -> braceDepth += 1
                AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
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
