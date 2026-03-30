package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenFileType

class AikenCompletionAutoPopupTypedHandler : TypedHandlerDelegate(), DumbAware {
    override fun checkAutoPopup(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile
    ): Result {
        if (file.fileType != AikenFileType) return Result.CONTINUE
        if (!shouldTriggerAutoPopup(charTyped, editor)) return Result.CONTINUE

        val offset = editor.caretModel.offset
        val tokenType = file.findElementAt((offset - 1).coerceAtLeast(0))?.node?.elementType
        if (tokenType == AikenTokenTypes.COMMENT || tokenType == AikenTokenTypes.STRING) {
            return Result.CONTINUE
        }

        if (shouldForceContextualRefresh(charTyped, editor)) return Result.CONTINUE

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }

    override fun charTyped(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile
    ): Result {
        if (file.fileType != AikenFileType) return Result.CONTINUE
        if (!shouldForceContextualRefresh(charTyped, editor)) return Result.CONTINUE

        val offset = editor.caretModel.offset
        val tokenType = file.findElementAt((offset - 1).coerceAtLeast(0))?.node?.elementType
        if (tokenType == AikenTokenTypes.COMMENT || tokenType == AikenTokenTypes.STRING) {
            return Result.CONTINUE
        }

        restartBasicCompletion(AutoPopupController.getInstance(project), editor)
        return Result.CONTINUE
    }

    private fun shouldTriggerAutoPopup(ch: Char, editor: Editor): Boolean {
        if (ch.isLetter() || ch == '_' || ch == '.' || ch == '/' || ch == '{' || ch == ':' || ch == '[' || ch == ',') {
            return true
        }

        if (!ch.isWhitespace()) return false

        val chars = editor.document.charsSequence
        var index = (editor.caretModel.offset - 2).coerceAtLeast(0)
        while (index >= 0 && chars[index].isWhitespace()) {
            index--
        }
        if (index < 0) return false

        return when (chars[index]) {
            ':', ',', '[' -> true
            else -> false
        }
    }

    private fun shouldForceContextualRefresh(ch: Char, editor: Editor): Boolean {
        if (ch == ':' || ch == '[' || ch == ',' || ch.isWhitespace()) {
            return true
        }

        if (ch != '{' && ch != '.' && ch != '/') return false

        val chars = editor.document.charsSequence
        val index = (editor.caretModel.offset - 2).coerceAtLeast(0)
        return index < chars.length && !chars[index].isWhitespace()
    }

    private fun restartBasicCompletion(
        autoPopupController: AutoPopupController,
        editor: Editor
    ) {
        autoPopupController.cancelAllRequests()
        autoPopupController.scheduleAutoPopup(editor, CompletionType.BASIC, null)
    }
}
