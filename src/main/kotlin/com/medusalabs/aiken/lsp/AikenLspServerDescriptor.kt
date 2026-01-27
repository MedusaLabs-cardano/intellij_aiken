package com.medusalabs.aiken.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenLspServerDescriptor(project: Project) :
    ProjectWideLspServerDescriptor(project, "Aiken Language Server") {
    private val log = Logger.getInstance(AikenLspServerDescriptor::class.java)

    override fun isSupportedFile(file: VirtualFile): Boolean {
        val ft = file.fileType
        return ft == AikenFileType || ft == UplcFileType
    }

    override val lspCustomization: LspCustomization =
        object : LspCustomization() {
            override val formattingCustomizer: LspFormattingCustomizer =
                object : LspFormattingSupport() {
                    override fun shouldFormatThisFileExclusivelyByServer(
                        file: VirtualFile,
                        ideCanFormatThisFileItself: Boolean,
                        serverExplicitlyWantsToFormatThisFile: Boolean
                    ): Boolean {
                        // Ensure Reformat Code uses LSP for our files, but don't override a native formatter
                        // if one is added in the future.
                        val supported = isSupportedFile(file)
                        val decision = supported && !ideCanFormatThisFileItself
                        log.info(
                            "Aiken LSP formatting decision for ${file.name}: supported=$supported " +
                                "ideCanFormat=$ideCanFormatThisFileItself serverWants=$serverExplicitlyWantsToFormatThisFile -> $decision"
                        )
                        return decision
                    }
                }
        }

    override fun createCommandLine(): GeneralCommandLine {
        val executable = if (SystemInfo.isWindows) "aiken.exe" else "aiken"
        return GeneralCommandLine(executable, "lsp").apply {
            withEnvironment(System.getenv())
            project.basePath?.let { withWorkDirectory(it) }
        }
    }
}
