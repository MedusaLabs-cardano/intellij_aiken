package com.medusalabs.aiken.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class AikenLspDiagnosticsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(AikenLspDiagnosticsProjectViewService::class.java)
    }
}
