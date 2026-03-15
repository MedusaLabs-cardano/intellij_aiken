package com.medusalabs.aiken.format

import com.intellij.formatting.FormattingRangesInfo
import com.intellij.formatting.service.FormattingService
import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import com.medusalabs.aiken.tooling.AikenNodeToolchain

class AikenFormattingService : FormattingService {
    private val log = Logger.getInstance(AikenFormattingService::class.java)

    override fun getFeatures(): Set<FormattingService.Feature> =
        setOf(FormattingService.Feature.AD_HOC_FORMATTING)

    override fun canFormat(file: PsiFile): Boolean {
        val fileType = file.fileType
        return fileType == AikenFileType || fileType == UplcFileType
    }

    override fun formatElement(element: PsiElement, canChangeWhiteSpacesOnly: Boolean): PsiElement {
        val file = element.containingFile ?: return element
        formatFileIfSupported(file)
        return element
    }

    override fun formatElement(element: PsiElement, range: TextRange, canChangeWhiteSpacesOnly: Boolean): PsiElement {
        val file = element.containingFile ?: return element
        formatFileIfSupported(file)
        return element
    }

    override fun formatRanges(
        file: PsiFile,
        rangesInfo: FormattingRangesInfo,
        canChangeWhiteSpacesOnly: Boolean,
        quickFormat: Boolean
    ) {
        formatFileIfSupported(file)
    }

    override fun getImportOptimizers(file: PsiFile): Set<ImportOptimizer> = emptySet()

    private fun formatFileIfSupported(file: PsiFile) {
        val fileType = file.fileType
        val executable = AikenNodeToolchain.resolvePreferredAikenExecutable(file.project)
        val command = when (fileType) {
            AikenFileType -> listOf(executable, "fmt", "--stdin")
            UplcFileType -> listOf(executable, "uplc", "fmt", "--stdin")
            else -> null
        } ?: return

        val project = file.project
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val input = AikenFormatterInputNormalizer.normalizeWhitespaceOnlyLines(document.text)
        val formatted = runFormatter(command, input) ?: return
        if (formatted == document.text) return

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(formatted)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun runFormatter(command: List<String>, stdin: String): String? =
        try {
            val process =
                ProcessBuilder(command)
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
