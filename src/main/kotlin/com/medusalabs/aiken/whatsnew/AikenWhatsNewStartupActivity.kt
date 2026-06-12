package com.medusalabs.aiken.whatsnew

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

private val aikenWhatsNewLogger: Logger = Logger.getInstance(AikenWhatsNewStartupActivity::class.java)
private const val aikenWhatsNewShownVersionKey = "com.medusalabs.aiken.whatsnew.shownVersion"
private const val aikenWhatsNewTabTitle = "Aiken · What's New"
private const val aikenWhatsNewLatestResource = "/whatsnew/latest/index.html"

class AikenWhatsNewStartupActivity : ProjectActivity, PluginAware {
    @Volatile
    private var pluginDescriptor: PluginDescriptor? = null

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor
    }

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return

        val currentVersion = currentPluginVersion()
        if (currentVersion.isBlank()) return

        val props = PropertiesComponent.getInstance()
        if (props.getValue(aikenWhatsNewShownVersionKey) == currentVersion) return

        subscribeToLafChanges(project)
        val html = loadHtml(currentVersion) ?: return

        ApplicationManager.getApplication().invokeLater {
            openOrRefreshTab(project, html)
            props.setValue(aikenWhatsNewShownVersionKey, currentVersion)
        }
    }

    private fun loadHtml(version: String): String? {
        val escapedVersion = StringUtil.escapeXmlEntities(version)
        val themeVars = buildThemeCssVariables()
        val versionResourcePath = "/whatsnew/$version/index.html"
        var baseResourcePath = versionResourcePath
        var template = readResource(versionResourcePath)
        if (template == null) {
            baseResourcePath = aikenWhatsNewLatestResource
            template = readResource(aikenWhatsNewLatestResource)
        }
        template ?: return null
        val baseResourceDir = baseResourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        return inlineImageResources(template, baseResourceDir)
            .replace("{{VERSION}}", escapedVersion)
            .replace("/*{{IDE_THEME_VARS}}*/", themeVars)
    }

    private fun inlineImageResources(html: String, baseResourceDir: String): String {
        val imageSourcePattern = Regex("""(<img\b[^>]*\bsrc=")([^"]+)(")""")
        return imageSourcePattern.replace(html) { match ->
            val prefix = match.groupValues[1]
            val source = match.groupValues[2]
            val suffix = match.groupValues[3]
            if (source.startsWith("data:") || source.startsWith("http://") || source.startsWith("https://")) {
                match.value
            } else {
                val resourcePath = resolveResourcePath(baseResourceDir, source)
                val bytes = readResourceBytes(resourcePath)
                if (bytes == null) {
                    aikenWhatsNewLogger.warn("Failed to inline What's New image resource: $resourcePath")
                    match.value
                } else {
                    val mimeType = imageMimeType(resourcePath)
                    val encoded = Base64.getEncoder().encodeToString(bytes)
                    prefix + "data:$mimeType;base64,$encoded" + suffix
                }
            }
        }
    }

    private fun resolveResourcePath(baseResourceDir: String, source: String): String {
        if (source.startsWith('/')) return source
        val parts = (baseResourceDir.trimEnd('/') + "/" + source).split('/')
        val normalized = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "",
                "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
                else -> normalized.addLast(part)
            }
        }
        return "/" + normalized.joinToString("/")
    }

    private fun imageMimeType(path: String): String =
        when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)) {
            "jpg",
            "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            else -> "image/png"
        }

    private fun readResourceBytes(path: String): ByteArray? {
        return try {
            javaClass.getResourceAsStream(path)?.use { stream ->
                stream.readBytes()
            }
        } catch (t: Throwable) {
                aikenWhatsNewLogger.warn("Failed to read What's New resource: $path", t)
                null
            }
    }

    private fun readResource(path: String): String? {
        return try {
            readResourceBytes(path)?.let { bytes ->
                String(bytes, StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
                aikenWhatsNewLogger.warn("Failed to read What's New resource: $path", t)
                null
            }
    }

    private fun subscribeToLafChanges(project: Project) {
        val connection = ApplicationManager.getApplication().messageBus.connect(project.service<AikenWhatsNewListenerDisposable>())
        connection.subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                val version = currentPluginVersion().takeIf { it.isNotBlank() } ?: return@LafManagerListener
                val html = loadHtml(version) ?: return@LafManagerListener
                ApplicationManager.getApplication().invokeLater {
                    val manager = FileEditorManager.getInstance(project)
                    if (manager.openFiles.none { it.name == aikenWhatsNewTabTitle }) return@invokeLater
                    openOrRefreshTab(project, html)
                }
            }
        )
    }

    private fun openOrRefreshTab(project: Project, html: String) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles
            .filter { it.name == aikenWhatsNewTabTitle }
            .forEach { manager.closeFile(it) }
        HTMLEditorProvider.openEditor(project, aikenWhatsNewTabTitle, html)
    }

    private fun currentPluginVersion(): String = (
        pluginDescriptor
            ?: (javaClass.classLoader as? PluginAwareClassLoader)?.pluginDescriptor
        )
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

        val sans = "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"Noto Sans\""
        val mono = "\"JetBrains Mono\", ui-monospace, SFMono-Regular, Menlo"

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
}

@Service(Service.Level.PROJECT)
private class AikenWhatsNewListenerDisposable : Disposable {
    override fun dispose() = Unit
}
