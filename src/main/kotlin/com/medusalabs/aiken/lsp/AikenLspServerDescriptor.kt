package com.medusalabs.aiken.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import org.eclipse.lsp4j.PublishDiagnosticsParams

class AikenLspServerDescriptor(project: Project) :
    ProjectWideLspServerDescriptor(project, "Aiken Language Server") {
    override fun isSupportedFile(file: VirtualFile): Boolean {
        val ft = file.fileType
        return ft == AikenFileType || ft == UplcFileType
    }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient {
        val delegatingHandler =
            object : LspServerNotificationsHandler by handler {
                override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                    handler.publishDiagnostics(params)

                    val uri = params.uri ?: return
                    val file = findFileByUri(uri) ?: return
                    if (!isSupportedFile(file)) return

                    val service =
                        project.getService(AikenLspDiagnosticsProjectViewService::class.java) ?: return
                    service.onPublishDiagnostics(file, params.diagnostics ?: emptyList())
                }
            }

        return super.createLsp4jClient(delegatingHandler)
    }

    override fun createCommandLine(): GeneralCommandLine {
        val executable = if (SystemInfo.isWindows) "aiken.exe" else "aiken"
        return GeneralCommandLine(executable, "lsp", "--stdio").apply {
            withEnvironment(System.getenv())
            project.basePath?.let { withWorkDirectory(it) }
        }
    }
}
