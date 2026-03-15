package com.medusalabs.aiken.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import com.medusalabs.aiken.tooling.AikenNodeToolchain

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
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val executable = AikenNodeToolchain.resolvePreferredAikenExecutable(project)
        val command = when (fileType) {
            AikenFileType -> listOf(executable, "fmt", "--stdin")
            UplcFileType -> listOf(executable, "uplc", "fmt", "--stdin")
            else -> null
        } ?: return

        val input = AikenFormatterInputNormalizer.normalizeWhitespaceOnlyLines(document.text)
        val formatted = runFormatter(command, input) ?: return
        if (formatted == document.text) return

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
