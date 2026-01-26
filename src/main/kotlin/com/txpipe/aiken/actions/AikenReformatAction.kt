package com.txpipe.aiken.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType

/**
 * Replaces Reformat Code for Aiken/UPLC files with a per-file Aiken CLI call.
 * Avoids running project-wide `aiken fmt`, uses `--stdin` to format just the current buffer.
 */
class AikenReformatAction(private val delegate: AnAction) : AnAction(), DumbAware {
    private val log = Logger.getInstance(AikenReformatAction::class.java)

    init {
        // Keep the original presentation (text, description, icon, shortcuts) for menus/toolbars.
        copyFrom(delegate)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (project == null || psiFile == null || editor == null) {
            delegate.actionPerformed(e)
            return
        }

        if (!isAiken(psiFile.virtualFile)) {
            delegate.actionPerformed(e)
            return
        }

        val command = when (psiFile.fileType) {
            AikenFileType -> listOf("aiken", "fmt", "--stdin")
            UplcFileType -> listOf("aiken", "uplc", "fmt", "--stdin")
            else -> emptyList()
        }
        if (command.isEmpty()) {
            delegate.actionPerformed(e)
            return
        }

        log.info("AikenReformatAction: formatting ${psiFile.name} with $command")
        val text = editor.document.text
        val formatted = runFormatter(command, text) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(formatted)
        }
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)

        if (psiFile != null && editor != null && isAiken(psiFile.virtualFile)) {
            // Built-in action disables itself because our language has no formatter registered;
            // we still want it available to run the CLI formatter.
            e.presentation.isEnabledAndVisible = true
            e.presentation.text = delegate.templatePresentation.text
            e.presentation.description = delegate.templatePresentation.description
        } else {
            delegate.update(e)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = delegate.actionUpdateThread

    private fun isAiken(file: VirtualFile?): Boolean =
        file?.fileType == AikenFileType || file?.fileType == UplcFileType

    private fun runFormatter(command: List<String>, stdin: String): String? =
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { it.write(stdin) }
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) output else {
                log.warn("Aiken reformat failed: exit=$exit, output=$output")
                null
            }
        } catch (e: Exception) {
            log.warn("Aiken reformat invocation failed", e)
            null
        }
}
