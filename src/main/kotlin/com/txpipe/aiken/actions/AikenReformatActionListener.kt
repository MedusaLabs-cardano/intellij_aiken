package com.txpipe.aiken.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiManager
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType

/**
 * Fallback hook: if our action replacement ever misses, intercept ReformatCode here.
 */
class AikenReformatActionListener : AnActionListener {
    private val log = Logger.getInstance(AikenReformatActionListener::class.java)
    private val ids = setOf("ReformatCode", "EditorReformat")

    override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        val id = com.intellij.openapi.actionSystem.ActionManager.getInstance().getId(action) ?: return
        if (id !in ids) return

        val project = event.project ?: return
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = dataContext.getData(CommonDataKeys.PSI_FILE) ?: return
        val vFile = psiFile.virtualFile ?: return

        if (vFile.fileType != AikenFileType && vFile.fileType != UplcFileType) return

        val command = when (vFile.fileType) {
            AikenFileType -> listOf("aiken", "fmt", "--stdin")
            UplcFileType -> listOf("aiken", "uplc", "fmt", "--stdin")
            else -> return
        }

        log.info("AikenReformatActionListener: formatting ${vFile.name} via $command (id=$id)")
        val formatted = runFormatter(command, editor.document.text) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(formatted)
            PsiManager.getInstance(project).findFile(vFile)?.let { it.subtreeChanged() }
        }

        // Prevent the original action from running (and showing "No lines changed").
        event.inputEvent?.consume()
        event.presentation.isEnabled = false
    }

    private fun runFormatter(command: List<String>, stdin: String): String? =
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { it.write(stdin) }
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) output else {
                log.warn("AikenReformatActionListener: formatter failed exit=$exit output=$output")
                null
            }
        } catch (e: Exception) {
            log.warn("AikenReformatActionListener: formatter invocation failed", e)
            null
        }
}
