package com.medusalabs.aiken.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private val aikenGitStartupLog: Logger = Logger.getInstance(AikenGitVcsMappingStartupActivity::class.java)

fun ensureAikenProjectModule(project: Project, basePath: String) {
    if (ModuleManager.getInstance(project).modules.isNotEmpty()) return
    val ideaDir = Paths.get(basePath, ".idea")
    Files.createDirectories(ideaDir)
    val imlPath = ideaDir.resolve("${project.name}.iml").toAbsolutePath().toString()

    WriteAction.run<RuntimeException> {
        if (ModuleManager.getInstance(project).modules.isNotEmpty()) return@run

        val module = ModuleManager.getInstance(project).newModule(imlPath, EmptyModuleType.EMPTY_MODULE)

        val rootModel = ModuleRootManager.getInstance(module).modifiableModel
        rootModel.addContentEntry(VfsUtilCore.pathToUrl(basePath))
        rootModel.inheritSdk()
        rootModel.commit()
    }
    project.save()
}

private fun ensureGitRootMapping(project: Project, basePath: String) {
    val vcsManager = ProjectLevelVcsManager.getInstance(project)
    val existingMappings = vcsManager.getDirectoryMappings()
    if (existingMappings.any { it.directory == basePath && it.vcs == "Git" }) {
        return
    }

    val updatedMappings = existingMappings
        .filterNot { it.directory == basePath }
        .toMutableList()
    updatedMappings += VcsDirectoryMapping(basePath, "Git")
    vcsManager.setDirectoryMappings(updatedMappings)
    project.save()
}

class AikenGitVcsMappingStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) return

        val root = File(basePath)
        if (!root.isDirectory) return
        if (!File(root, ".git").isDirectory) return
        if (!File(root, "aiken.toml").isFile) return

        try {
            ensureAikenProjectModule(project, basePath)
            ensureGitRootMapping(project, basePath)
        } catch (t: Throwable) {
            aikenGitStartupLog.warn("Failed to initialize module/VCS state for Aiken project at $basePath", t)
        }
    }
}
