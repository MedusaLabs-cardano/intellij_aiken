package com.medusalabs.aiken.folding

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Basic Aiken folding for `{ ... }` blocks as a fallback when LSP folding ranges aren't available.
 */
class AikenFoldingBuilder : BalancedPairFoldingBuilder(
    pairs = listOf('{' to '}'),
    lineCommentPrefix = "//",
    stringDelimiter = '"'
) {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val base = super.buildFoldRegions(root, document, quick)
        val arrowRanges = buildArrowBlockRanges(document)
        if (arrowRanges.isEmpty()) return base

        val seen = HashSet<Long>(base.size + arrowRanges.size)
        val allRanges = ArrayList<TextRange>(base.size + arrowRanges.size)

        for (descriptor in base) {
            val range = descriptor.range
            if (seen.add(packRange(range))) allRanges.add(range)
        }
        for (range in arrowRanges) {
            if (seen.add(packRange(range))) allRanges.add(range)
        }

        return allRanges
            .sortedBy { it.startOffset }
            .map { FoldingDescriptor(root, it) }
            .toTypedArray()
    }

    private fun buildArrowBlockRanges(document: Document): List<TextRange> {
        val text = document.charsSequence
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

        val ranges = ArrayList<TextRange>()
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
                    // Don't fold the line break before the next sibling header, otherwise it gets pulled onto the same line.
                    endOffsetBeforeLineBreak(document, nextSibling.startOffset)
                } else {
                    // If the closing brace is alone on its line, keep it on its own line too.
                    endOffsetBeforeClosingBraceLine(document, text, enclosing.closeOffset) ?: enclosing.closeOffset
                }
            if (foldEnd <= foldStart) continue
            if (document.getLineNumber(foldStart) >= document.getLineNumber(foldEnd)) continue

            ranges.add(TextRange(foldStart, foldEnd))
        }

        return ranges
    }

    private data class ArrowHeader(val line: Int, val startOffset: Int, val indent: Int, val enclosing: CurlyBracePair? = null)

    private fun collectArrowHeaders(document: Document, text: CharSequence): List<ArrowHeader> {
        val headers = ArrayList<ArrowHeader>()
        for (line in 0 until document.lineCount) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val indent = countIndent(text, lineStart, lineEnd)
            if (isArrowHeaderLine(text, lineStart, lineEnd)) {
                headers.add(ArrowHeader(line, lineStart, indent))
            }
        }
        return headers
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

            if (ch == '/' && i + 1 < lineEnd && text[i + 1] == '/') return false // ignore arrows in trailing comments
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

    private fun isWhenIsBraceOpen(document: Document, text: CharSequence, openBraceOffset: Int): Boolean {
        val line = document.getLineNumber(openBraceOffset)
        val lineStart = document.getLineStartOffset(line)

        var i = openBraceOffset - 1
        while (i >= lineStart && text[i].isWhitespace()) i--
        if (i < lineStart) return false

        // Look for the word "is" right before the opening brace.
        if (text[i] != 's') return false
        if (i - 1 < lineStart || text[i - 1] != 'i') return false

        val beforeI = i - 2
        if (beforeI >= lineStart && (text[beforeI].isLetterOrDigit() || text[beforeI] == '_')) return false

        // And somewhere earlier on the same line, the word "when".
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
                        pairs.add(CurlyBracePair(stack.removeLast(), i))
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

    private fun packRange(range: TextRange): Long = (range.startOffset.toLong() shl 32) or (range.endOffset.toLong() and 0xffffffffL)

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
}
