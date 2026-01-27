package com.medusalabs.aiken.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Lightweight, PSI-free folding based on balancing paired characters (e.g. `{}`).
 *
 * This is a fallback for languages that don't provide a full parser/PSI but still need basic block folding.
 */
abstract class BalancedPairFoldingBuilder(
    pairs: List<Pair<Char, Char>>,
    private val lineCommentPrefix: String = "//",
    private val stringDelimiter: Char = '"'
) : FoldingBuilderEx() {
    private val openToClose: Map<Char, Char> = pairs.toMap()
    private val closeToOpen: Map<Char, Char> = pairs.associate { (open, close) -> close to open }

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.charsSequence
        val endOffset = text.length
        if (endOffset == 0) return emptyArray()

        val foldRanges = ArrayList<TextRange>()
        val stack = ArrayDeque<OpenToken>()

        var i = 0
        var inLineComment = false
        var inString = false

        while (i < endOffset) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < endOffset) {
                    i += 2
                    continue
                }
                if (ch == stringDelimiter) inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < endOffset && text[i + 1] == '/' && lineCommentPrefix == "//") {
                inLineComment = true
                i += 2
                continue
            }

            // If we ever support non-`//` line comments, fall back to a prefix check.
            if (lineCommentPrefix != "//" && startsWith(text, i, lineCommentPrefix)) {
                inLineComment = true
                i += lineCommentPrefix.length
                continue
            }

            if (ch == stringDelimiter) {
                inString = true
                i++
                continue
            }

            val closeForOpen = openToClose[ch]
            if (closeForOpen != null) {
                stack.addLast(OpenToken(ch, i))
                i++
                continue
            }

            val openForClose = closeToOpen[ch]
            if (openForClose != null) {
                // Find the nearest matching opener.
                var openToken: OpenToken? = null
                while (stack.isNotEmpty()) {
                    val candidate = stack.removeLast()
                    if (candidate.openChar == openForClose) {
                        openToken = candidate
                        break
                    }
                }

                if (openToken != null) {
                    val openOffset = openToken.offset
                    val closeOffset = i
                    if (document.getLineNumber(openOffset) < document.getLineNumber(closeOffset)) {
                        val rangeStart = openOffset + 1
                        val rangeEnd = closeOffset
                        if (rangeStart < rangeEnd) {
                            foldRanges.add(TextRange(rangeStart, rangeEnd))
                        }
                    }
                }

                i++
                continue
            }

            i++
        }

        if (foldRanges.isEmpty()) return emptyArray()

        // Use the file/root element as an anchor so this keeps working without a dedicated PSI tree.
        return foldRanges
            .distinct()
            .sortedBy { it.startOffset }
            .map { FoldingDescriptor(root, it) }
            .toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private data class OpenToken(val openChar: Char, val offset: Int)

    private fun startsWith(text: CharSequence, offset: Int, prefix: String): Boolean {
        if (offset + prefix.length > text.length) return false
        for (j in prefix.indices) {
            if (text[offset + j] != prefix[j]) return false
        }
        return true
    }
}

