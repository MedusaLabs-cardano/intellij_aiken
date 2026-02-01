package com.medusalabs.aiken.breadcrumbs

import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.AikenLanguage

class AikenBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(AikenLanguage)

    override fun acceptElement(element: PsiElement): Boolean {
        if (element is AikenBreadcrumbElement) return true
        val file = element.containingFile ?: return false
        return file.fileType == AikenFileType
    }

    @Suppress("UnstableApiUsage")
    override fun acceptStickyElement(element: PsiElement): Boolean = element is AikenBreadcrumbElement

    override fun getElementInfo(element: PsiElement): String =
        when (element) {
            is AikenBreadcrumbElement -> element.label
            else -> element.text
        }

    override fun getParent(element: PsiElement): PsiElement? {
        if (element is AikenBreadcrumbElement) return element.parentElement
        val file = element.containingFile ?: return null
        if (file.fileType != AikenFileType) return null

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val offset = element.textRange?.startOffset ?: return null
        return buildScopeChain(file, document, offset)
    }

    private fun buildScopeChain(file: PsiFile, document: Document, offset: Int): PsiElement? {
        val scopes = collectScopes(document, document.charsSequence)
            .filter { offset in it.startOffset until it.endOffset }
            .sortedWith(compareBy<ScopeRange> { it.startOffset }.thenByDescending { it.endOffset })

        if (scopes.isEmpty()) return null

        var parent: PsiElement = file
        var current: AikenBreadcrumbElement? = null
        for (scope in scopes) {
            val label = scope.label.ifBlank { fallbackLabel(document, scope.startOffset) }
            current = AikenBreadcrumbElement(file, scope.textRange, label, parent)
            parent = current
        }
        return current
    }

    private data class ScopeRange(
        val startOffset: Int,
        val endOffset: Int,
        val label: String,
        val textRange: TextRange
    )

    private fun collectScopes(document: Document, text: CharSequence): List<ScopeRange> {
        val lexer = AikenLexing.createLexer()
        lexer.start(text)

        val stack = ArrayList<BraceEntry>()
        val scopes = ArrayList<ScopeRange>()
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                AikenTokenTypes.LBRACE -> stack.add(
                    BraceEntry(
                        lexer.tokenStart,
                        isBlockBrace(document, text, lexer.tokenStart)
                    )
                )
                AikenTokenTypes.RBRACE -> {
                    if (stack.isNotEmpty()) {
                        val entry = stack.removeLast()
                        if (entry.isBlock) {
                            val start = entry.offset
                            val end = lexer.tokenEnd
                            val scopeInfo = extractScopeInfo(document, text, start)
                            val label = scopeInfo.label.ifBlank { fallbackLabel(document, scopeInfo.startOffset) }
                            val range = TextRange(scopeInfo.startOffset, end)
                            scopes.add(ScopeRange(scopeInfo.startOffset, end, label, range))
                        }
                    }
                }
            }
            lexer.advance()
        }

        scopes.addAll(collectArrowScopes(document, text))
        return scopes
    }

    private data class ScopeInfo(val startOffset: Int, val label: String)

    private data class BraceEntry(val offset: Int, val isBlock: Boolean)

    private fun extractScopeInfo(document: Document, text: CharSequence, braceOffset: Int): ScopeInfo {
        val header = findSignatureHeader(document, text, braceOffset)
        if (header != null) return header

        val lineStart = text.lastIndexOf('\n', startIndex = braceOffset.coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', startIndex = braceOffset).let { if (it == -1) text.length else it }
        val raw = text.subSequence(lineStart, lineEnd).toString().trim()
        val cleaned = raw.substringBefore("{").trim()
        val label = if (cleaned.isNotEmpty() && cleaned != "{") cleaned else ""
        return ScopeInfo(braceOffset, label)
    }

    private fun fallbackLabel(document: Document, braceOffset: Int): String {
        val line = document.getLineNumber(braceOffset)
        for (i in line downTo (line - 3).coerceAtLeast(0)) {
            val start = document.getLineStartOffset(i)
            val end = document.getLineEndOffset(i)
            val raw = document.getText(TextRange(start, end)).trim()
            if (raw.isNotEmpty()) {
                val cleaned = raw.substringBefore("{").trim()
                if (cleaned.isNotEmpty() && cleaned != "{") return cleaned
            }
        }
        return "{"
    }

    private val headerKeywordRegex =
        Regex("""^\s*(pub\s+)?(fn|test|bench|validator)\b""")
    private val headerIdentifierRegex =
        Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val headerExcluded =
        setOf("if", "when", "and", "or", "else", "let", "expect", "const", "type", "pub", "fn", "test", "bench", "validator")

    private fun findSignatureHeader(document: Document, text: CharSequence, braceOffset: Int): ScopeInfo? {
        val braceLine = document.getLineNumber(braceOffset)
        val startLine = (braceLine - 200).coerceAtLeast(0)

        if (isControlBlockHeader(document, text, braceLine)) return null
        if (isArrowHeaderLine(document, text, braceLine)) return null

        val headerLine = findFunctionHeaderStartLine(document, text, braceLine, startLine) ?: return null
        val parenLine = findFirstParenLine(document, text, headerLine, braceLine) ?: headerLine
        val parenStart = document.getLineStartOffset(parenLine)
        val parenEnd = document.getLineEndOffset(parenLine)
        val parenRaw = text.subSequence(parenStart, parenEnd).toString()
        val trimmed = parenRaw.trim()
        val label = trimmed.substringBefore("{").trim().ifEmpty { return null }
        return ScopeInfo(parenStart, label)
    }

    private fun isControlBlockHeader(document: Document, text: CharSequence, braceLine: Int): Boolean {
        val minLine = (braceLine - 3).coerceAtLeast(0)
        for (line in braceLine downTo minLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val raw = text.subSequence(lineStart, lineEnd).toString()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) continue
            if (containsAnyWord(trimmed, setOf("if", "else", "when", "and", "or"))) return true
            break
        }
        return false
    }

    private fun isBlockBrace(document: Document, text: CharSequence, braceOffset: Int): Boolean {
        val line = document.getLineNumber(braceOffset)
        val lineStart = document.getLineStartOffset(line)
        val raw = text.subSequence(lineStart, braceOffset).toString()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val code = trimmed.substringBefore("//").trim()
        if (code.isEmpty()) return false
        if (code.contains("->")) return true
        if (containsAnyWord(code, setOf("if", "else", "when", "and", "or", "validator", "fn", "test", "bench"))) return true
        if (code.contains(')')) return true
        return false
    }

    private fun isArrowHeaderLine(document: Document, text: CharSequence, braceLine: Int): Boolean {
        val lineStart = document.getLineStartOffset(braceLine)
        val lineEnd = document.getLineEndOffset(braceLine)
        val raw = text.subSequence(lineStart, lineEnd).toString()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val code = trimmed.substringBefore("//").trim()
        val arrowIndex = code.indexOf("->")
        if (arrowIndex < 0) return false
        val beforeArrow = code.substring(0, arrowIndex)
        return !beforeArrow.contains(')')
    }

    private fun findFunctionHeaderStartLine(
        document: Document,
        text: CharSequence,
        fromLine: Int,
        minLine: Int
    ): Int? {
        for (line in fromLine downTo minLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val raw = text.subSequence(lineStart, lineEnd).toString()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) continue
            val code = trimmed.substringBefore("//").trim()
            if (code.isEmpty()) continue
            if (headerKeywordRegex.containsMatchIn(code)) return line
            if (containsWord(code, 0, code.length, "fn")) return line
            val match = headerIdentifierRegex.find(code) ?: continue
            val name = match.groupValues[1]
            if (name !in headerExcluded && code.contains('(')) {
                if (code.contains('{')) return line
                if (hasTypeAnnotationBetween(document, text, line, fromLine)) return line
            }
        }
        return null
    }

    private fun hasTypeAnnotationBetween(
        document: Document,
        text: CharSequence,
        startLine: Int,
        endLine: Int
    ): Boolean {
        for (line in startLine..endLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val raw = text.subSequence(lineStart, lineEnd).toString()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) continue
            val code = trimmed.substringBefore("//").trim()
            if (code.contains(':')) return true
        }
        return false
    }

    private fun findFirstParenLine(
        document: Document,
        text: CharSequence,
        startLine: Int,
        endLine: Int
    ): Int? {
        for (line in startLine..endLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val raw = text.subSequence(lineStart, lineEnd).toString()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) continue
            if (raw.contains('(')) return line
        }
        return null
    }

    private fun containsAnyWord(text: String, words: Set<String>): Boolean =
        words.any { containsWord(text, 0, text.length, it) }

    private fun collectArrowScopes(document: Document, text: CharSequence): List<ScopeRange> {
        val bracePairs = collectCurlyBracePairs(text)
        if (bracePairs.isEmpty()) return emptyList()

        val whenPairOpenOffsets = bracePairs
            .asSequence()
            .filter { isWhenIsBraceOpen(document, text, it.openOffset) }
            .map { it.openOffset }
            .toHashSet()
        if (whenPairOpenOffsets.isEmpty()) return emptyList()

        val rawHeaders = collectArrowHeaders(document, text)
        if (rawHeaders.isEmpty()) return emptyList()

        val headers = ArrayList<ArrowHeader>(rawHeaders.size)
        for (rawHeader in rawHeaders) {
            val enclosing = findSmallestEnclosingPair(bracePairs, rawHeader.startOffset) ?: continue
            if (!whenPairOpenOffsets.contains(enclosing.openOffset)) continue
            headers.add(rawHeader.copy(enclosing = enclosing))
        }
        if (headers.isEmpty()) return emptyList()

        val scopes = ArrayList<ScopeRange>()
        for ((index, header) in headers.withIndex()) {
            val enclosing = header.enclosing ?: continue
            val foldStart = document.getLineEndOffset(header.line)
            if (foldStart >= enclosing.closeOffset) continue

            val nextSibling = headers
                .asSequence()
                .drop(index + 1)
                .firstOrNull { it.enclosing == enclosing && it.indent == header.indent }

            val foldEnd =
                if (nextSibling != null) {
                    endOffsetBeforeLineBreak(document, nextSibling.startOffset)
                } else {
                    endOffsetBeforeClosingBraceLine(document, text, enclosing.closeOffset) ?: enclosing.closeOffset
                }
            if (foldEnd <= header.startOffset) continue

            scopes.add(
                ScopeRange(
                    startOffset = header.startOffset,
                    endOffset = foldEnd,
                    label = header.label.ifBlank { fallbackLabel(document, header.startOffset) },
                    textRange = TextRange(header.startOffset, foldEnd)
                )
            )
        }

        return scopes
    }

    private data class ArrowHeader(
        val line: Int,
        val startOffset: Int,
        val indent: Int,
        val label: String,
        val enclosing: CurlyBracePair? = null
    )

    private fun collectArrowHeaders(document: Document, text: CharSequence): List<ArrowHeader> {
        val headers = ArrayList<ArrowHeader>()
        for (line in 0 until document.lineCount) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val indent = countIndent(text, lineStart, lineEnd)
            if (isArrowHeaderLine(text, lineStart, lineEnd)) {
                val label = extractArrowLabel(text, lineStart, lineEnd)
                headers.add(ArrowHeader(line, lineStart, indent, label))
            }
        }
        return headers
    }

    private fun extractArrowLabel(text: CharSequence, lineStart: Int, lineEnd: Int): String {
        val raw = text.subSequence(lineStart, lineEnd).toString().trim()
        val arrowIndex = raw.indexOf("->")
        val before = if (arrowIndex >= 0) raw.substring(0, arrowIndex) else raw
        return before.trim().ifEmpty { raw }
    }

    private fun countIndent(text: CharSequence, lineStart: Int, lineEnd: Int): Int {
        var i = lineStart
        var indent = 0
        while (i < lineEnd) {
            val ch = text[i]
            if (ch == ' ' || ch == '\t') {
                indent++
                i++
            } else {
                break
            }
        }
        return indent
    }

    private fun isArrowHeaderLine(text: CharSequence, lineStart: Int, lineEnd: Int): Boolean {
        var i = lineStart
        while (i < lineEnd && text[i].isWhitespace()) i++
        if (i >= lineEnd) return false
        if (i + 1 < lineEnd && text[i] == '/' && text[i + 1] == '/') return false

        var inString = false
        while (i < lineEnd) {
            val ch = text[i]

            if (inString) {
                if (ch == '\\' && i + 1 < lineEnd) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < lineEnd && text[i + 1] == '/') return false
            if (ch == '"') {
                inString = true
                i++
                continue
            }

            if (ch == '-' && i + 1 < lineEnd && text[i + 1] == '>') {
                return true
            }

            i++
        }

        return false
    }

    private data class CurlyBracePair(val openOffset: Int, val closeOffset: Int)

    private fun collectCurlyBracePairs(text: CharSequence): List<CurlyBracePair> {
        val pairs = ArrayList<CurlyBracePair>()
        val stack = ArrayDeque<Int>()

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
                '{' -> stack.addLast(i)
                '}' -> {
                    if (stack.isNotEmpty()) {
                        pairs.add(CurlyBracePair(stack.removeLast(), i + 1))
                    }
                }
            }

            i++
        }

        return pairs
    }

    private fun findSmallestEnclosingPair(pairs: List<CurlyBracePair>, offset: Int): CurlyBracePair? {
        var best: CurlyBracePair? = null
        for (pair in pairs) {
            if (pair.openOffset < offset && offset < pair.closeOffset) {
                if (best == null || (pair.closeOffset - pair.openOffset) < (best.closeOffset - best.openOffset)) {
                    best = pair
                }
            }
        }
        return best
    }

    private fun isWhenIsBraceOpen(document: Document, text: CharSequence, openBraceOffset: Int): Boolean {
        val line = document.getLineNumber(openBraceOffset)
        val lineStart = document.getLineStartOffset(line)

        var i = openBraceOffset - 1
        while (i >= lineStart && text[i].isWhitespace()) i--
        if (i < lineStart) return false

        if (text[i] != 's') return false
        if (i - 1 < lineStart || text[i - 1] != 'i') return false

        val beforeI = i - 2
        if (beforeI >= lineStart && (text[beforeI].isLetterOrDigit() || text[beforeI] == '_')) return false

        return containsWord(text, lineStart, i - 1, "when")
    }

    private fun containsWord(text: CharSequence, start: Int, endExclusive: Int, word: String): Boolean {
        if (endExclusive - start < word.length) return false

        var i = start
        while (i + word.length <= endExclusive) {
            var j = 0
            while (j < word.length && text[i + j] == word[j]) j++
            if (j == word.length) {
                val beforeOk = i == start || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_')
                val afterIndex = i + word.length
                val afterOk = afterIndex >= endExclusive || !(text[afterIndex].isLetterOrDigit() || text[afterIndex] == '_')
                if (beforeOk && afterOk) return true
            }
            i++
        }

        return false
    }

    private fun endOffsetBeforeLineBreak(document: Document, lineStartOffset: Int): Int {
        val line = document.getLineNumber(lineStartOffset)
        if (line <= 0) return lineStartOffset
        return document.getLineEndOffset(line - 1)
    }

    private fun endOffsetBeforeClosingBraceLine(document: Document, text: CharSequence, closeOffset: Int): Int? {
        val closeLine = document.getLineNumber(closeOffset)
        if (closeLine <= 0) return null

        val closeLineStart = document.getLineStartOffset(closeLine)
        if (closeLineStart >= closeOffset) return null

        for (i in closeLineStart until closeOffset) {
            if (!text[i].isWhitespace()) return null
        }

        return document.getLineEndOffset(closeLine - 1)
    }

    private class AikenBreadcrumbElement(
        private val file: PsiFile,
        private val range: TextRange,
        val label: String,
        val parentElement: PsiElement?
    ) : FakePsiElement() {
        override fun getParent(): PsiElement? = parentElement
        override fun getContainingFile(): PsiFile = file
        override fun getTextRange(): TextRange = range
        override fun getTextOffset(): Int = range.startOffset
        override fun getName(): String = label
    }
}
