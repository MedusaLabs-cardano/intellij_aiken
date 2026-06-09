package com.medusalabs.aiken.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.medusalabs.aiken.AikenBundle
import com.medusalabs.aiken.icons.AikenIcons
import com.medusalabs.aiken.naming.AikenNamingRules
import com.medusalabs.aiken.tooling.AikenNodeToolchain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.io.path.deleteIfExists

class AikenNewFileAction : AnAction(), DumbAware {
    init {
        templatePresentation.text = AikenBundle.message("action.com.medusalabs.aiken.actions.AikenNewFileAction.text")
        templatePresentation.description =
            AikenBundle.message("action.com.medusalabs.aiken.actions.AikenNewFileAction.description")
        templatePresentation.icon = AikenIcons.AIKEN
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasTarget = e.getData(LangDataKeys.IDE_VIEW)?.directories?.isNotEmpty() == true
        e.presentation.isEnabledAndVisible = project != null && hasTarget
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()
        if (directory == null) {
            Messages.showErrorDialog(
                project,
                AikenBundle.message("aiken.new.file.error.no.target.directory"),
                AikenBundle.message("aiken.new.file.dialog.title")
            )
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
                        AikenBundle.message("aiken.new.file.error.validator.template.unresolved"),
                        AikenBundle.message("aiken.new.file.dialog.title")
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
            Messages.showErrorDialog(
                project,
                AikenBundle.message("aiken.new.file.error.file.exists", fileName),
                AikenBundle.message("aiken.new.file.dialog.title")
            )
            return
        }

        try {
            WriteCommandAction.writeCommandAction(project)
                .withName(AikenBundle.message("aiken.new.file.command.name"))
                .run<Throwable> {
                    val psiFile = directory.createFile(fileName)
                    psiFile.virtualFile.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
                    val opened = FileEditorManager.getInstance(project).openFile(psiFile.virtualFile, true, true)
                    if (editor != null && opened.isNotEmpty()) {
                        opened[0].preferredFocusedComponent?.requestFocusInWindow()
                    }
                }
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                AikenBundle.message(
                    "aiken.new.file.error.create.failed",
                    fileName,
                    t.message ?: AikenBundle.message("aiken.error.unknown")
                ),
                AikenBundle.message("aiken.new.file.dialog.title")
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
        title = AikenBundle.message("aiken.new.file.dialog.title")
        nameField.columns = 28
        nameField.emptyText.text = AikenBundle.message("aiken.new.file.name.placeholder")
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
        row(AikenBundle.message("aiken.new.file.name.label")) {
            cell(nameField).resizableColumn()
        }
        row(AikenBundle.message("aiken.new.file.template.label")) {
            cell(templateCombo)
                .comment(AikenBundle.message("aiken.new.file.template.comment"))
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

private enum class AikenTemplateKind(private val labelKey: String) {
    VALIDATOR("aiken.new.file.template.validator"),
    TEST("aiken.new.file.template.test"),
    COMMON("aiken.new.file.template.common");

    override fun toString(): String = AikenBundle.message(labelKey)
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
                val text = readProjectText(path)
                text.takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCache(path: Path, text: String) {
        try {
            writeProjectText(path, text)
        } catch (t: Throwable) {
            logger.warn("Failed to write validator template cache: $path", t)
        }
    }

    private fun readProjectText(path: Path): String {
        val virtualFile = VfsUtil.findFile(path, true)
            ?: throw java.io.FileNotFoundException(path.toString())
        return VfsUtil.loadText(virtualFile)
    }

    private fun writeProjectText(path: Path, text: String) {
        val parentPath = path.parent ?: throw java.io.IOException("Path has no parent: $path")
        ApplicationManager.getApplication().runWriteAction {
            val parent = VfsUtil.createDirectories(parentPath.toString())
            val fileName = path.fileName.toString()
            val file = parent.findChild(fileName) ?: parent.createChildData(this, fileName)
            VfsUtil.saveText(file, text)
        }
    }

    private fun fetchFromAikenNew(project: Project): String? {
        return try {
            runTemplateResolutionWithProgress(project) {
                loadTemplateFromCli(project)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to fetch validator template from `aiken new`", t)
            null
        }
    }

    private fun loadTemplateFromCli(project: Project): String? {
        val tempRoot = Files.createTempDirectory("aiken-template-")
        try {
            val cmd = listOf(
                AikenNodeToolchain.resolvePreferredAikenExecutable(project),
                "new",
                "codex/template"
            )
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
            val candidate = projectDir.resolve("validators").resolve("contract.ak")
            if (Files.exists(candidate)) {
                return readLocalText(candidate)
            }

            Files.walk(tempRoot).use { stream ->
                val discovered = stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == "contract.ak" }
                    .findFirst()
                    .orElse(null)
                if (discovered != null) {
                    return readLocalText(discovered)
                }
            }
            return null
        } finally {
            deleteTree(tempRoot)
        }
    }

    private fun readLocalText(path: Path): String =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { it.readText() }

    private fun <T> runTemplateResolutionWithProgress(project: Project, action: () -> T): T? {
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
            AikenBundle.message("aiken.new.file.resolving.validator.template"),
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
