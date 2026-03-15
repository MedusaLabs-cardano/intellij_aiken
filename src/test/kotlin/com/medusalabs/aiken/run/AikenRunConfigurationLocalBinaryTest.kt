package com.medusalabs.aiken.run

import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.tooling.AikenProjectToolchainSettings
import com.medusalabs.aiken.tooling.AikenToolchainMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class AikenRunConfigurationLocalBinaryTest : AikenPlatformTestCase() {
    @Test
    fun resolveAikenExecutablePrefersProjectLocalBinaryForDefaultPath() {
        val projectDir = Files.createTempDirectory("aiken-local-runner")
        val binDir = projectDir
            .resolve("bin")
            .resolve("node_modules")
            .resolve("@aiken-lang")
            .resolve("aiken")
            .resolve("node_modules")
            .resolve(".bin_real")
        Files.createDirectories(binDir)
        val binaryName = if (com.intellij.openapi.util.SystemInfo.isWindows) "aiken.cmd" else "aiken"
        val binaryPath = binDir.resolve(binaryName)
        val content = if (com.intellij.openapi.util.SystemInfo.isWindows) {
            "@echo off\r\necho aiken v1.1.21\r\n"
        } else {
            "#!/bin/sh\necho aiken v1.1.21\n"
        }
        Files.writeString(binaryPath, content)
        binaryPath.toFile().setExecutable(true)

        val configuration = buildConfiguration()
        configuration.projectDirectory = projectDir.toString()
        configuration.aikenBinaryPath = "aiken"

        assertEquals(binaryPath.toString(), configuration.resolveAikenExecutableForTest())
    }

    @Test
    fun resolveAikenExecutableKeepsExplicitCustomPath() {
        val configuration = buildConfiguration()
        configuration.projectDirectory = Files.createTempDirectory("aiken-custom-runner").toString()
        configuration.aikenBinaryPath = "/custom/tools/aiken"

        assertEquals("/custom/tools/aiken", configuration.resolveAikenExecutableForTest())
    }

    @Test
    fun resolveAikenExecutableUsesProjectGlobalToolchainWhenConfigured() {
        project.getService(AikenProjectToolchainSettings::class.java)
            .update(AikenToolchainMode.GLOBAL, "/global/aiken", "1.1.21")

        val configuration = buildConfiguration()
        configuration.projectDirectory = Files.createTempDirectory("aiken-global-runner").toString()
        configuration.aikenBinaryPath = "aiken"

        assertEquals("/global/aiken", configuration.resolveAikenExecutableForTest())
    }

    private fun buildConfiguration(): AikenRunConfiguration {
        val type = AikenRunConfigurationType()
        val factory =
            type.configurationFactories
                .filterIsInstance<AikenRunConfigurationFactory>()
                .first { it.presetCommand == AikenRunCommand.CHECK }
        return factory.createTemplateConfiguration(project)
    }

    private fun AikenRunConfiguration.resolveAikenExecutableForTest(): String {
        val method = AikenRunConfiguration::class.java.getDeclaredMethod("resolveAikenExecutable")
        method.isAccessible = true
        return method.invoke(this) as String
    }
}
