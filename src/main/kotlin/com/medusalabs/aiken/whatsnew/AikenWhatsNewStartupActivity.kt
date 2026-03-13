package com.medusalabs.aiken.whatsnew

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.nio.charset.StandardCharsets
import java.util.Locale

class AikenWhatsNewStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return

        val currentVersion = currentPluginVersion()
        if (currentVersion.isBlank()) return
        subscribeToLafChanges(project)

        val props = PropertiesComponent.getInstance()
        val shownVersion = props.getValue(SHOWN_VERSION_KEY)?.trim().orEmpty()
//        if (shownVersion == currentVersion) return
        val html = loadHtml(currentVersion) ?: return

        ApplicationManager.getApplication().invokeLater {
            openOrRefreshTab(project, html)
            props.setValue(SHOWN_VERSION_KEY, currentVersion)
        }
    }

    private fun loadHtml(version: String): String? {
        val escapedVersion = StringUtil.escapeXmlEntities(version)
        val themeVars = buildThemeCssVariables()
        val template = readResource("/whatsnew/$version.html")
            ?: readResource("/whatsnew/latest.html")
            ?: return null
        return template
            .replace("{{VERSION}}", escapedVersion)
            .replace("{{IDE_THEME_VARS}}", themeVars)
    }

    private fun readResource(path: String): String? {
        return try {
            javaClass.getResourceAsStream(path)?.use { stream ->
                String(stream.readBytes(), StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to read What's New resource: $path", t)
            null
        }
    }

    private fun subscribeToLafChanges(project: Project) {
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                val version = currentPluginVersion().takeIf { it.isNotBlank() } ?: return@LafManagerListener
                val html = loadHtml(version) ?: return@LafManagerListener
                ApplicationManager.getApplication().invokeLater {
                    val manager = FileEditorManager.getInstance(project)
                    if (manager.openFiles.none { it.name == TAB_TITLE }) return@invokeLater
                    openOrRefreshTab(project, html)
                }
            }
        )
    }

    private fun openOrRefreshTab(project: Project, html: String) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles
            .filter { it.name == TAB_TITLE }
            .forEach { manager.closeFile(it) }
        HTMLEditorProvider.openEditor(project, TAB_TITLE, html)
    }

    private fun currentPluginVersion(): String = PluginManagerCore
        .getPlugin(PLUGIN_ID)
        ?.version
        ?.trim()
        .orEmpty()

    private fun buildThemeCssVariables(): String {
        val bg = UIUtil.getPanelBackground()
        val fg = UIUtil.getLabelForeground()
        val borderBase = JBColor.namedColor(
            "Component.borderColor",
            JBColor(Color(0xD0, 0xD7, 0xDE), Color(0x55, 0x5A, 0x60))
        )

        val sans = """-apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", sans-serif"""
        val mono = """"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace"""

        return buildString {
            appendLine(":root {")
            appendLine("  --jb-bg: ${bg.toCssRgb()};")
            appendLine("  --jb-fg: ${fg.toCssRgb()};")
            appendLine("  --jb-border: ${borderBase.toCssRgb(0.35)};")
            appendLine("  --jb-soft-bg: ${fg.toCssRgb(0.05)};")
            appendLine("  --jb-soft-bg-2: ${fg.toCssRgb(0.08)};")
            appendLine("  --jb-code-bg: ${fg.toCssRgb(0.12)};")
            appendLine("  --jb-font-sans: $sans;")
            appendLine("  --jb-font-mono: $mono;")
            append('}')
        }
    }

    private fun Color.toCssRgb(alpha: Double? = null): String {
        if (alpha == null) return "rgb($red, $green, $blue)"
        val normalized = alpha.coerceIn(0.0, 1.0)
        val a = String.format(Locale.US, "%.3f", normalized).trimEnd('0').trimEnd('.')
        return "rgba($red, $green, $blue, $a)"
    }

    private companion object {
        val logger: Logger = Logger.getInstance(AikenWhatsNewStartupActivity::class.java)
        val PLUGIN_ID: PluginId = PluginId.getId("com.medusalabs.aiken")
        const val SHOWN_VERSION_KEY = "com.medusalabs.aiken.whatsnew.shownVersion"
        const val TAB_TITLE = "Aiken · What's New"
    }
}
