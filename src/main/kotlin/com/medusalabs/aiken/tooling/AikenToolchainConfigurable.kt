package com.medusalabs.aiken.tooling

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import com.medusalabs.aiken.AikenBundle
import com.medusalabs.aiken.ui.AdaptiveWrapText
import javax.swing.DefaultComboBoxModel

private fun aikenToolchainWrappedCommentText(text: String): AdaptiveWrapText =
    AdaptiveWrapText(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
    }

private fun aikenToolchainWrappedStatusText(): AdaptiveWrapText =
    AdaptiveWrapText("").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = UIUtil.getLabelFont(UIUtil.FontSize.NORMAL)
    }

private fun com.intellij.ui.dsl.builder.Panel.aikenToolchainWrappedCommentRow(text: String): Row =
    row("") {
        cell(aikenToolchainWrappedCommentText(text))
            .resizableColumn()
            .align(AlignX.FILL)
    }

class AikenToolchainConfigurable(private val project: Project) :
    BoundSearchableConfigurable(
        AikenBundle.message("aiken.settings.toolchain.display.name"),
        "aiken.toolchain.settings",
        AikenBundle.message("aiken.settings.toolchain.display.name")
    ) {

    private val settings = project.service<AikenProjectToolchainSettings>()
    private val modeCombo = ComboBox(DefaultComboBoxModel(AikenToolchainMode.entries.toTypedArray()))
    private val globalCommandField = TextFieldWithBrowseButton()
    private val localVersionField = ComboBox(DefaultComboBoxModel(arrayOf(AikenNodeToolchain.DEFAULT_AIKEN_VERSION)))
    private val hintLabel = aikenToolchainWrappedStatusText()
    private lateinit var globalCommandRow: Row
    private lateinit var globalCommandCommentRow: Row
    private lateinit var localVersionRow: Row
    private lateinit var localVersionCommentRow: Row
    private var versionsLoading = false
    private var versionsLoaded = false

    override fun createPanel() = panel {
        row(AikenBundle.message("aiken.settings.toolchain.mode.label")) {
            cell(modeCombo)
                .align(AlignX.FILL)
        }
        aikenToolchainWrappedCommentRow(AikenBundle.message("aiken.settings.toolchain.mode.comment"))
        globalCommandRow = row(AikenBundle.message("aiken.settings.toolchain.global.command.label")) {
            cell(globalCommandField)
                .resizableColumn()
                .align(AlignX.FILL)
        }
        globalCommandCommentRow = aikenToolchainWrappedCommentRow(AikenBundle.message("aiken.settings.toolchain.global.command.comment"))
        localVersionRow = row(AikenBundle.message("aiken.settings.toolchain.local.version.label")) {
            cell(localVersionField)
                .resizableColumn()
                .align(AlignX.FILL)
        }
        localVersionCommentRow = aikenToolchainWrappedCommentRow(AikenBundle.message("aiken.settings.toolchain.local.version.comment"))
        row {
            cell(hintLabel)
                .resizableColumn()
                .align(AlignX.FILL)
        }
    }.also {
        val globalCommandDescriptor =
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle(AikenBundle.message("aiken.settings.toolchain.select.executable.title"))
                .withDescription(AikenBundle.message("aiken.settings.toolchain.select.executable.description"))
        globalCommandField.addBrowseFolderListener(
            TextBrowseFolderListener(globalCommandDescriptor, project)
        )
        globalCommandField.textField.columns = 1
        localVersionField.isEditable = true
        (localVersionField.editor.editorComponent as? javax.swing.JTextField)?.columns = 1
        reset()
        modeCombo.addActionListener { updateModeUi() }
    }

    override fun reset() {
        modeCombo.selectedItem = settings.getMode()
        globalCommandField.text = settings.getGlobalAikenCommand()
        localVersionField.selectedItem = settings.getLocalAikenVersion()
        updateModeUi()
    }

    override fun isModified(): Boolean {
        return selectedMode() != settings.getMode() ||
            globalCommandField.text.trim().ifEmpty { "aiken" } != settings.getGlobalAikenCommand() ||
            selectedLocalVersion() != settings.getLocalAikenVersion()
    }

    override fun apply() {
        settings.update(selectedMode(), globalCommandField.text, selectedLocalVersion())
    }

    private fun updateModeUi() {
        val localMode = selectedMode() == AikenToolchainMode.LOCAL
        globalCommandRow.visible(localMode.not())
        globalCommandCommentRow.visible(localMode.not())
        localVersionRow.visible(localMode)
        localVersionCommentRow.visible(localMode)
        localVersionField.isEnabled = localMode
        hintLabel.text = if (localMode) {
            loadAvailableVersions()
            AikenBundle.message(
                "aiken.settings.toolchain.local.mode.hint",
                AikenNodeToolchain.LOCAL_TOOLCHAIN_DIRECTORY
            )
        } else {
            AikenBundle.message("aiken.settings.toolchain.global.mode.hint")
        }
    }

    private fun loadAvailableVersions() {
        if (selectedMode() != AikenToolchainMode.LOCAL || versionsLoading || versionsLoaded) {
            return
        }
        versionsLoading = true
        hintLabel.text = AikenBundle.message("aiken.settings.toolchain.loading.versions")
        val baseDirectory = project.basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
        AikenNodeToolchain.fetchAvailableAikenVersions(project, baseDirectory)
            .whenComplete { catalog, _ ->
                ApplicationManager.getApplication().invokeLater({
                    versionsLoading = false
                    val comboModel = localVersionField.model as DefaultComboBoxModel<String>
                    comboModel.removeAllElements()
                    if (catalog != null) {
                        for (version in catalog.versions) {
                            comboModel.addElement(version)
                        }
                        versionsLoaded = true
                    } else {
                        comboModel.addElement(AikenNodeToolchain.DEFAULT_AIKEN_VERSION)
                    }
                    localVersionField.selectedItem = settings.getLocalAikenVersion()
                    updateModeUi()
                }, ModalityState.any())
            }
    }

    private fun selectedMode(): AikenToolchainMode =
        (modeCombo.selectedItem as? AikenToolchainMode) ?: AikenToolchainMode.LOCAL

    private fun selectedLocalVersion(): String {
        val value = (localVersionField.editor.item ?: localVersionField.selectedItem)?.toString().orEmpty()
        return AikenNodeToolchain.normalizeRequestedVersion(value)
    }
}
