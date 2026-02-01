package com.medusalabs.aiken.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.ExternalFormatProcessor
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenExternalFormatProcessor : ExternalFormatProcessor {
    private val log = Logger.getInstance(AikenExternalFormatProcessor::class.java)

    override fun getId(): String = "aiken"

    override fun activeForFile(file: PsiFile): Boolean {
        val fileType = file.fileType
        return fileType == AikenFileType || fileType == UplcFileType
    }

    override fun format(
        file: PsiFile,
        range: TextRange,
        canChangeWhiteSpacesOnly: Boolean,
        canChangeIndentation: Boolean,
        isIndentEnabled: Boolean,
        context: Int
    ): TextRange {
        val fileType = file.fileType
        val command = when (fileType) {
            AikenFileType -> listOf("aiken", "fmt", "--stdin")
            UplcFileType -> listOf("aiken", "uplc", "fmt", "--stdin")
            else -> null
        } ?: return range

        val project = file.project
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return range
        val formatted = runFormatter(command, document.text) ?: return range
        if (formatted == document.text) return range

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(formatted)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        return TextRange(0, formatted.length)
    }

    override fun indent(file: PsiFile, lineStartOffset: Int): String? = null

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
