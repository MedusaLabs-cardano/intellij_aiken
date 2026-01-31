package com.medusalabs.aiken.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class AikenLspDiagnosticsStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        val service = project.getService(AikenLspDiagnosticsProjectViewService::class.java)
        service?.refreshOpenFiles()
    }
}
