package com.txpipe.aiken.lsp

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType

/**
 * Ensures IntelliJ delegates formatting requests to the Aiken LSP server
 * for .ak and .uplc files.
 */
class AikenLspFormattingSupport : LspFormattingSupport() {
    private val log = Logger.getInstance(AikenLspFormattingSupport::class.java)

    override fun shouldFormatThisFileExclusivelyByServer(
        file: VirtualFile,
        formatByServerAvailable: Boolean,
        formatByIdeAvailable: Boolean
    ): Boolean {
        val ft = file.fileType
        val ours = ft == AikenFileType || ft == UplcFileType
        val decision = ours && formatByServerAvailable
        log.info("LSP formatting check for ${file.name}: ours=$ours serverAvail=$formatByServerAvailable ideAvail=$formatByIdeAvailable -> $decision")
        return decision
    }
}
