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
        if (isInsideCommentOrString(editor, file)) {
            return Result.STOP
        }
        if (!shouldTriggerAutoPopup(charTyped, editor, file)) return Result.CONTINUE

        if (AikenAutoPopupGuard.suppressActiveLookupOnExactMatch(project, editor) ||
            AikenAutoPopupGuard.suppressSemanticAutoPopupOnExactMatch(project, editor, file)
        ) {
            return Result.STOP
        }

        if (shouldSuppressDelimiterTriggeredOrdinaryAutoPopup(charTyped, editor, file)) {
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
        if (!shouldRestartCompletion(charTyped, editor, file)) return Result.CONTINUE

        if (isInsideCommentOrString(editor, file)) {
            return Result.STOP
        }

        if (AikenAutoPopupGuard.suppressActiveLookupOnExactMatch(project, editor) ||
            AikenAutoPopupGuard.suppressSemanticAutoPopupOnExactMatch(project, editor, file)
        ) {
            return Result.STOP
        }

        if (shouldSuppressDelimiterTriggeredOrdinaryAutoPopup(charTyped, editor, file)) {
            return Result.STOP
        }

        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        restartBasicCompletion(project, AutoPopupController.getInstance(project), editor, charTyped)
        return Result.CONTINUE
    }

    private fun shouldRestartCompletion(ch: Char, editor: Editor, file: PsiFile): Boolean {
        if ((ch == '=' || (ch.isWhitespace() && isWhitespaceAfterBindingOperator(editor))) &&
            hasTypedBindingInitializerContext(editor, file)
        ) {
            return true
        }
        if (shouldForceContextualRefresh(ch, editor)) return true
        return ch.isLetterOrDigit() || ch == '_'
    }

    private fun shouldTriggerAutoPopup(ch: Char, editor: Editor, file: PsiFile): Boolean {
        if (ch == '=' && hasTypedBindingInitializerContext(editor, file)) {
            return true
        }

        if (ch == ':' || ch == '[' || ch == ',') {
            return true
        }

        if (ch == '.' || ch == '/' || ch == '{') {
            return shouldTriggerOnImmediateLeftContext(ch, editor)
        }

        if (!ch.isWhitespace()) return false
        if (isWhitespaceAfterBindingOperator(editor) && hasTypedBindingInitializerContext(editor, file)) {
            return true
        }
        return isWhitespaceContextualRefresh(editor)
    }

    private fun shouldSuppressDelimiterTriggeredOrdinaryAutoPopup(
        ch: Char,
        editor: Editor,
        file: PsiFile
    ): Boolean {
        if (!isDelimiterDrivenAutoPopupTrigger(ch, editor)) return false
        return AikenAutoPopupGuard.suppressBlankOrdinaryExpressionAutoPopup(editor, file) ||
            AikenAutoPopupGuard.suppressBlankUnconstrainedArgumentAutoPopup(editor, file)
    }

    private fun isDelimiterDrivenAutoPopupTrigger(
        ch: Char,
        editor: Editor
    ): Boolean {
        if (ch == ',' || ch == '{') return true
        if (!ch.isWhitespace()) return false

        val chars = editor.document.charsSequence
        var index = (editor.caretModel.offset - 2).coerceAtLeast(0)
        while (index >= 0 && chars[index].isWhitespace()) {
            index--
        }
        if (index < 0) return false
        return chars[index] == ',' || chars[index] == '{'
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

    private fun isWhitespaceAfterBindingOperator(editor: Editor): Boolean {
        val chars = editor.document.charsSequence
        var index = (editor.caretModel.offset - 2).coerceAtLeast(0)
        while (index >= 0 && chars[index].isWhitespace()) {
            index--
        }
        return index >= 0 && chars[index] == '=' && (index == 0 || chars[index - 1] != '=')
    }

    private fun hasTypedBindingInitializerContext(editor: Editor, file: PsiFile): Boolean {
        val project = file.project
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val offset = editor.caretModel.offset.coerceIn(0, editor.document.textLength)
        val anchor =
            file.findElementAt((offset - 1).coerceAtLeast(0))
                ?: file.findElementAt(offset.coerceAtMost(file.textLength))
                ?: return false
        if (!AikenTypeDirectedCompletionSupport.hasBindingInitializerExpectedTypeContext(anchor, file.text, offset)) {
            return false
        }
        return AikenTypeDirectedCompletionSupport.bindingInitializerLookups(anchor, file.text, offset).isNotEmpty()
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
        autoPopupController.scheduleAutoPopup(editor, CompletionType.BASIC, null)
    }

    private fun isInsideCommentOrString(
        editor: Editor,
        file: PsiFile
    ): Boolean {
        val offset = editor.caretModel.offset
        val tokenType = file.findElementAt((offset - 1).coerceAtLeast(0))?.node?.elementType
        if (tokenType == AikenTokenTypes.COMMENT || tokenType == AikenTokenTypes.STRING) {
            return true
        }

        val text = editor.document.charsSequence
        if (text.isEmpty()) return false
        val probeOffset = (offset - 1).coerceAtLeast(0)
        val lexer = com.medusalabs.aiken.highlight.lexer.AikenLexing.createLexer()
        lexer.start(text.toString())
        while (lexer.tokenType != null) {
            if (probeOffset in lexer.tokenStart until lexer.tokenEnd) {
                return lexer.tokenType == AikenTokenTypes.COMMENT || lexer.tokenType == AikenTokenTypes.STRING
            }
            lexer.advance()
        }
        return false
    }
}
