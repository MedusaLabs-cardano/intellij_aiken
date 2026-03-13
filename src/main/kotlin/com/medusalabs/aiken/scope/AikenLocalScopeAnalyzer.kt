package com.medusalabs.aiken.scope

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenFileType

object AikenLocalScopeAnalyzer {
    data class VisibleBinding(
        val name: String,
        val declarationOffset: Int
    )

    fun findDeclarationOffset(document: Document, name: String, caretOffset: Int): Int? =
        analyze(document, name, caretOffset).matchingDeclaration?.declarationOffset

    fun findVariableScope(element: PsiElement, name: String = element.text): TextRange? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(element.project).getDocument(psiFile) ?: return null
        return findVariableScope(document, name, element.textRange.startOffset)
    }

    fun findVariableScope(document: Document, name: String, caretOffset: Int): TextRange? {
        val analysis = analyze(document, name, caretOffset)
        return analysis.matchingDeclaration?.scope ?: analysis.innermostScope
    }

    fun collectVisibleBindings(element: PsiElement): List<VisibleBinding> {
        val psiFile = element.containingFile ?: return emptyList()
        val document = PsiDocumentManager.getInstance(element.project).getDocument(psiFile) ?: return emptyList()
        return collectVisibleBindings(document, element.textRange.startOffset)
    }

    fun collectVisibleBindings(document: Document, caretOffset: Int): List<VisibleBinding> {
        val text = document.charsSequence
        val bracePairs = collectScopeBracePairs(document, text)
        val candidates = collectScopeCandidates(text, bracePairs)

        return candidates
            .asSequence()
            .filter { candidate ->
                candidate.declarationOffset <= caretOffset &&
                    (candidate.scope.containsOffset(caretOffset) || candidate.declarationOffset == caretOffset)
            }
            .groupBy { it.name }
            .values
            .mapNotNull { entries -> entries.maxByOrNull { it.declarationOffset } }
            .sortedByDescending { it.declarationOffset }
            .map { candidate -> VisibleBinding(candidate.name, candidate.declarationOffset) }
            .toList()
    }

    fun isConstDeclaration(element: PsiElement): Boolean {
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
            val tokenType = lexer.tokenType
            if (tokenType != null && tokenType != TokenType.WHITE_SPACE && tokenType != AikenTokenTypes.COMMENT) {
                prevType = tokenType
                prevText = text.subSequence(start, end).toString()
            }
            lexer.advance()
        }

        return false
    }

    private data class ScopeCandidate(val name: String, val declarationOffset: Int, val scope: TextRange)

    private data class ScopeBracePair(val openOffset: Int, val closeOffset: Int)

    private data class ScopeOpen(val offset: Int, val isScope: Boolean)

    private data class Analysis(
        val matchingDeclaration: ScopeCandidate?,
        val innermostScope: TextRange?
    )

    private fun analyze(document: Document, name: String, caretOffset: Int): Analysis {
        val text = document.charsSequence
        val bracePairs = collectScopeBracePairs(document, text)
        val candidates = collectScopeCandidates(text, bracePairs)

        val matchingDeclaration =
            candidates
                .asSequence()
                .filter { candidate ->
                    candidate.name == name &&
                        candidate.declarationOffset <= caretOffset &&
                        (candidate.scope.containsOffset(caretOffset) || candidate.declarationOffset == caretOffset)
                }
                .maxByOrNull { it.declarationOffset }

        val innermostScope =
            bracePairs
                .asSequence()
                .filter { (start, end) -> start < caretOffset && end > caretOffset }
                .minByOrNull { (start, end) -> end - start }
                ?.let { (start, end) -> TextRange(start, end) }

        return Analysis(matchingDeclaration = matchingDeclaration, innermostScope = innermostScope)
    }

    private fun collectScopeCandidates(text: CharSequence, bracePairs: List<ScopeBracePair>): List<ScopeCandidate> {
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
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            when (tokenType) {
                AikenTokenTypes.LBRACE -> {
                    if (bracePairsByStart.containsKey(lexer.tokenStart)) {
                        openBraces.add(lexer.tokenStart)
                    }
                }
                AikenTokenTypes.RBRACE -> {
                    val open = bracePairsByEnd[lexer.tokenEnd]
                    if (open != null) {
                        val index = openBraces.lastIndexOf(open)
                        if (index != -1) openBraces.removeAt(index)
                    }
                }
            }

            if (collectingParams) {
                when (tokenType) {
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
                if (tokenType == AikenTokenTypes.LBRACE && bracePairsByStart.containsKey(lexer.tokenStart)) {
                    val start = lexer.tokenStart
                    val end = bracePairsByStart[start] ?: text.length
                    val scope = TextRange(start, end)
                    for ((offset, paramName) in pendingParamNames) {
                        candidates.add(ScopeCandidate(paramName, offset, scope))
                    }
                    pendingParamNames = ArrayList()
                    afterParams = false
                }
                lexer.advance()
                continue
            }

            if (collectingBindings) {
                when {
                    tokenType == TokenType.WHITE_SPACE -> {}
                    tokenType == AikenTokenTypes.OPERATOR && tokenText == "=" -> collectingBindings = false
                    tokenType == AikenTokenTypes.IDENTIFIER || tokenType == AikenTokenTypes.FIELD -> {
                        val scope =
                            openBraces.lastOrNull()?.let { start ->
                                val end = bracePairsByStart[start] ?: text.length
                                TextRange(start, end)
                            } ?: TextRange(0, text.length)
                        candidates.add(ScopeCandidate(tokenText, lexer.tokenStart, scope))
                    }
                }
                lexer.advance()
                continue
            }

            if (tokenType == AikenTokenTypes.KEYWORD && (tokenText == "let" || tokenText == "expect")) {
                collectingBindings = true
                lexer.advance()
                continue
            }

            if (tokenType == AikenTokenTypes.KEYWORD &&
                (tokenText == "fn" || tokenText == "test" || tokenText == "bench" || tokenText == "validator")
            ) {
                pendingParamNames = ArrayList()
                parenDepth = 0
                collectingParams = false
                afterParams = false

                lexer.advance()
                while (lexer.tokenType != null) {
                    val nestedType = lexer.tokenType
                    val nestedText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    if (nestedType == TokenType.WHITE_SPACE) {
                        if (nestedText.contains('\n')) break
                        lexer.advance()
                        continue
                    }
                    if (nestedType == AikenTokenTypes.LPAREN) {
                        collectingParams = true
                        parenDepth = 0
                        break
                    }
                    if (nestedType == AikenTokenTypes.LBRACE) {
                        break
                    }
                    lexer.advance()
                }
                continue
            }

            lexer.advance()
        }

        return candidates
    }

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
