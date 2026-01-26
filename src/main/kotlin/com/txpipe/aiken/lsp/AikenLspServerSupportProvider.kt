package com.txpipe.aiken.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType

/**
 * Hooks IntelliJ LSP API to start the Aiken language server when Aiken/UPLC files open.
 */
class AikenLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (file.fileType != AikenFileType && file.fileType != UplcFileType) return
        serverStarter.ensureServerStarted(AikenLspServerDescriptor(project))
    }
}
