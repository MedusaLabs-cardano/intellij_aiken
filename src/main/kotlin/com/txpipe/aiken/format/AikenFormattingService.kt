package com.txpipe.aiken.format

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiFile
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType
import java.util.EnumSet

class AikenFormattingService : AsyncDocumentFormattingService() {
    private val log = Logger.getInstance(AikenFormattingService::class.java)

    override fun getName(): String = "Aiken CLI formatter"
    override fun getNotificationGroupId(): String = "AikenFormatting"

    override fun getFeatures(): MutableSet<FormattingService.Feature> =
        EnumSet.of(
            FormattingService.Feature.AD_HOC_FORMATTING,
            FormattingService.Feature.FORMAT_FRAGMENTS,
            FormattingService.Feature.OPTIMIZE_IMPORTS
        )

    override fun canFormat(psiFile: PsiFile): Boolean =
        psiFile.fileType == AikenFileType || psiFile.fileType == UplcFileType

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val file = request.ioFile ?: return null
        val command = when (file.extension) {
            "ak" -> listOf("aiken", "fmt", "--stdin")
            "uplc" -> listOf("aiken", "uplc", "fmt", "--stdin")
            else -> return null
        }
        log.info("AikenFormattingService: format request for ${file.name} with command=$command")

        return object : FormattingTask {
            @Volatile private var cancelled = false

            override fun run() {
                val result = runFormatter(command, request.documentText)
                if (cancelled) return
                if (result != null) {
                    request.onTextReady(result)
                } else {
                    request.onError("Aiken formatter failed", "aiken fmt returned non-zero exit code or error")
                }
            }

            override fun cancel(): Boolean {
                cancelled = true
                return true
            }

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
