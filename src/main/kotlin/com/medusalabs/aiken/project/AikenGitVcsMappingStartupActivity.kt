package com.medusalabs.aiken.project

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File
import java.nio.file.Paths

class AikenGitVcsMappingStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) return

        val root = File(basePath)
        if (!root.isDirectory) return
        if (!File(root, ".git").isDirectory) return
        if (!File(root, "aiken.toml").isFile) return

        ensureProjectModule(project, basePath)
    }

    companion object {
        fun ensureProjectModule(project: Project, basePath: String) {
            if (ModuleManager.getInstance(project).modules.isNotEmpty()) return
            val ideaDir = Paths.get(basePath, ".idea")
            val imlPath = ideaDir.resolve("${project.name}.iml").toAbsolutePath().toString()

            WriteAction.run<RuntimeException> {
                if (ModuleManager.getInstance(project).modules.isNotEmpty()) return@run

                val module = ModuleManager.getInstance(project).newModule(imlPath, "EMPTY_MODULE")

                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(VfsUtilCore.pathToUrl(basePath))
                rootModel.inheritSdk()
                rootModel.commit()
            }
            project.save()
        }
    }
}
