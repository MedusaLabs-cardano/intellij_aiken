package com.medusalabs.aiken.parameterinfo

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.psi.search.GlobalSearchScope
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.index.AikenFunctionSignatureIndex
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

class AikenParameterInfoHandler : ParameterInfoHandler<PsiElement, AikenParameterInfoHandler.SignatureItem> {
    data class SignatureItem(
        val signature: String,
        val parameterRanges: List<Range>
    )

    data class Range(val start: Int, val end: Int)

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val file = context.file
        val offset = context.offset.coerceIn(0, file.textLength)

        val leaf = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        val type = leaf.node.elementType
        if (type == AikenTokenTypes.COMMENT || type == AikenTokenTypes.STRING || type == TokenType.WHITE_SPACE) return null

        val call = findCallContext(file.text, offset) ?: return null
        return file.findElementAt(call.openParenOffset)
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        val file = context.file
        val project = file.project
        val openParenOffset = element.textRange.startOffset
        val name = findNameBeforeParen(file.text, openParenOffset) ?: return

        val signatures = collectSignatures(project, file.text, name)
        if (signatures.isEmpty()) return

        context.itemsToShow = signatures.toTypedArray()
        context.showHint(element, openParenOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val file = context.file
        val offset = context.offset.coerceIn(0, file.textLength)

        val call = findCallContext(file.text, offset) ?: return null
        return file.findElementAt(call.openParenOffset)
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val file = context.file
        val offset = context.offset.coerceIn(0, file.textLength)

        val openParenOffset = parameterOwner.textRange.startOffset
        val argumentIndex = computeArgumentIndex(file.text, openParenOffset, offset) ?: run {
            context.removeHint()
            return
        }

        context.setCurrentParameter(argumentIndex)
    }

    override fun updateUI(p: SignatureItem, context: ParameterInfoUIContext) {
        val currentIndex = context.currentParameterIndex
        val highlight =
            if (currentIndex in p.parameterRanges.indices) p.parameterRanges[currentIndex]
            else null

        val highlightStart = highlight?.start ?: 0
        val highlightEnd = highlight?.end ?: 0

        context.setupUIComponentPresentation(
            p.signature,
            highlightStart,
            highlightEnd,
            false,
            false,
            false,
            context.defaultParameterColor
        )
    }

    override fun getParameterCloseChars(): String = ")"

    override fun couldShowInLookup(): Boolean = false

    private fun collectSignatures(project: Project, fileText: CharSequence, name: String): List<SignatureItem> {
        val scope = GlobalSearchScope.allScope(project)
        val indexed =
            FileBasedIndex.getInstance()
                .getValues(AikenFunctionSignatureIndex.NAME, name, scope)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

        val local =
            if (indexed.isNotEmpty()) emptySet()
            else AikenFunctionSignatureExtractor.extract(fileText)[name]?.let { setOf(it) } ?: emptySet()

        return (indexed + local)
            .asSequence()
            .sorted()
            .map { SignatureItem(it, computeParameterRanges(it)) }
            .toList()
    }

    private data class CallContext(val openParenOffset: Int)

    private fun findCallContext(text: CharSequence, offset: Int): CallContext? {
        val openParenOffset = findOpenParenForOffset(text, offset) ?: return null

        val name = findNameBeforeParen(text, openParenOffset) ?: return null
        if (name.isEmpty()) return null

        // If we already have the closing paren and caret is after it, do not show.
        val closeParenOffset = findMatchingParen(text, openParenOffset)
        if (closeParenOffset != null && offset > closeParenOffset) return null

        return CallContext(openParenOffset)
    }

    private fun findNameBeforeParen(text: CharSequence, openParenOffset: Int): String? {
        var i = openParenOffset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return null

        // Handle dotted access: module.fn(...)
        val end = i + 1
        while (i >= 0 && isIdentifierChar(text[i])) i--
        val start = i + 1
        if (start >= end) return null

        return text.subSequence(start, end).toString()
    }

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun findOpenParenForOffset(text: CharSequence, offset: Int): Int? {
        val stack = ArrayDeque<Int>()

        var inString = false
        var inLineComment = false

        var i = 0
        val limit = offset.coerceIn(0, text.length)
        while (i < limit) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < limit) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < limit && text[i + 1] == '/') {
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
                '(' -> stack.addLast(i)
                ')' -> if (stack.isNotEmpty()) stack.removeLast()
            }

            i++
        }

        return stack.lastOrNull()
    }

    private fun computeArgumentIndex(text: CharSequence, openParenOffset: Int, offset: Int): Int? {
        if (openParenOffset !in 0 until text.length) return null
        if (text[openParenOffset] != '(') return null

        var inString = false
        var inLineComment = false

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        var index = 0
        var i = openParenOffset + 1
        val limit = offset.coerceIn(0, text.length)
        while (i < limit) {
            val ch = text[i]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                i++
                continue
            }

            if (inString) {
                if (ch == '\\' && i + 1 < limit) {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < limit && text[i + 1] == '/') {
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
                '(' -> parenDepth++
                ')' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return null
                    if (parenDepth > 0) parenDepth--
                }
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) index++
            }

            i++
        }

        return index
    }

    private fun findMatchingParen(text: CharSequence, openIndex: Int): Int? {
        if (openIndex !in 0 until text.length) return null
        if (text[openIndex] != '(') return null

        var inString = false
        var inLineComment = false
        var depth = 0

        var i = openIndex
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
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }

        return null
    }

    private fun computeParameterRanges(signature: String): List<Range> {
        val openIndex = signature.indexOf('(')
        if (openIndex < 0) return emptyList()
        val closeIndex = findMatchingParen(signature, openIndex) ?: return emptyList()

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var angleDepth = 0

        val ranges = ArrayList<Range>()
        var segmentStart = openIndex + 1
        var i = openIndex + 1
        while (i < closeIndex) {
            val ch = signature[i]
            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && angleDepth == 0) {
                        addTrimmedRange(signature, segmentStart, i, ranges)
                        segmentStart = i + 1
                    }
                }
            }
            i++
        }

        addTrimmedRange(signature, segmentStart, closeIndex, ranges)

        return ranges.filter { it.start < it.end }
    }

    private fun addTrimmedRange(text: String, start: Int, endExclusive: Int, out: MutableList<Range>) {
        var s = start
        var e = endExclusive
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        out.add(Range(s, e))
    }
}
