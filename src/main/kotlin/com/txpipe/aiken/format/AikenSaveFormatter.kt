package com.txpipe.aiken.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType

/**
 * Simple on-save formatter that shells out to the Aiken CLI.
 * Uses `aiken fmt --stdin` for .ak files and `aiken uplc fmt --stdin` for .uplc files.
 * If the CLI is unavailable or fails, we leave the document untouched.
 */
class AikenSaveFormatter : FileDocumentManagerListener {
    private val log = Logger.getInstance(AikenSaveFormatter::class.java)

    override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val fileType = file.fileType
        val command = when (fileType) {
            AikenFileType -> listOf("aiken", "fmt", "--stdin")
            UplcFileType -> listOf("aiken", "uplc", "fmt", "--stdin")
            else -> null
        } ?: return

        val formatted = runFormatter(command, document.text) ?: return

        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(formatted)
        }
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
                log.warn("Aiken formatter failed: exit=$exit, output=$output")
                null
            }
        } catch (e: Exception) {
            log.warn("Aiken formatter invocation failed", e)
            null
        }
}
