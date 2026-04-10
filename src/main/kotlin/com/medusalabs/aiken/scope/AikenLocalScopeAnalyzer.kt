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
        var bindingPatternStart = -1
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
                    tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.COMMENT || tokenType == AikenTokenTypes.WHITESPACE -> {}
                    tokenType == AikenTokenTypes.OPERATOR && tokenText == "=" -> {
                        val scope =
                            openBraces.lastOrNull()?.let { start ->
                                val end = bracePairsByStart[start] ?: text.length
                                TextRange(start, end)
                            } ?: TextRange(0, text.length)
                        if (bindingPatternStart in 0..lexer.tokenStart) {
                            val patternEnd = topLevelBindingPatternEnd(text, bindingPatternStart, lexer.tokenStart)
                            val patternBindings = extractPatternBindings(text, bindingPatternStart, patternEnd)
                            for ((name, declarationOffset) in patternBindings) {
                                candidates.add(ScopeCandidate(name, declarationOffset, scope))
                            }
                        }
                        collectingBindings = false
                        bindingPatternStart = -1
                    }
                }
                lexer.advance()
                continue
            }

            if (tokenType == AikenTokenTypes.KEYWORD && (tokenText == "let" || tokenText == "expect")) {
                collectingBindings = true
                bindingPatternStart = lexer.tokenEnd
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

        candidates += collectWhenPatternCandidates(text)

        return candidates
    }

    private fun collectWhenPatternCandidates(text: CharSequence): List<ScopeCandidate> {
        val result = ArrayList<ScopeCandidate>()
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (tokenType != AikenTokenTypes.KEYWORD || tokenText != "when") {
                lexer.advance()
                continue
            }

            val whenStart = lexer.tokenStart
            val whenEnd = lexer.tokenEnd
            val whenBody = findWhenBody(text, whenEnd)
            if (whenBody == null) {
                lexer.advance()
                continue
            }

            var cursor = whenBody.openBrace + 1
            while (cursor < whenBody.closeBrace) {
                val arrowOffset = findTopLevelArrowInRange(text, cursor, whenBody.closeBrace) ?: break
                val expressionStart = skipWhitespaceForward(text, arrowOffset + 2)
                if (expressionStart >= whenBody.closeBrace) break
                val expressionEnd = consumeExpressionEndWithin(text, expressionStart, whenBody.closeBrace)
                if (expressionEnd <= expressionStart) break

                val scope = TextRange(expressionStart, expressionEnd)
                val patternBindings = extractPatternBindings(text, cursor, arrowOffset)
                for ((name, declarationOffset) in patternBindings) {
                    result += ScopeCandidate(name = name, declarationOffset = declarationOffset, scope = scope)
                }

                cursor = expressionEnd
            }

            lexer.start(text, (whenBody.closeBrace + 1).coerceAtMost(text.length), text.length, 0)
            continue
        }

        return result
    }

    private data class WhenBodyRange(
        val openBrace: Int,
        val closeBrace: Int
    )

    private fun findWhenBody(
        text: CharSequence,
        afterWhenOffset: Int
    ): WhenBodyRange? {
        val lexer = AikenLexing.createLexer()
        lexer.start(text, afterWhenOffset.coerceIn(0, text.length), text.length, 0)

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0
        var sawTopLevelIs = false

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

            if (tokenType == TokenType.WHITE_SPACE || tokenType == AikenTokenTypes.COMMENT || tokenType == AikenTokenTypes.WHITESPACE) {
                lexer.advance()
                continue
            }

            val atTopLevel = parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0
            if (tokenType == AikenTokenTypes.KEYWORD && tokenText == "is" && atTopLevel) {
                sawTopLevelIs = true
                lexer.advance()
                continue
            }

            if (tokenType == AikenTokenTypes.LBRACE && sawTopLevelIs && atTopLevel) {
                val openBrace = lexer.tokenStart
                val closeBrace = findMatchingBrace(text, openBrace) ?: return null
                return WhenBodyRange(openBrace = openBrace, closeBrace = closeBrace)
            }

            when {
                tokenType == AikenTokenTypes.LPAREN -> parenDepth++
                tokenType == AikenTokenTypes.RPAREN -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.LBRACKET -> bracketDepth++
                tokenType == AikenTokenTypes.RBRACKET -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.LBRACE -> braceDepth++
                tokenType == AikenTokenTypes.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                tokenType == AikenTokenTypes.OPERATOR && tokenText == "<" -> angleDepth++
                tokenType == AikenTokenTypes.OPERATOR && tokenText == ">" -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
            }
            lexer.advance()
        }

        return null
    }

    private fun findMatchingBrace(
        text: CharSequence,
        openOffset: Int
    ): Int? {
        if (openOffset !in text.indices || text[openOffset] != '{') return null
        var depth = 0
        var inString = false
        var inLineComment = false
        var index = openOffset

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
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
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }

        return null
    }

    private fun findTopLevelArrowInRange(
        text: CharSequence,
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
        text: CharSequence,
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

    private fun extractPatternBindings(
        text: CharSequence,
        start: Int,
        endExclusive: Int
    ): List<Pair<String, Int>> {
        if (start >= endExclusive || start !in 0..text.length) return emptyList()
        val safeEnd = endExclusive.coerceIn(start, text.length)
        val patternText = text.subSequence(start, safeEnd).toString()
        val lexer = AikenLexing.createLexer()
        lexer.start(patternText)
        val bindings = ArrayList<Pair<String, Int>>()

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            if (tokenType != AikenTokenTypes.IDENTIFIER && tokenType != AikenTokenTypes.FIELD) {
                lexer.advance()
                continue
            }

            val name = patternText.substring(lexer.tokenStart, lexer.tokenEnd)
            if (name == "_" || name.isBlank()) {
                lexer.advance()
                continue
            }
            val nextIndex = skipWhitespaceForward(patternText, lexer.tokenEnd)
            if (nextIndex < patternText.length && patternText[nextIndex] == ':') {
                lexer.advance()
                continue
            }
            bindings += name to (start + lexer.tokenStart)
            lexer.advance()
        }

        return bindings
    }

    private fun topLevelBindingPatternEnd(
        text: CharSequence,
        start: Int,
        endExclusive: Int
    ): Int {
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = start.coerceIn(0, text.length)
        val limit = endExclusive.coerceIn(index, text.length)

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
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ':' ->
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        return index
                    }
            }
            index++
        }

        return limit
    }

    private fun skipWhitespaceForward(
        text: CharSequence,
        start: Int
    ): Int {
        var index = start.coerceIn(0, text.length)
        while (index < text.length && text[index].isWhitespace()) index++
        return index
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
