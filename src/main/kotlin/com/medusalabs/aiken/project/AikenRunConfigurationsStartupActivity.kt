package com.medusalabs.aiken.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

private val AIKEN_RUN_CONFIGURATIONS_STARTUP_LOG: Logger =
    Logger.getInstance(AikenRunConfigurationsStartupActivity::class.java)

class AikenRunConfigurationsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) return

        val baseDirectory = VfsUtil.findFile(Path.of(basePath), true) ?: return
        ensureDefaultRunConfigurationsForBaseDirectory(baseDirectory)
    }

    internal fun ensureDefaultRunConfigurationsForBaseDirectory(baseDirectory: com.intellij.openapi.vfs.VirtualFile) {
        val projectRoot = AikenProjectRoots.findRootForFile(baseDirectory) ?: return
        if (AikenProjectScaffolder.defaultRunConfigurationsInitialized(projectRoot)) return

        try {
            AikenProjectScaffolder.ensureDefaultRunConfigurations(projectRoot, markInitialized = true)
        } catch (t: Throwable) {
            AIKEN_RUN_CONFIGURATIONS_STARTUP_LOG.warn(
                "Failed to initialize default Aiken run configurations for ${projectRoot.path}",
                t
            )
        }
    }
}
