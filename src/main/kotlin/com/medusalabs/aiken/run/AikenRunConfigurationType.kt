package com.medusalabs.aiken.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.openapi.project.Project
import com.medusalabs.aiken.icons.AikenIcons

class AikenRunConfigurationType : ConfigurationTypeBase(
    "AIKEN_RUN_CONFIGURATION",
    "Aiken",
    "Run Aiken workflows (Build blueprint, Run checks, Parametrize, Make artifacts, Clean artifacts)",
    AikenIcons.AIKEN
) {
    init {
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.BUILD, "Build blueprint"))
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.ADDRESS, "Make artifacts"))
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.CLEAN, "Clean artifacts"))
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.APPLY, "Parametrize"))
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.CHECK, "Run checks"))
    }
}

internal class AikenRunConfigurationFactory(
    type: ConfigurationType,
    internal val presetCommand: AikenRunCommand,
    private val displayName: String
) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): AikenRunConfiguration {
        return AikenRunConfiguration(
            project = project,
            factory = this,
            name = displayName,
            initialCommand = presetCommand
        ).also { it.setGeneratedName() }
    }

    override fun getId(): String =
        when (presetCommand) {
            AikenRunCommand.CHECK -> "AikenRunConfigurationFactory"
            AikenRunCommand.BUILD -> "AikenBuildRunConfigurationFactory"
            AikenRunCommand.ADDRESS -> "AikenArtifactsRunConfigurationFactory"
            AikenRunCommand.CLEAN -> "AikenCleanRunConfigurationFactory"
            AikenRunCommand.APPLY -> "AikenApplyRunConfigurationFactory"
            AikenRunCommand.CONVERT -> "AikenConvertRunConfigurationFactory"
        }

    override fun getName(): String = displayName
}
