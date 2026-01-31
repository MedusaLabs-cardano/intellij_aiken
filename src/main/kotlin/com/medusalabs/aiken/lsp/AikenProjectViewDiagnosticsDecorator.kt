package com.medusalabs.aiken.lsp

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.project.Project
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenProjectViewDiagnosticsDecorator(private val project: Project) : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (file.fileType != AikenFileType && file.fileType != UplcFileType) return
        val service = project.getService(AikenLspDiagnosticsProjectViewService::class.java) ?: return
        when (service.getSeverity(file)) {
            AikenLspDiagnosticsProjectViewService.FileSeverity.ERROR ->
                data.setAttributesKey(CodeInsightColors.ERRORS_ATTRIBUTES)
            AikenLspDiagnosticsProjectViewService.FileSeverity.WARNING ->
                data.setAttributesKey(CodeInsightColors.WARNINGS_ATTRIBUTES)
            AikenLspDiagnosticsProjectViewService.FileSeverity.INFO ->
                data.setAttributesKey(CodeInsightColors.WEAK_WARNING_ATTRIBUTES)
            null -> {}
        }
    }
}
