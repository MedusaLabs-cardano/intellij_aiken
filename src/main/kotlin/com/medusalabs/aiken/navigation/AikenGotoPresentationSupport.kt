package com.medusalabs.aiken.navigation

import com.intellij.psi.PsiElement

internal object AikenGotoPresentationSupport {
    internal data class LineInfo(val text: String, val number: Int)
    private const val ELLIPSIS = "..."

    fun extractLineInfo(element: PsiElement): LineInfo? {
        val psiFile = element.containingFile ?: return null
        val text = psiFile.text
        if (text.isEmpty()) return null

        val offset = element.textRange.startOffset.coerceIn(0, text.length - 1)
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it == -1) 0 else it + 1 }
        val lineEndRaw = text.indexOf('\n', offset)
        val lineEnd = if (lineEndRaw == -1) text.length else lineEndRaw
        if (lineEnd <= lineStart) return null

        val lineText = text.substring(lineStart, lineEnd).trim()
        if (lineText.isEmpty()) return null

        var lineNo = 1
        var i = 0
        while (i < offset) {
            if (text[i] == '\n') lineNo += 1
            i += 1
        }
        return LineInfo(lineText, lineNo)
    }

    fun buildPreview(
        element: PsiElement,
        maxLength: Int = 110,
        focusToken: String? = null
    ): String? {
        val line = extractLineInfo(element) ?: return null
        val normalized = line.text.replace("\\s+".toRegex(), " ").trim()
        if (normalized.isEmpty()) return null
        return shrinkLine(normalized, maxLength, focusToken)
    }

    fun buildShortLocation(element: PsiElement): String? {
        val psiFile = element.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return psiFile.name
        val line = extractLineInfo(element)?.number ?: return virtualFile.name
        return "${virtualFile.name}:$line"
    }

    private fun shrinkLine(text: String, maxLength: Int, focusToken: String?): String {
        if (maxLength <= 0) return ""
        if (text.length <= maxLength) return text
        if (maxLength <= ELLIPSIS.length) return ELLIPSIS.take(maxLength)

        val tokenRange = findFocusRange(text, focusToken)
        if (tokenRange == null) return shrinkMiddle(text, maxLength)
        return shrinkAroundFocus(text, tokenRange, maxLength)
    }

    private fun findFocusRange(text: String, focusToken: String?): IntRange? {
        val token = focusToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var index = text.indexOf(token)
        var firstMatch = -1

        while (index >= 0) {
            if (firstMatch < 0) firstMatch = index
            val startOk = index == 0 || !isIdentifierChar(text[index - 1])
            val end = index + token.length
            val endOk = end == text.length || !isIdentifierChar(text[end])
            if (startOk && endOk) {
                return index until end
            }
            index = text.indexOf(token, index + 1)
        }

        return if (firstMatch >= 0) firstMatch until (firstMatch + token.length) else null
    }

    private fun shrinkAroundFocus(text: String, range: IntRange, maxLength: Int): String {
        var start = range.first
        var end = range.last + 1

        if (renderLength(start, end, text.length) > maxLength) {
            return shrinkMiddle(text.substring(start, end), maxLength)
        }

        while (true) {
            var expanded = false
            if (start > 0 && renderLength(start - 1, end, text.length) <= maxLength) {
                start -= 1
                expanded = true
            }
            if (end < text.length && renderLength(start, end + 1, text.length) <= maxLength) {
                end += 1
                expanded = true
            }
            if (!expanded) break
        }

        return renderSnippet(text, start, end)
    }

    private fun shrinkMiddle(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        if (maxLength <= ELLIPSIS.length) return ELLIPSIS.take(maxLength)

        val core = maxLength - ELLIPSIS.length
        val left = core / 2
        val right = core - left
        return text.take(left) + ELLIPSIS + text.takeLast(right)
    }

    private fun renderLength(start: Int, end: Int, fullLength: Int): Int {
        val left = if (start > 0) ELLIPSIS.length else 0
        val right = if (end < fullLength) ELLIPSIS.length else 0
        return (end - start) + left + right
    }

    private fun renderSnippet(text: String, start: Int, end: Int): String {
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < text.length) ELLIPSIS else ""
        return prefix + text.substring(start, end) + suffix
    }

    private fun isIdentifierChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || ch == '_'
    }
}
