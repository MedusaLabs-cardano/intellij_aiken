package com.medusalabs.aiken.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.util.ui.UIUtil
import com.medusalabs.aiken.icons.AikenIcons
import com.medusalabs.aiken.run.AikenCliCompatibility
import com.medusalabs.aiken.tooling.AikenProjectToolchainSettings
import com.medusalabs.aiken.tooling.AikenToolchainMode
import com.medusalabs.aiken.tooling.AikenNodeToolchain
import com.medusalabs.aiken.ui.AdaptiveWrapText
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private fun aikenWizardCommentText(text: String): AdaptiveWrapText =
    AdaptiveWrapText(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
    }

private fun aikenWizardStatusText(): AdaptiveWrapText =
    AdaptiveWrapText("").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = UIUtil.getLabelFont(UIUtil.FontSize.NORMAL)
    }

private fun aikenWizardWarningText(): AdaptiveWrapText =
    AdaptiveWrapText("").apply {
        foreground = JBColor.RED
        font = UIUtil.getLabelFont(UIUtil.FontSize.NORMAL)
    }

private fun Panel.aikenWizardWrappedCommentRow(text: String): Row =
    row("") {
        cell(aikenWizardCommentText(text))
            .resizableColumn()
            .align(AlignX.FILL)
    }

class AikenNewProjectWizard : LanguageGeneratorNewProjectWizard {
    override val name: String = "Aiken"

    override val icon: Icon = AikenIcons.AIKEN

    override fun isEnabled(context: WizardContext): Boolean = true

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = Step(parent)

    private class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
        private val baseData = parent as? NewProjectWizardBaseData
            ?: error("Aiken wizard requires base name/location step")

        private val vendorField = JTextField(defaultVendor())
        private val subtypeCombo = ComboBox(arrayOf(ProjectSubtype.VALIDATOR, ProjectSubtype.LIB))
        private val toolchainModeCombo = ComboBox(DefaultComboBoxModel(AikenToolchainMode.entries.toTypedArray()))
        private val globalAikenField = TextFieldWithBrowseButton()
        private val aikenVersionModel = DefaultComboBoxModel(arrayOf(AikenNodeToolchain.DEFAULT_AIKEN_VERSION))
        private val aikenVersionCombo = ComboBox(aikenVersionModel)
        private val stdlibVersionModel = DefaultComboBoxModel(arrayOf("v3"))
        private val stdlibVersionCombo = ComboBox(stdlibVersionModel)
        private val versionsStatusLabel = aikenWizardStatusText().apply {
            text = "Aiken versions are loaded from the npm registry when local mode is enabled."
        }
        private val stdlibStatusLabel = aikenWizardStatusText().apply {
            text = "Loading bundled stdlib versions..."
        }
        private val npmWarningLabel = aikenWizardWarningText()
        private lateinit var globalAikenCommentRow: Row
        private lateinit var aikenVersionCommentRow: Row
        private lateinit var globalAikenRow: Row
        private lateinit var aikenVersionRow: Row
        private lateinit var versionsStatusRow: Row
        private lateinit var stdlibVersionRow: Row
        private lateinit var stdlibStatusRow: Row
        private lateinit var npmWarningRow: Row
        private var normalizingVendor = false
        private var normalizingName = false
        private var lastValidVendor = vendorField.text.trim().ifBlank { "my_org" }
        private var lastValidProjectName = "my_project"
        private var npmAvailability = AikenNodeToolchain.describeNpmAvailability(toolchainProject())
        private var versionsFuture: CompletableFuture<AikenNodeToolchain.VersionCatalog>? = null
        private var versionsLoading = false
        private var versionsLoaded = false
        private var stdlibFuture: CompletableFuture<AikenStdlibCatalog.Catalog>? = null
        private var stdlibCatalog: AikenStdlibCatalog.Catalog? = null
        private var stdlibLoading = false
        private var detectedGlobalAikenVersion: String? = null
        private var detectingGlobalAikenVersion = false

        init {
            val normalizedInitialName = AikenProjectScaffolder.normalizeToken(baseData.name)
            if (normalizedInitialName.isNotBlank()) {
                lastValidProjectName = normalizedInitialName
            }
            if (normalizedInitialName != baseData.name) {
                normalizingName = true
                try {
                    baseData.name = normalizedInitialName.ifBlank { lastValidProjectName }
                } finally {
                    normalizingName = false
                }
            }

            baseData.nameProperty.afterChange { raw ->
                if (normalizingName) return@afterChange
                val normalized = AikenProjectScaffolder.normalizeToken(raw)
                val effective = normalized.ifBlank { lastValidProjectName }
                if (effective.isNotBlank()) {
                    lastValidProjectName = effective
                }
                if (effective != raw) {
                    normalizingName = true
                    try {
                        baseData.name = effective
                    } finally {
                        normalizingName = false
                    }
                }
            }
            vendorField.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = normalizeVendor()
                    override fun removeUpdate(e: DocumentEvent?) = normalizeVendor()
                    override fun changedUpdate(e: DocumentEvent?) = normalizeVendor()
                }
            )
            aikenVersionCombo.isEditable = true
            stdlibVersionCombo.isEditable = true
            toolchainModeCombo.selectedItem = defaultToolchainMode()
            globalAikenField.text = "aiken"
            vendorField.columns = 1
            globalAikenField.textField.columns = 1
            (aikenVersionCombo.editor.editorComponent as? JTextField)?.columns = 1
            (stdlibVersionCombo.editor.editorComponent as? JTextField)?.columns = 1
            val globalAikenDescriptor =
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withTitle("Select Aiken Executable")
                    .withDescription("Choose the global Aiken executable or script to run for this project.")
            globalAikenField.addBrowseFolderListener(
                TextBrowseFolderListener(
                    globalAikenDescriptor,
                    null
                )
            )
            refreshNpmStatus()
            updateToolchainUi()
            toolchainModeCombo.addActionListener { updateToolchainUi() }
            aikenVersionCombo.addActionListener { updateCompatibleStdlibVersions() }
            (aikenVersionCombo.editor.editorComponent as? JTextField)?.document?.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = updateCompatibleStdlibVersions()
                    override fun removeUpdate(e: DocumentEvent?) = updateCompatibleStdlibVersions()
                    override fun changedUpdate(e: DocumentEvent?) = updateCompatibleStdlibVersions()
                }
            )
            globalAikenField.textField.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = onGlobalAikenCommandChanged()
                    override fun removeUpdate(e: DocumentEvent?) = onGlobalAikenCommandChanged()
                    override fun changedUpdate(e: DocumentEvent?) = onGlobalAikenCommandChanged()
                }
            )
        }

        override fun setupUI(builder: Panel) {
            builder.row("Vendor:") {
                cell(vendorField)
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .validationOnInput { field ->
                        validateAllOrNull(field.getText())
                    }
                    .validationOnApply { field ->
                        validateAllOrNull(field.getText())
                    }
            }
            builder.aikenWizardWrappedCommentRow("Used in project identifier `vendor/name`. Allowed: lowercase letters, digits, `_`, `-`.")
            builder.row("Project type:") {
                cell(subtypeCombo)
                    .align(AlignX.FILL)
            }
            builder.aikenWizardWrappedCommentRow("`validator` creates a standard contract project, `lib` creates a library-only project. Project name comes from the base Name field and must match [a-z0-9_-]+.")
            builder.row("Toolchain mode:") {
                cell(toolchainModeCombo)
                    .align(AlignX.FILL)
            }
            builder.aikenWizardWrappedCommentRow("Use a global Aiken command for offline-friendly projects, or install a project-local version from npm.")
            globalAikenRow = builder.row("Global Aiken:") {
                cell(globalAikenField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            globalAikenCommentRow = builder.aikenWizardWrappedCommentRow("Command name or full path. Used when `Use global Aiken` is selected.")
            aikenVersionRow = builder.row("Aiken version:") {
                cell(aikenVersionCombo)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            aikenVersionCommentRow = builder.aikenWizardWrappedCommentRow("Loaded from npm package `@aiken-lang/aiken` and installed locally into the project.")
            versionsStatusRow = builder.row("") {
                cell(versionsStatusLabel)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            stdlibVersionRow = builder.row("stdlib version:") {
                cell(stdlibVersionCombo)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            stdlibStatusRow = builder.row("") {
                cell(stdlibStatusLabel)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            npmWarningRow = builder.row("") {
                cell(npmWarningLabel)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            updateToolchainUi()
        }

        override fun setupProject(project: Project) {
            val vendor = vendorField.text.trim()
            val validationError = validateAllOrNull(vendor)
            if (validationError != null) {
                throw IOException(validationError.message)
            }
            val projectName = baseData.name.trim()
            val projectPath = baseData.path.trim()
            val subtype = (subtypeCombo.selectedItem as? ProjectSubtype) ?: ProjectSubtype.VALIDATOR
            val mode = selectedToolchainMode()
            val aikenVersion = selectedCompilerVersionForTemplate()
            val stdlibVersion = selectedStdlibVersion()
            val globalAikenCommand = selectedGlobalAikenCommand()
            val targetDirectoryPath = project.basePath.orEmpty().trim().ifBlank {
                AikenProjectScaffolder.resolveTargetDirectoryPath(projectPath, projectName)
            }

            AikenProjectScaffolder.createProject(
                targetDirectoryPath = targetDirectoryPath,
                vendor = vendor,
                projectName = projectName,
                libraryOnly = subtype == ProjectSubtype.LIB,
                toolchainMode = mode,
                aikenVersion = aikenVersion,
                stdlibVersion = stdlibVersion,
                plutusVersion = stdlibCatalog?.plutusVersionFor(stdlibVersion)
            )
            project.getService(AikenProjectToolchainSettings::class.java)
                .update(mode, globalAikenCommand, aikenVersion)
            ensureAikenProjectModule(project, targetDirectoryPath)
        }

        private fun normalizeVendor() {
            if (normalizingVendor) return
            val raw = vendorField.text
            val normalized = AikenProjectScaffolder.normalizeToken(raw)
            val effective = normalized.ifBlank { lastValidVendor }
            if (effective.isNotBlank()) {
                lastValidVendor = effective
            }
            if (effective == raw) return
            normalizingVendor = true
            try {
                vendorField.text = effective
                vendorField.caretPosition = vendorField.text.length
            } finally {
                normalizingVendor = false
            }
        }

        private fun validateAllOrNull(vendorValue: String): ValidationInfo? =
            try {
                AikenProjectScaffolder.requireValidToken("Vendor", vendorValue.trim())
                val projectName = baseData.name.trim()
                val projectPath = baseData.path.trim()
                AikenProjectScaffolder.requireValidToken("Project name", projectName)
                val targetDirectoryPath = AikenProjectScaffolder.resolveTargetDirectoryPath(projectPath, projectName)
                AikenProjectScaffolder.validateTargetDirectoryPath(targetDirectoryPath, projectName)?.let {
                    throw IllegalStateException(it)
                }
                if (selectedToolchainMode() == AikenToolchainMode.LOCAL && !npmAvailability.available) {
                    throw IllegalStateException(npmAvailability.message)
                }
                if (selectedToolchainMode() == AikenToolchainMode.LOCAL && selectedAikenVersion().isBlank()) {
                    throw IllegalStateException("Aiken version must not be empty.")
                }
                if (selectedToolchainMode() == AikenToolchainMode.LOCAL && !AikenNodeToolchain.isSupportedAikenVersion(selectedAikenVersion())) {
                    throw IllegalStateException("Aiken versions below ${AikenNodeToolchain.MIN_SUPPORTED_AIKEN_VERSION} are not supported.")
                }
                if (selectedStdlibVersion().isBlank()) {
                    throw IllegalStateException("stdlib version must not be empty.")
                }
                val resolvedAikenVersion = resolveSelectedAikenVersionForCompatibility()
                val catalog = stdlibCatalog
                if (catalog != null && resolvedAikenVersion != null) {
                    val compatible = catalog.compatibleTagsFor(resolvedAikenVersion)
                    if (compatible.isNotEmpty() && selectedStdlibVersion() !in compatible) {
                        throw IllegalStateException("Selected stdlib version is not compatible with Aiken $resolvedAikenVersion.")
                    }
                }
                if (selectedToolchainMode() == AikenToolchainMode.GLOBAL && selectedGlobalAikenCommand().isBlank()) {
                    throw IllegalStateException("Global Aiken command must not be empty.")
                }
                null
            } catch (e: IllegalStateException) {
                ValidationInfo(e.message.orEmpty())
            }

        private fun defaultVendor(): String {
            val raw = System.getProperty("user.name").orEmpty().lowercase(Locale.US).trim()
            val normalized = AikenProjectScaffolder.normalizeToken(raw)
            return normalized.ifBlank { "my_org" }
        }

        private fun refreshNpmStatus() {
            npmAvailability = AikenNodeToolchain.describeNpmAvailability(toolchainProject())
            npmWarningLabel.text =
                "Local Aiken installation is unavailable because npm was not found. Install Node.js/npm or use `Use global Aiken`."
        }

        private fun loadAvailableVersions() {
            if (selectedToolchainMode() != AikenToolchainMode.LOCAL) {
                return
            }
            if (versionsLoading || versionsLoaded) {
                return
            }
            versionsLoading = true
            versionsStatusLabel.text = "Loading Aiken versions..."
            val baseDirectory = LocalFileSystem.getInstance().refreshAndFindFileByPath(baseData.path.trim())
            versionsFuture = AikenNodeToolchain.fetchAvailableAikenVersions(toolchainProject(), baseDirectory)
            versionsFuture?.whenComplete { catalog, error ->
                ApplicationManager.getApplication().invokeLater({
                    versionsLoading = false
                    if (error != null || catalog == null) {
                        aikenVersionModel.removeAllElements()
                        aikenVersionModel.addElement(AikenNodeToolchain.DEFAULT_AIKEN_VERSION)
                        aikenVersionCombo.selectedItem = selectedAikenVersion()
                        versionsStatusLabel.text = "Couldn't load Aiken versions. You can type one manually; details are in idea.log."
                        return@invokeLater
                    }

                    aikenVersionModel.removeAllElements()
                    for (version in catalog.versions) {
                        aikenVersionModel.addElement(version)
                    }
                    aikenVersionCombo.selectedItem = catalog.latest
                    versionsLoaded = true
                    versionsStatusLabel.text = "Latest Aiken version: ${catalog.latest}"
                    updateCompatibleStdlibVersions()
                    updateToolchainUi()
                }, ModalityState.any())
            }
        }

        private fun loadStdlibVersions() {
            if (stdlibLoading || stdlibCatalog != null) return
            stdlibLoading = true
            stdlibStatusLabel.text = "Loading bundled stdlib versions..."
            stdlibFuture = AikenStdlibCatalog.fetchCatalog()
            stdlibFuture?.whenComplete { catalog, error ->
                ApplicationManager.getApplication().invokeLater({
                    stdlibLoading = false
                    if (error != null || catalog == null) {
                        stdlibVersionModel.removeAllElements()
                        stdlibVersionModel.addElement("v3")
                        stdlibVersionCombo.selectedItem = "v3"
                        stdlibStatusLabel.text = "Couldn't load the bundled stdlib catalog. You can still type a version manually."
                        return@invokeLater
                    }
                    stdlibCatalog = catalog
                    updateCompatibleStdlibVersions()
                }, ModalityState.any())
            }
        }

        private fun updateToolchainUi() {
            val localMode = selectedToolchainMode() == AikenToolchainMode.LOCAL
            if (::globalAikenRow.isInitialized) {
                globalAikenRow.visible(localMode.not())
                globalAikenCommentRow.visible(localMode.not())
                aikenVersionRow.visible(localMode)
                aikenVersionCommentRow.visible(localMode)
                versionsStatusRow.visible(localMode)
                stdlibVersionRow.visible(true)
                stdlibStatusRow.visible(true)
                npmWarningRow.visible(localMode && !npmAvailability.available)
            }
            aikenVersionCombo.isEnabled = localMode
            versionsStatusLabel.isEnabled = localMode
            if (localMode) {
                loadAvailableVersions()
            } else {
                detectGlobalAikenVersion()
            }
            loadStdlibVersions()
            updateCompatibleStdlibVersions()
        }

        private fun selectedAikenVersion(): String {
            val raw = (aikenVersionCombo.editor.item ?: aikenVersionCombo.selectedItem)?.toString().orEmpty()
            return AikenNodeToolchain.normalizeRequestedVersion(raw)
        }

        private fun selectedCompilerVersionForTemplate(): String =
            when (selectedToolchainMode()) {
                AikenToolchainMode.LOCAL -> selectedAikenVersion()
                AikenToolchainMode.GLOBAL -> {
                    val detected = AikenCliCompatibility.extractVersion(detectedGlobalAikenVersion)
                    if (detected != null) {
                        buildString {
                            append(detected.major)
                            append('.')
                            append(detected.minor)
                            append('.')
                            append(detected.patch)
                            if (detected.preRelease.isNotEmpty()) {
                                append('-')
                                append(
                                    detected.preRelease.joinToString(".") { identifier ->
                                        identifier.numeric?.toString() ?: identifier.text.orEmpty()
                                    }
                                )
                            }
                        }
                    } else {
                        selectedAikenVersion()
                    }
                }
            }

        private fun selectedStdlibVersion(): String {
            val raw = (stdlibVersionCombo.editor.item ?: stdlibVersionCombo.selectedItem)?.toString().orEmpty()
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return if (stdlibVersionModel.size > 0) {
                stdlibVersionModel.getElementAt(0)
            } else {
                ""
            }
        }

        private fun selectedToolchainMode(): AikenToolchainMode =
            (toolchainModeCombo.selectedItem as? AikenToolchainMode) ?: defaultToolchainMode()

        private fun selectedGlobalAikenCommand(): String =
            globalAikenField.text.trim().ifEmpty { "aiken" }

        private fun defaultToolchainMode(): AikenToolchainMode =
            if (npmAvailability.available) AikenToolchainMode.LOCAL else AikenToolchainMode.GLOBAL

        private fun toolchainProject(): Project = ProjectManager.getInstance().defaultProject

        private fun onGlobalAikenCommandChanged() {
            if (selectedToolchainMode() == AikenToolchainMode.GLOBAL) {
                detectGlobalAikenVersion()
            }
        }

        private fun detectGlobalAikenVersion() {
            if (detectingGlobalAikenVersion) return
            detectingGlobalAikenVersion = true
            stdlibStatusLabel.text = "Detecting global Aiken version for stdlib compatibility..."
            val command = selectedGlobalAikenCommand()
            ApplicationManager.getApplication().executeOnPooledThread {
                val detected = AikenStdlibCatalog.detectGlobalAikenVersion(command)
                ApplicationManager.getApplication().invokeLater({
                    detectedGlobalAikenVersion = detected
                    detectingGlobalAikenVersion = false
                    updateCompatibleStdlibVersions()
                }, ModalityState.any())
            }
        }

        private fun resolveSelectedAikenVersionForCompatibility(): String? =
            when (selectedToolchainMode()) {
                AikenToolchainMode.LOCAL -> {
                    val selected = selectedAikenVersion()
                    if (selected == AikenNodeToolchain.DEFAULT_AIKEN_VERSION && !versionsLoaded) {
                        null
                    } else {
                        selected
                    }
                }
                AikenToolchainMode.GLOBAL -> detectedGlobalAikenVersion
            }

        private fun updateCompatibleStdlibVersions() {
            val catalog = stdlibCatalog ?: return
            val currentSelection = selectedStdlibVersion()
            val compatibilityVersion = resolveSelectedAikenVersionForCompatibility()
            val compatibleTags =
                if (compatibilityVersion != null) {
                    catalog.compatibleTagsFor(compatibilityVersion)
                } else {
                    catalog.tags
                }

            val tagsToShow =
                if (compatibilityVersion != null && compatibleTags.isNotEmpty()) {
                    compatibleTags
                } else if (compatibilityVersion != null) {
                    emptyList()
                } else {
                    catalog.tags
                }
            stdlibVersionModel.removeAllElements()
            tagsToShow.forEach(stdlibVersionModel::addElement)

            val nextSelection =
                when {
                    currentSelection in tagsToShow -> currentSelection
                    compatibilityVersion != null -> catalog.recommendedTagFor(compatibilityVersion)
                    else -> tagsToShow.firstOrNull()
                } ?: ""
            stdlibVersionCombo.selectedItem = nextSelection

            stdlibStatusLabel.text =
                when {
                    compatibilityVersion != null && compatibleTags.isEmpty() ->
                        "Aiken $compatibilityVersion is unsupported for scaffolded projects. Only Plutus V3 stdlib versions are supported."
                    compatibilityVersion == null && selectedToolchainMode() == AikenToolchainMode.GLOBAL ->
                        "Global Aiken version couldn't be detected. Showing all bundled stdlib tags."
                    compatibilityVersion == null && selectedToolchainMode() == AikenToolchainMode.LOCAL && !versionsLoaded ->
                        "Waiting for the selected Aiken version to resolve before filtering stdlib tags."
                    compatibilityVersion != null ->
                        "Showing ${tagsToShow.size} stdlib tag(s) compatible with Aiken $compatibilityVersion."
                    else ->
                        "Showing bundled stdlib tags."
                }
        }

    }
    enum class ProjectSubtype(private val label: String) {
        VALIDATOR("validator"),
        LIB("lib");

        override fun toString(): String = label
    }
}
