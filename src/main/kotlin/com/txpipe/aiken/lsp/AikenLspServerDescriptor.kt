package com.txpipe.aiken.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.txpipe.aiken.lang.AikenFileType
import com.txpipe.aiken.lang.UplcFileType

class AikenLspServerDescriptor(project: Project) :
    ProjectWideLspServerDescriptor(project, "Aiken Language Server") {

    override fun isSupportedFile(file: VirtualFile): Boolean {
        val ft = file.fileType
        return ft == AikenFileType || ft == UplcFileType
    }

    override fun createCommandLine(): GeneralCommandLine {
        val executable = if (SystemInfo.isWindows) "aiken.exe" else "aiken"
        return GeneralCommandLine(executable, "lsp").apply {
            withEnvironment(System.getenv())
            project.basePath?.let { withWorkDirectory(it) }
        }
    }
}
