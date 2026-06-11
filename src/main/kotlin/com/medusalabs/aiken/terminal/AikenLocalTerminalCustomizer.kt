@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.medusalabs.aiken.terminal

import com.intellij.openapi.project.Project
import com.medusalabs.aiken.tooling.AikenNodeToolchain
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

class AikenLocalTerminalCustomizer : LocalTerminalCustomizer() {
    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<out String>,
        envs: MutableMap<String, String>
    ): Array<out String> {
        AikenNodeToolchain.applyProjectLocalAikenToTerminalEnvironment(project, envs)
        return command
    }
}
