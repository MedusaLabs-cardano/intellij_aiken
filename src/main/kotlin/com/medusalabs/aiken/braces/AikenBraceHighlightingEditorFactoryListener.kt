package com.medusalabs.aiken.braces

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

private val AIKEN_BRACE_STATE_KEY: Key<AikenBraceHighlightingState> =
    Key.create("com.medusalabs.aiken.braces.matching.state")

private data class AikenBraceMappingCache(
    val documentStamp: Long,
    val mapping: Map<Int, Int>
)

private data class AikenBraceHighlightingState(
    val caretListener: CaretListener,
    val documentListener: DocumentListener,
    var cache: AikenBraceMappingCache? = null,
    var highlighters: List<RangeHighlighter> = emptyList()
)

private val AIKEN_OPENING_BRACES = setOf('(', '{', '[')
private val AIKEN_CLOSING_BRACES = setOf(')', '}', ']')

private fun isAikenBrace(ch: Char): Boolean = AIKEN_OPENING_BRACES.contains(ch) || AIKEN_CLOSING_BRACES.contains(ch)

private fun buildAikenBraceMapping(text: CharSequence): Map<Int, Int> {
    val mapping = HashMap<Int, Int>()
    val stack = ArrayDeque<Pair<Char, Int>>()

    var inString = false
    var inLineComment = false
    var i = 0
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

        if (AIKEN_OPENING_BRACES.contains(ch)) {
            stack.addLast(ch to i)
            i++
            continue
        }

        if (AIKEN_CLOSING_BRACES.contains(ch)) {
            val expectedOpen = when (ch) {
                ')' -> '('
                '}' -> '{'
                ']' -> '['
                else -> null
            }
            if (expectedOpen != null) {
                var matched: Pair<Char, Int>? = null
                while (stack.isNotEmpty()) {
                    val last = stack.removeLast()
                    if (last.first == expectedOpen) {
                        matched = last
                        break
                    }
                }
                if (matched != null) {
                    mapping[matched.second] = i
                    mapping[i] = matched.second
                }
            }
            i++
            continue
        }

        i++
    }

    return mapping
}

class AikenBraceHighlightingEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (vf.fileType != AikenFileType && vf.fileType != UplcFileType) return

        val caretListener =
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    updateHighlights(event.editor)
                }
            }
        val documentListener =
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    updateHighlights(editor)
                }
            }

        editor.caretModel.addCaretListener(caretListener)
        editor.document.addDocumentListener(documentListener, editor.project ?: ApplicationManager.getApplication())
        editor.putUserData(AIKEN_BRACE_STATE_KEY, AikenBraceHighlightingState(caretListener, documentListener))

        updateHighlights(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val state = editor.getUserData(AIKEN_BRACE_STATE_KEY) ?: return
        editor.caretModel.removeCaretListener(state.caretListener)
        editor.document.removeDocumentListener(state.documentListener)
        clearHighlights(editor)
        editor.putUserData(AIKEN_BRACE_STATE_KEY, null)
    }
}

private fun clearHighlights(editor: Editor) {
    val state = editor.getUserData(AIKEN_BRACE_STATE_KEY) ?: return
    state.highlighters.forEach { editor.markupModel.removeHighlighter(it) }
    state.highlighters = emptyList()
}

private fun updateHighlights(editor: Editor) {
    val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: run {
        clearHighlights(editor)
        return
    }

    if (vf.fileType != AikenFileType && vf.fileType != UplcFileType) {
        clearHighlights(editor)
        return
    }

    val text = editor.document.charsSequence
    if (text.isEmpty()) {
        clearHighlights(editor)
        return
    }

    val caretOffset = editor.caretModel.offset
    val braceOffset =
        when {
            caretOffset in 0 until text.length && isAikenBrace(text[caretOffset]) -> caretOffset
            caretOffset - 1 in 0 until text.length && isAikenBrace(text[caretOffset - 1]) -> caretOffset - 1
            else -> null
        }

    if (braceOffset == null) {
        clearHighlights(editor)
        return
    }

    val state = editor.getUserData(AIKEN_BRACE_STATE_KEY) ?: run {
        clearHighlights(editor)
        return
    }

    val stamp = editor.document.modificationStamp
    val cache = state.cache
    val mapping =
        if (cache != null && cache.documentStamp == stamp) cache.mapping else {
            val newMapping = buildAikenBraceMapping(text)
            state.cache = AikenBraceMappingCache(stamp, newMapping)
            newMapping
        }

    val matchOffset = mapping[braceOffset]
    if (matchOffset == null) {
        clearHighlights(editor)
        return
    }

    clearHighlights(editor)
    val layer = HighlighterLayer.SELECTION - 1
    val attrs = editor.colorsScheme.getAttributes(CodeInsightColors.MATCHED_BRACE_ATTRIBUTES)
    val left =
        editor.markupModel.addRangeHighlighter(
            braceOffset,
            braceOffset + 1,
            layer,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )
    val right =
        editor.markupModel.addRangeHighlighter(
            matchOffset,
            matchOffset + 1,
            layer,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )

    state.highlighters = listOf(left, right)
}
