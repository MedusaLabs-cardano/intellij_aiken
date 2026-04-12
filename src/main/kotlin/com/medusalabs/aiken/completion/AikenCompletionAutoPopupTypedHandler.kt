package com.medusalabs.aiken.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
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

        if (AikenAutoPopupGuard.suppressActiveLookupOnExactMatch(project, editor) ||
            AikenAutoPopupGuard.suppressSemanticAutoPopupOnExactMatch(project, editor, file)
        ) {
            return Result.STOP
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
        if (!shouldRestartCompletion(charTyped, editor)) return Result.CONTINUE

        val offset = editor.caretModel.offset
        val tokenType = file.findElementAt((offset - 1).coerceAtLeast(0))?.node?.elementType
        if (tokenType == AikenTokenTypes.COMMENT || tokenType == AikenTokenTypes.STRING) {
            return Result.CONTINUE
        }

        if (AikenAutoPopupGuard.suppressActiveLookupOnExactMatch(project, editor) ||
            AikenAutoPopupGuard.suppressSemanticAutoPopupOnExactMatch(project, editor, file)
        ) {
            return Result.STOP
        }

        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        restartBasicCompletion(project, AutoPopupController.getInstance(project), editor, charTyped)
        return Result.CONTINUE
    }

    private fun shouldRestartCompletion(ch: Char, editor: Editor): Boolean {
        if (shouldForceContextualRefresh(ch, editor)) return true
        return ch.isLetterOrDigit() || ch == '_'
    }

    private fun shouldTriggerAutoPopup(ch: Char, editor: Editor): Boolean {
        if (ch == ':' || ch == '[' || ch == ',') {
            return true
        }

        if (ch == '.' || ch == '/' || ch == '{') {
            return shouldTriggerOnImmediateLeftContext(ch, editor)
        }

        if (!ch.isWhitespace()) return false
        return isWhitespaceContextualRefresh(editor)
    }

    private fun shouldForceContextualRefresh(ch: Char, editor: Editor): Boolean {
        if (ch == ':' || ch == '[' || ch == ',') {
            return true
        }

        if (ch.isWhitespace()) return isWhitespaceContextualRefresh(editor)

        if (ch != '{' && ch != '.' && ch != '/') return false

        val chars = editor.document.charsSequence
        val index = editor.caretModel.offset - 2
        if (index !in chars.indices) return false
        val previousChar = chars[index]

        return when (ch) {
            '.' -> previousChar.isLetterOrDigit() || previousChar == '_' || previousChar == '.' || previousChar == ')' || previousChar == ']' || previousChar == '}'
            '/' -> previousChar.isLetterOrDigit() || previousChar == '_' || previousChar == '/'
            '{' -> previousChar.isLetterOrDigit() || previousChar == '_' || previousChar == ')' || previousChar == '}' || previousChar == ']'
            else -> false
        }
    }

    private fun isWhitespaceContextualRefresh(editor: Editor): Boolean {
        val chars = editor.document.charsSequence
        var index = (editor.caretModel.offset - 2).coerceAtLeast(0)
        while (index >= 0 && chars[index].isWhitespace()) {
            index--
        }
        if (index < 0) return false

        return when (chars[index]) {
            ':', ',', '[' -> true
            '>' -> index > 0 && chars[index - 1] == '|'
            else -> false
        }
    }

    private fun shouldTriggerOnImmediateLeftContext(ch: Char, editor: Editor): Boolean {
        val chars = editor.document.charsSequence
        val index = editor.caretModel.offset - 1
        if (index !in chars.indices) return false
        val previousChar = chars[index]

        return when (ch) {
            '.' -> previousChar.isLetterOrDigit() || previousChar == '_' || previousChar == '.' || previousChar == ')' || previousChar == ']' || previousChar == '}'
            '/' -> previousChar.isLetterOrDigit() || previousChar == '_' || previousChar == '/'
            '{' -> previousChar.isLetterOrDigit() || previousChar == '_' || previousChar == ')' || previousChar == '}' || previousChar == ']'
            else -> false
        }
    }

    private fun restartBasicCompletion(
        project: Project,
        autoPopupController: AutoPopupController,
        editor: Editor,
        triggerChar: Char
    ) {
        autoPopupController.cancelAllRequests()
        val activeLookup = LookupManager.getActiveLookup(editor)
        if (activeLookup != null) {
            LookupManager.hideActiveLookup(project)
        }
        if (triggerChar == '.') {
            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed) return@invokeLater
                CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
            }
            return
        }
        autoPopupController.autoPopupMemberLookup(editor, CompletionType.BASIC, null)
    }
}
