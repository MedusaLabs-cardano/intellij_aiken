package com.medusalabs.aiken.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.Panel
import com.medusalabs.aiken.icons.AikenIcons
import java.io.IOException
import java.util.Locale
import javax.swing.Icon
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
        private var normalizingVendor = false
        private var normalizingName = false
        private var lastValidVendor = vendorField.text.trim().ifBlank { "my_org" }
        private var lastValidProjectName = "my_project"

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
        }

        override fun setupUI(builder: Panel) {
            builder.row("Vendor:") {
                cell(vendorField)
                    .resizableColumn()
                    .validationOnInput { field ->
                        validateAllOrNull(field.getText())
                    }
                    .validationOnApply { field ->
                        validateAllOrNull(field.getText())
                    }
                    .comment("Used in project identifier `vendor/name`. Allowed: lowercase letters, digits, `_`, `-`.")
            }
            builder.row("Project type:") {
                cell(subtypeCombo)
                    .comment("`validator` creates a standard contract project, `lib` creates a library-only project. Project name comes from the base Name field and must match [a-z0-9_-]+.")
            }
        }

        override fun setupProject(project: Project) {
            val vendor = vendorField.text.trim()
            val validationError = validateAllOrNull(vendor)
            if (validationError != null) {
                throw IOException(validationError.message.orEmpty())
            }
            val projectName = baseData.name.trim()
            val projectPath = baseData.path.trim()
            val subtype = (subtypeCombo.selectedItem as? ProjectSubtype) ?: ProjectSubtype.VALIDATOR
            val targetDirectoryPath = project.basePath?.trim().orEmpty().ifBlank {
                AikenProjectScaffolder.resolveTargetDirectoryPath(projectPath, projectName)
            }

            AikenProjectScaffolder.createProject(
                targetDirectoryPath = targetDirectoryPath,
                vendor = vendor,
                projectName = projectName,
                libraryOnly = subtype == ProjectSubtype.LIB
            )
            AikenGitVcsMappingStartupActivity.ensureProjectModule(project, targetDirectoryPath)
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
                null
            } catch (e: IllegalStateException) {
                ValidationInfo(e.message.orEmpty())
            }

        private fun defaultVendor(): String {
            val raw = System.getProperty("user.name").orEmpty().lowercase(Locale.US).trim()
            val normalized = AikenProjectScaffolder.normalizeToken(raw)
            return normalized.ifBlank { "my_org" }
        }
    }

    enum class ProjectSubtype(private val label: String) {
        VALIDATOR("validator"),
        LIB("lib");

        override fun toString(): String = label
    }
}
