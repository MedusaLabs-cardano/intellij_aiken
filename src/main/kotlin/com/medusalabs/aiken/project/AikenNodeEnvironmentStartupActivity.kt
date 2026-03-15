package com.medusalabs.aiken.project

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ide.util.PropertiesComponent
import com.medusalabs.aiken.tooling.AikenProjectToolchainSettings
import com.medusalabs.aiken.tooling.AikenToolchainMode
import com.medusalabs.aiken.tooling.AikenNodeToolchain
import java.io.File

class AikenNodeEnvironmentStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!looksLikeAikenProject(project)) {
            return
        }

        val settings = project.service<AikenProjectToolchainSettings>()
        if (settings.getMode() == AikenToolchainMode.GLOBAL) {
            PropertiesComponent.getInstance(project).unsetValue(NPM_WARNING_SHOWN_KEY)
            return
        }
        if (AikenNodeToolchain.resolveProjectLocalAikenExecutable(project.basePath) != null) {
            PropertiesComponent.getInstance(project).unsetValue(NPM_WARNING_SHOWN_KEY)
            return
        }

        val availability = AikenNodeToolchain.describeNpmAvailability(project)
        if (availability.available) {
            PropertiesComponent.getInstance(project).unsetValue(NPM_WARNING_SHOWN_KEY)
            return
        }

        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(NPM_WARNING_SHOWN_KEY, false)) {
            return
        }
        properties.setValue(NPM_WARNING_SHOWN_KEY, true)

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                "Node.js / npm is required for local Aiken toolchains",
                "${availability.message} The project wizard and local runners can use the IDE Node.js configuration if you set it up here.",
                NotificationType.WARNING
            )
            .addAction(
                NotificationAction.createSimple("Configure Node.js…") {
                    if (AikenNodeToolchain.openNodeInterpreterDialog(project)) {
                        PropertiesComponent.getInstance(project).unsetValue(NPM_WARNING_SHOWN_KEY)
                    }
                }
            )
            .notify(project)
    }

    private fun looksLikeAikenProject(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        if (File(basePath, "aiken.toml").isFile) {
            return true
        }
        val baseVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath) ?: return false
        return AikenProjectRoots.findRootForFile(baseVirtualFile) != null
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID = "Aiken Toolchain"
        const val NPM_WARNING_SHOWN_KEY = "aiken.toolchain.npm.warning.shown"
    }
}
