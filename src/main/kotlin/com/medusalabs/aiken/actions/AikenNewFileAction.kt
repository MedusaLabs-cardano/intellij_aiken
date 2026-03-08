package com.medusalabs.aiken.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiDirectory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.medusalabs.aiken.icons.AikenIcons
import com.medusalabs.aiken.naming.AikenNamingRules
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.io.path.deleteIfExists

class AikenNewFileAction : AnAction("Aiken File", "Create a new Aiken file", AikenIcons.AIKEN), DumbAware {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasTarget = e.getData(LangDataKeys.IDE_VIEW)?.directories?.isNotEmpty() == true
        e.presentation.isEnabledAndVisible = project != null && hasTarget
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()
        if (directory == null) {
            Messages.showErrorDialog(project, "Please select a target directory in Project View.", "New Aiken File")
            return
        }

        val dialog = AikenNewFileDialog(project)
        if (!dialog.showAndGet()) return

        val rawName = dialog.fileName.trim()
        val fileName = if (rawName.endsWith(".ak")) rawName else "$rawName.ak"
        val content = when (dialog.templateKind) {
            AikenTemplateKind.VALIDATOR -> {
                val text = AikenValidatorTemplateProvider.getTemplate(project)
                if (text == null) {
                    Messages.showErrorDialog(
                        project,
                        "Failed to resolve validator template. Please ensure `aiken` is installed and available in PATH.",
                        "New Aiken File"
                    )
                    return
                }
                text
            }
            AikenTemplateKind.TEST -> TEST_TEMPLATE
            AikenTemplateKind.COMMON -> ""
        }

        createFile(project, directory, fileName, content, e.getData(CommonDataKeys.EDITOR))
    }

    private fun createFile(
        project: Project,
        directory: PsiDirectory,
        fileName: String,
        content: String,
        editor: Editor?
    ) {
        if (directory.findFile(fileName) != null) {
            Messages.showErrorDialog(project, "File already exists: $fileName", "New Aiken File")
            return
        }

        try {
            WriteCommandAction.runWriteCommandAction(project, "Create Aiken File", null, Runnable {
                val psiFile = directory.createFile(fileName)
                psiFile.virtualFile.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
                val opened = FileEditorManager.getInstance(project).openFile(psiFile.virtualFile, true, true)
                if (editor != null && opened.isNotEmpty()) {
                    opened[0].preferredFocusedComponent?.requestFocusInWindow()
                }
            })
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Failed to create file `$fileName`.\n${t.message ?: "Unknown error"}",
                "New Aiken File"
            )
        }
    }
}

private class AikenNewFileDialog(project: Project) : DialogWrapper(project) {
    private val nameField = JBTextField()
    private val templateCombo = ComboBox(arrayOf(
        AikenTemplateKind.VALIDATOR,
        AikenTemplateKind.TEST,
        AikenTemplateKind.COMMON
    ))

    val fileName: String
        get() = nameField.text

    val templateKind: AikenTemplateKind
        get() = templateCombo.selectedItem as AikenTemplateKind

    init {
        title = "New Aiken File"
        nameField.columns = 28
        nameField.emptyText.text = "e.g. validator, test_module"
        templateCombo.prototypeDisplayValue = AikenTemplateKind.VALIDATOR
        setHorizontalStretch(0.9f)
        init()
        initValidation()
        nameField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = refreshValidationState()
                override fun removeUpdate(e: DocumentEvent?) = refreshValidationState()
                override fun changedUpdate(e: DocumentEvent?) = refreshValidationState()
            }
        )
        refreshValidationState()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).resizableColumn()
        }
        row("Template:") {
            cell(templateCombo)
                .comment("Validator scaffold, test scaffold, or empty file.")
        }
    }

    override fun doValidate(): ValidationInfo? {
        return validationInfo()
    }

    private fun refreshValidationState() {
        val info = validationInfo()
        isOKActionEnabled = info == null
        setErrorText(info?.message, info?.component)
    }

    private fun validationInfo(): ValidationInfo? {
        val value = fileName.trim()
        val message = AikenNamingRules.validateAikenFileName(value) ?: return null
        return ValidationInfo(message, nameField)
    }
}

private enum class AikenTemplateKind(private val label: String) {
    VALIDATOR("Aiken Validator"),
    TEST("Aiken Test"),
    COMMON("Common File");

    override fun toString(): String = label
}

private object AikenValidatorTemplateProvider {
    private val logger = Logger.getInstance(AikenValidatorTemplateProvider::class.java)
    private val cacheRelativePath: Path = Path.of(".idea", "aiken", "validator_placeholder.ak")

    fun getTemplate(project: Project): String? {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) return null

        val cacheFile = Path.of(basePath).resolve(cacheRelativePath)
        readCache(cacheFile)?.let { return it }

        val fetched = fetchFromAikenNew(project)
        if (!fetched.isNullOrBlank()) {
            writeCache(cacheFile, fetched)
            return fetched
        }

        return null
    }

    private fun readCache(path: Path): String? {
        return try {
            if (!Files.exists(path)) {
                null
            } else {
                val text = Files.readString(path, StandardCharsets.UTF_8)
                text.takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCache(path: Path, text: String) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, text, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            logger.warn("Failed to write validator template cache: $path", t)
        }
    }

    private fun fetchFromAikenNew(project: Project): String? {
        return try {
            runWithProgress(project, "Resolving Aiken validator template") {
                loadTemplateFromCli()
            }
        } catch (t: Throwable) {
            logger.warn("Failed to fetch validator template from `aiken new`", t)
            null
        }
    }

    private fun loadTemplateFromCli(): String? {
        val tempRoot = Files.createTempDirectory("aiken-template-")
        try {
            val cmd = listOf("aiken", "new", "codex/template")
            val process = ProcessBuilder(cmd)
                .directory(tempRoot.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("`aiken new` failed (exit=$exitCode): ${output.trim()}")
                return null
            }

            val projectDir = tempRoot.resolve("template")
            val candidate = projectDir.resolve("validators").resolve("placeholder.ak")
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
            }

            Files.walk(tempRoot).use { stream ->
                val discovered = stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == "placeholder.ak" }
                    .findFirst()
                    .orElse(null)
                if (discovered != null) {
                    return Files.readString(discovered, StandardCharsets.UTF_8)
                }
            }
            return null
        } finally {
            deleteTree(tempRoot)
        }
    }

    private fun <T> runWithProgress(project: Project, title: String, action: () -> T): T? {
        var result: T? = null
        var failure: Throwable? = null
        val finished = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    val future = ApplicationManager.getApplication().executeOnPooledThread<T> { action() }
                    result = future.get()
                } catch (e: ExecutionException) {
                    failure = e.cause ?: e
                } catch (t: Throwable) {
                    failure = t
                }
            },
            title,
            true,
            project
        )
        if (!finished) return null
        failure?.let { throw it }
        return result
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { it.deleteIfExists() }
    }
}

private const val TEST_TEMPLATE = """
test example(){
  2+2 == 4
}
"""
