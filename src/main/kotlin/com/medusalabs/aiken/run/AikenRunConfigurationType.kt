package com.medusalabs.aiken.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.openapi.project.Project
import com.medusalabs.aiken.icons.AikenIcons

class AikenRunConfigurationType : ConfigurationTypeBase(
    "AIKEN_RUN_CONFIGURATION",
    "Aiken",
    "Run Aiken CLI commands (build, address, check)",
    AikenIcons.AIKEN
) {
    init {
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.BUILD, "Build"))
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.ADDRESS, "Address"))
        addFactory(AikenRunConfigurationFactory(this, AikenRunCommand.CHECK, "Check"))
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
        )
    }

    override fun createConfiguration(
        name: String?,
        template: com.intellij.execution.configurations.RunConfiguration
    ): com.intellij.execution.configurations.RunConfiguration {
        val resolvedName = if (name.isUnnamedLike()) displayName else name
        return super.createConfiguration(resolvedName, template)
    }

    override fun getId(): String =
        when (presetCommand) {
            AikenRunCommand.CHECK -> "AikenRunConfigurationFactory"
            AikenRunCommand.BUILD -> "AikenBuildRunConfigurationFactory"
            AikenRunCommand.ADDRESS -> "AikenAddressRunConfigurationFactory"
        }

    override fun getName(): String = displayName

    private fun String?.isUnnamedLike(): Boolean {
        val trimmed = this?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        return UNNAMED_NAME_REGEX.matches(trimmed)
    }

    companion object {
        private val UNNAMED_NAME_REGEX = Regex("""^Unnamed(?:\s*\(\d+\))?$""", RegexOption.IGNORE_CASE)
    }
}
