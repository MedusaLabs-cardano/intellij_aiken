package com.medusalabs.aiken.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.text.JTextComponent

class AikenRunConfigurationEditor : SettingsEditor<AikenRunConfiguration>() {
    private val descriptionFontScale = 0.9f
    private val buildCard = "build"
    private val artifactsCard = "artifacts"
    private val cleanCard = "clean"
    private val applyCard = "apply"
    private val checkCard = "check"

    private val commandSpecificPanel = JPanel(CardLayout())
    private var rootContent: JComponent? = null

    private val projectDirectoryField = TextFieldWithBrowseButton(JBTextField())
    private val aikenBinaryField = TextFieldWithBrowseButton(JBTextField())
    private val extraArgsField = JBTextField()

    private val buildDenyWarningsCheck = JBCheckBox("Treat warnings as errors")
    private val buildSilentWarningsCheck = JBCheckBox("Hide warnings output")
    private val buildWatchCheck = JBCheckBox("Re-run on file changes")

    private val checkWarningsHandlingCombo = JComboBox(CheckWarningsHandling.entries.toTypedArray())
    private val checkWatchCheck = JBCheckBox("Re-run on file changes")

    private val buildUplcCheck = JBCheckBox("Dump textual UPLC")
    private val buildEnvField = JBTextField()
    private val buildOutField = JBTextField()
    private val buildTraceFilterCombo = JComboBox(AikenTraceFilter.entries.toTypedArray())
    private val buildTraceLevelCombo = JComboBox(AikenTraceLevel.entries.toTypedArray())
    private val buildOutputModeCombo = JComboBox(AikenBuildOutputMode.entries.toTypedArray())

    private val addressInField = JBTextField()
    private val addressModuleField = JBTextField()
    private val addressValidatorField = JBTextField()
    private val addressDelegatedToField = JBTextField()
    private val addressIncludeScriptCborCheck = JBCheckBox("Generate script file")
    private val addressIncludeTestnetAddressCheck = JBCheckBox("Generate testnet address")
    private val addressIncludeMainnetAddressCheck = JBCheckBox("Generate mainnet address")
    private val addressGeneratePolicyIdCheck = JBCheckBox("Generate policy ID")
    private val addressScriptTemplateField = JBTextField()
    private val addressMainnetTemplateField = JBTextField()
    private val addressTestnetTemplateField = JBTextField()
    private val addressPolicyTemplateField = JBTextField()
    private val addressArtifactsBasePathField = JBTextField()
    private val cleanTargetPathField = JBTextField()

    private val applyInField = JBTextField()
    private val applyOutField = JBTextField()
    private val applyModuleField = JBTextField()
    private val applyValidatorField = JBTextField()
    private val applyDefaultCborParametersField = JBTextField()
    private val applyAutoUntilNoParametersCheck = JBCheckBox("Auto-repeat until no parameters remain")
    private val applyOutputModeCombo = JComboBox(AikenApplyOutputMode.entries.toTypedArray())

    private val checkSkipTestsCheck = JBCheckBox("Skip tests")
    private val checkOutputModeCombo = JComboBox(AikenCheckOutputMode.entries.toTypedArray())
    private val checkDebugCheck = JBCheckBox("Pretty-print failing test UPLC")
    private val checkSeedField = JBTextField()
    private val checkMaxSuccessField = JBTextField()
    private val checkPropertyCoverageCombo = JComboBox(AikenPropertyCoverage.entries.toTypedArray())
    private val checkMatchTestsField = JBTextField()
    private val checkExactMatchCheck = JBCheckBox("Exact test name match")
    private val checkEnvField = JBTextField()
    private val checkTraceFilterCombo = JComboBox(AikenTraceFilter.entries.toTypedArray())
    private val checkTraceLevelCombo = JComboBox(AikenTraceLevel.entries.toTypedArray())

    init {
        val projectDirectoryDescriptor =
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select project directory")
                .withDescription("Directory passed to `aiken ... [DIRECTORY]` and used as working directory.")
        projectDirectoryField.addBrowseFolderListener(TextBrowseFolderListener(projectDirectoryDescriptor, null))

        val aikenBinaryDescriptor =
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select aiken binary")
                .withDescription("Path to `aiken` executable.")
        aikenBinaryField.addBrowseFolderListener(TextBrowseFolderListener(aikenBinaryDescriptor, null))

        (projectDirectoryField.textField as? JBTextField)?.emptyText?.text = "e.g. /home/user/my-aiken-project"
        (aikenBinaryField.textField as? JBTextField)?.emptyText?.text = "aiken"
        extraArgsField.emptyText.text = "e.g. --help"

        buildEnvField.emptyText.text = "e.g. preprod"
        buildOutField.emptyText.text = "plutus.json"

        addressInField.emptyText.text = "plutus.json"
        addressModuleField.emptyText.text = "e.g. my_module, other_module"
        addressValidatorField.emptyText.text = "e.g. my_validator, other_validator"
        addressDelegatedToField.emptyText.text = "e.g. stake_test1..."
        addressScriptTemplateField.emptyText.text = "%module%.%validator%.script"
        addressMainnetTemplateField.emptyText.text = "%module%.%validator%.addr"
        addressTestnetTemplateField.emptyText.text = "%module%.%validator%.addr_test"
        addressPolicyTemplateField.emptyText.text = "%module%.%validator%.policy"

        applyInField.emptyText.text = "plutus.json"
        applyOutField.emptyText.text = "plutus.json"
        applyModuleField.emptyText.text = "e.g. my_module"
        applyValidatorField.emptyText.text = "e.g. my_validator"
        applyDefaultCborParametersField.emptyText.text = "e.g. 182A, d8799f0102ff"

        addressArtifactsBasePathField.emptyText.text = "artifacts"
        cleanTargetPathField.emptyText.text = "artifacts"

        checkSeedField.emptyText.text = "e.g. 42"
        checkMaxSuccessField.emptyText.text = "100"
        checkMatchTestsField.emptyText.text = "e.g. list, aiken/list, aiken/list.{map}"
        checkEnvField.emptyText.text = "e.g. preprod"
    }

    override fun resetEditorFrom(configuration: AikenRunConfiguration) {
        showCommandCard(configuration.command)
        updatePreferredSize(configuration.command)
        projectDirectoryField.text =
            configuration.projectDirectory.ifBlank { configuration.project.basePath ?: "" }
        aikenBinaryField.text = configuration.aikenBinaryPath.ifBlank { "aiken" }
        extraArgsField.text = configuration.extraArgs

        buildDenyWarningsCheck.isSelected = configuration.denyWarnings
        buildSilentWarningsCheck.isSelected = configuration.silentWarnings
        buildWatchCheck.isSelected = configuration.watch

        checkWarningsHandlingCombo.selectedItem =
            CheckWarningsHandling.fromFlags(
                denyWarnings = configuration.denyWarnings,
                silentWarnings = configuration.silentWarnings
            )
        checkWatchCheck.isSelected = configuration.watch

        buildUplcCheck.isSelected = configuration.buildUplc
        buildEnvField.text = configuration.buildEnv
        buildOutField.text = configuration.buildOut.ifBlank { "plutus.json" }
        buildTraceFilterCombo.selectedItem = configuration.buildTraceFilter
        buildTraceLevelCombo.selectedItem = configuration.buildTraceLevel
        buildOutputModeCombo.selectedItem = configuration.buildOutputMode

        addressInField.text = configuration.addressInput.ifBlank { "plutus.json" }
        addressModuleField.text = configuration.addressModule
        addressValidatorField.text = configuration.addressValidator
        addressDelegatedToField.text = configuration.addressDelegatedTo
        addressIncludeScriptCborCheck.isSelected = configuration.addressIncludeScriptCbor
        addressIncludeTestnetAddressCheck.isSelected = configuration.addressIncludeTestnetAddress
        addressIncludeMainnetAddressCheck.isSelected = configuration.addressIncludeMainnetAddress
        addressGeneratePolicyIdCheck.isSelected = configuration.addressGeneratePolicyId
        addressScriptTemplateField.text = configuration.addressScriptTemplate.ifBlank { "%module%.%validator%.script" }
        addressMainnetTemplateField.text = configuration.addressMainnetTemplate.ifBlank { "%module%.%validator%.addr" }
        addressTestnetTemplateField.text = configuration.addressTestnetTemplate.ifBlank { "%module%.%validator%.addr_test" }
        addressPolicyTemplateField.text = configuration.addressPolicyTemplate.ifBlank { "%module%.%validator%.policy" }
        addressArtifactsBasePathField.text = configuration.addressArtifactsBasePath.ifBlank { "artifacts" }
        cleanTargetPathField.text = configuration.cleanTargetPath.ifBlank { "artifacts" }

        applyInField.text = configuration.applyInput.ifBlank { "plutus.json" }
        applyOutField.text = configuration.applyOut.ifBlank { "plutus.json" }
        applyModuleField.text = configuration.applyModule
        applyValidatorField.text = configuration.applyValidator
        applyDefaultCborParametersField.text = configuration.applyDefaultCborParameters
        applyAutoUntilNoParametersCheck.isSelected = configuration.applyAutoUntilNoParameters
        applyOutputModeCombo.selectedItem = configuration.applyOutputMode

        checkSkipTestsCheck.isSelected = configuration.checkSkipTests
        checkOutputModeCombo.selectedItem = configuration.checkOutputMode
        checkDebugCheck.isSelected = configuration.checkDebug
        checkSeedField.text = configuration.checkSeed
        checkMaxSuccessField.text = configuration.checkMaxSuccess.ifBlank { "100" }
        checkPropertyCoverageCombo.selectedItem = configuration.checkPropertyCoverage
        checkMatchTestsField.text = configuration.checkMatchTests
        checkExactMatchCheck.isSelected = configuration.checkExactMatch
        checkEnvField.text = configuration.checkEnv
        checkTraceFilterCombo.selectedItem = configuration.checkTraceFilter
        checkTraceLevelCombo.selectedItem = configuration.checkTraceLevel
    }

    override fun applyEditorTo(configuration: AikenRunConfiguration) {
        configuration.projectDirectory = projectDirectoryField.text.trim()
        configuration.aikenBinaryPath = aikenBinaryField.text.trim().ifEmpty { "aiken" }
        configuration.extraArgs = extraArgsField.text.trim()

        when (configuration.command) {
            AikenRunCommand.BUILD -> {
                configuration.denyWarnings = buildDenyWarningsCheck.isSelected
                configuration.silentWarnings = buildSilentWarningsCheck.isSelected
                configuration.watch = buildWatchCheck.isSelected
            }

            AikenRunCommand.CHECK -> {
                val mode = (checkWarningsHandlingCombo.selectedItem as? CheckWarningsHandling)
                    ?: CheckWarningsHandling.NORMAL
                configuration.denyWarnings = mode == CheckWarningsHandling.TREAT_AS_ERRORS
                configuration.silentWarnings = mode == CheckWarningsHandling.IGNORE
                configuration.watch = checkWatchCheck.isSelected
            }

            AikenRunCommand.ADDRESS -> {
                // Shared toggles are not used by Artifacts runner.
            }

            AikenRunCommand.CLEAN -> {
                // Shared toggles are not used by Clean runner.
            }

            AikenRunCommand.APPLY -> {
                // No shared toggles used by `aiken blueprint apply`.
            }

            AikenRunCommand.CONVERT -> {
                // Legacy Convert configs are handled by Artifacts behavior.
            }

        }

        configuration.buildUplc = buildUplcCheck.isSelected
        configuration.buildEnv = buildEnvField.text.trim()
        configuration.buildOut = buildOutField.text.trim()
        configuration.buildTraceFilter =
            (buildTraceFilterCombo.selectedItem as? AikenTraceFilter) ?: AikenTraceFilter.ALL
        configuration.buildTraceLevel =
            (buildTraceLevelCombo.selectedItem as? AikenTraceLevel) ?: AikenTraceLevel.SILENT
        configuration.buildOutputMode =
            (buildOutputModeCombo.selectedItem as? AikenBuildOutputMode) ?: AikenBuildOutputMode.IDE_INTEGRATED

        configuration.addressInput = addressInField.text.trim()
        configuration.addressModule = addressModuleField.text.trim()
        configuration.addressValidator = addressValidatorField.text.trim()
        configuration.addressDelegatedTo = addressDelegatedToField.text.trim()
        configuration.addressIncludeScriptCbor = addressIncludeScriptCborCheck.isSelected
        configuration.addressIncludeTestnetAddress = addressIncludeTestnetAddressCheck.isSelected
        configuration.addressIncludeMainnetAddress = addressIncludeMainnetAddressCheck.isSelected
        configuration.addressGeneratePolicyId = addressGeneratePolicyIdCheck.isSelected
        configuration.addressScriptTemplate = addressScriptTemplateField.text.trim()
        configuration.addressMainnetTemplate = addressMainnetTemplateField.text.trim()
        configuration.addressTestnetTemplate = addressTestnetTemplateField.text.trim()
        configuration.addressPolicyTemplate = addressPolicyTemplateField.text.trim()
        configuration.addressArtifactsBasePath =
            addressArtifactsBasePathField.text.trim().ifEmpty { "artifacts" }
        configuration.cleanTargetPath = cleanTargetPathField.text.trim().ifEmpty { "artifacts" }

        configuration.applyInput = applyInField.text.trim()
        configuration.applyOut = applyOutField.text.trim()
        configuration.applyModule = applyModuleField.text.trim()
        configuration.applyValidator = applyValidatorField.text.trim()
        configuration.applyDefaultCborParameters = applyDefaultCborParametersField.text.trim()
        configuration.applyAutoUntilNoParameters = applyAutoUntilNoParametersCheck.isSelected
        configuration.applyOutputMode =
            (applyOutputModeCombo.selectedItem as? AikenApplyOutputMode) ?: AikenApplyOutputMode.IDE_INTEGRATED

        configuration.checkSkipTests = checkSkipTestsCheck.isSelected
        configuration.checkOutputMode =
            (checkOutputModeCombo.selectedItem as? AikenCheckOutputMode) ?: AikenCheckOutputMode.IDE_INTEGRATED
        configuration.checkDebug = checkDebugCheck.isSelected
        configuration.checkSeed = checkSeedField.text.trim()
        configuration.checkMaxSuccess = checkMaxSuccessField.text.trim()
        configuration.checkPropertyCoverage =
            (checkPropertyCoverageCombo.selectedItem as? AikenPropertyCoverage)
                ?: AikenPropertyCoverage.RELATIVE_TO_LABELS
        configuration.checkMatchTests = checkMatchTestsField.text.trim()
        configuration.checkExactMatch = checkExactMatchCheck.isSelected
        configuration.checkEnv = checkEnvField.text.trim()
        configuration.checkTraceFilter =
            (checkTraceFilterCombo.selectedItem as? AikenTraceFilter) ?: AikenTraceFilter.ALL
        configuration.checkTraceLevel =
            (checkTraceLevelCombo.selectedItem as? AikenTraceLevel) ?: AikenTraceLevel.VERBOSE
    }

    override fun createEditor(): JComponent {
        commandSpecificPanel.removeAll()
        commandSpecificPanel.add(createBuildPanel(), buildCard)
        commandSpecificPanel.add(createArtifactsPanel(), artifactsCard)
        commandSpecificPanel.add(createCleanPanel(), cleanCard)
        commandSpecificPanel.add(createApplyPanel(), applyCard)
        commandSpecificPanel.add(createCheckPanel(), checkCard)

        val content = panel {
            group("General") {
                row("Project directory:") {
                    cell(projectDirectoryField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Working directory for Aiken command. Relative paths resolve from here.")
                }

                row("Aiken binary path:") {
                    cell(aikenBinaryField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Binary name or full path. Default: aiken.")
                }

                row("Extra arguments:") {
                    cell(extraArgsField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional raw arguments appended at the end.")
                }
            }

            row {
                cell(commandSpecificPanel)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }

        applyUnifiedDescriptionFont(content)
        rootContent = content
        return ScrollPaneFactory.createScrollPane(content, true)
    }

    private fun createBuildPanel(): JComponent {
        return panel {
            group("Options") {
                row {
                    cell(buildDenyWarningsCheck)
                }

                row {
                    cell(buildSilentWarningsCheck)
                }

                row {
                    cell(buildWatchCheck)
                }

                row("Output mode:") {
                    cell(buildOutputModeCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("TTY for terminal output, IDE integrated for structured warnings/errors.")
                }

                row {
                    cell(buildUplcCheck)
                }

                row("Build environment:") {
                    cell(buildEnvField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Environment name from aiken.toml, for example preprod.")
                }

                row("Blueprint output file:") {
                    cell(buildOutField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Output blueprint file. Relative paths are inside project directory.")
                }

                row("Trace filter:") {
                    cell(buildTraceFilterCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Select which trace categories to include in generated code.")
                }

                row("Trace level:") {
                    cell(buildTraceLevelCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Controls detail level of emitted traces.")
                }
            }
        }
    }

    private fun createArtifactsPanel(): JComponent {
        val toggleColumnWidth = artifactsToggleColumnWidth()
        return panel {
            group("Options") {
                row("Input blueprint file:") {
                    cell(addressInField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Blueprint JSON file used to produce script, addresses, and policy IDs.")
                }

                row("Module:") {
                    cell(addressModuleField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional module filter(s). Separate multiple values with comma or space.")
                }

                row("Validator:") {
                    cell(addressValidatorField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional top-level validator filter(s), without handler suffixes like spend/mint/else.")
                }

                row("Delegated stake address:") {
                    cell(addressDelegatedToField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional stake key/address attached to both generated addresses (testnet and mainnet).")
                }
            }

            group("Artifacts") {
                row {
                    cell(createFixedWidthToggleCell(addressIncludeScriptCborCheck, toggleColumnWidth))
                    cell(addressScriptTemplateField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Template for script file. Use %module% and %validator%. Use '/' to create subdirectories.")
                }

                row {
                    cell(createFixedWidthToggleCell(addressIncludeTestnetAddressCheck, toggleColumnWidth))
                    cell(addressTestnetTemplateField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Template for testnet address file. Use %module% and %validator%. Use '/' to create subdirectories.")
                }

                row {
                    cell(createFixedWidthToggleCell(addressIncludeMainnetAddressCheck, toggleColumnWidth))
                    cell(addressMainnetTemplateField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Template for mainnet address file. Use %module% and %validator%. Use '/' to create subdirectories.")
                }

                row {
                    cell(createFixedWidthToggleCell(addressGeneratePolicyIdCheck, toggleColumnWidth))
                    cell(addressPolicyTemplateField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Template for policy file. Use %module% and %validator%. Use '/' to create subdirectories.")
                }

                row("Artifacts base path:") {
                    cell(addressArtifactsBasePathField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Output directory for auto-saved selected artifacts.")
                }
            }
        }
    }

    private fun createFixedWidthToggleCell(checkBox: JBCheckBox, width: Int): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(width, checkBox.preferredSize.height)
            minimumSize = Dimension(width, checkBox.preferredSize.height)
            add(checkBox, BorderLayout.WEST)
        }
    }

    private fun applyUnifiedDescriptionFont(component: Component) {
        when (component) {
            is JLabel -> {
                if (isContextHelpColor(component.foreground)) {
                    component.font = component.font.deriveFont(component.font.size2D * descriptionFontScale)
                }
            }

            is JTextComponent -> {
                if (isContextHelpColor(component.foreground)) {
                    component.font = component.font.deriveFont(component.font.size2D * descriptionFontScale)
                }
            }
        }
        if (component is java.awt.Container) {
            component.components.forEach(::applyUnifiedDescriptionFont)
        }
    }

    private fun isContextHelpColor(color: Color?): Boolean {
        val contextHelp = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        return color != null &&
            color.red == contextHelp.red &&
            color.green == contextHelp.green &&
            color.blue == contextHelp.blue &&
            color.alpha == contextHelp.alpha
    }

    private fun artifactsToggleColumnWidth(): Int {
        val maxToggleWidth = listOf(
            addressIncludeScriptCborCheck,
            addressIncludeTestnetAddressCheck,
            addressIncludeMainnetAddressCheck,
            addressGeneratePolicyIdCheck
        ).maxOf { it.preferredSize.width }
        return maxToggleWidth + JBUI.scale(8)
    }

    private fun createCheckPanel(): JComponent {
        return panel {
            group("Options") {
                row("Warnings handling:") {
                    cell(checkWarningsHandlingCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Ignore warnings, keep normal behavior, or treat warnings as errors.")
                }

                row {
                    cell(checkWatchCheck)
                }

                row {
                    cell(checkSkipTestsCheck)
                }

                row("Output mode:") {
                    cell(checkOutputModeCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("TTY for terminal output, JSON for machine output, IDE integrated for test tree.")
                }

                row {
                    cell(checkDebugCheck)
                }

                row("Seed:") {
                    cell(checkSeedField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Unsigned integer seed for deterministic property tests.")
                }

                row("Max success:") {
                    cell(checkMaxSuccessField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Maximum successful iterations per property test.")
                }

                row("Property coverage mode:") {
                    cell(checkPropertyCoverageCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("How property-test label coverage is evaluated.")
                }

                row("Match tests pattern:") {
                    cell(checkMatchTestsField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Examples: list, aiken/list, aiken/list.{map}, module. Separate entries with comma or space.")
                    cell(checkExactMatchCheck)
                        .align(AlignX.RIGHT)
                }

                row("Check environment:") {
                    cell(checkEnvField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Environment name from aiken.toml, for example preprod.")
                }

                row("Trace filter:") {
                    cell(checkTraceFilterCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Select which trace categories to include while checking.")
                }

                row("Trace level:") {
                    cell(checkTraceLevelCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Controls detail level of traces in check output.")
                }
            }
        }
    }

    private fun createCleanPanel(): JComponent {
        return panel {
            group("Options") {
                row("Target directory:") {
                    cell(cleanTargetPathField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Directory whose contents will be deleted. Relative paths resolve from project directory.")
                }
            }
        }
    }

    private fun createApplyPanel(): JComponent {
        return panel {
            group("Options") {
                row("Input blueprint file:") {
                    cell(applyInField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Blueprint JSON used as input. Default is plutus.json.")
                }

                row("Output blueprint file:") {
                    cell(applyOutField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Output blueprint file path. Default is plutus.json.")
                }

                row("Module:") {
                    cell(applyModuleField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Validator module. Optional only if blueprint has a single validator.")
                }

                row("Validator:") {
                    cell(applyValidatorField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Validator name in the selected module. Optional only if unique.")
                }

                row("Auto aplied CBOR parameters:") {
                    cell(applyDefaultCborParametersField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Tip: run manual parameterization first, then paste those CBOR values here. Separate values with comma or space.")
                }

                row {
                    cell(applyAutoUntilNoParametersCheck)
                        .comment("If disabled, run Apply separately for each parameter.")
                }

                row("Output mode:") {
                    cell(applyOutputModeCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("TTY for native interactive prompts, IDE integrated for structured GUI parameterization.")
                }
            }
        }
    }

    private fun showCommandCard(command: AikenRunCommand) {
        val card =
            when (command) {
                AikenRunCommand.BUILD -> buildCard
                AikenRunCommand.ADDRESS -> artifactsCard
                AikenRunCommand.CLEAN -> cleanCard
                AikenRunCommand.APPLY -> applyCard
                AikenRunCommand.CONVERT -> artifactsCard
                AikenRunCommand.CHECK -> checkCard
            }
        (commandSpecificPanel.layout as? CardLayout)?.show(commandSpecificPanel, card)
    }

    private fun updatePreferredSize(command: AikenRunCommand) {
        val preferredHeight =
            when (command) {
                AikenRunCommand.ADDRESS -> 840
                AikenRunCommand.CLEAN -> 420
                AikenRunCommand.APPLY -> 740
                AikenRunCommand.CONVERT -> 840
                AikenRunCommand.BUILD -> 680
                AikenRunCommand.CHECK -> 900
            }
        rootContent?.preferredSize = Dimension(860, preferredHeight)
        rootContent?.revalidate()
    }

    private enum class CheckWarningsHandling(private val label: String) {
        IGNORE("Ignore"),
        NORMAL("Normal"),
        TREAT_AS_ERRORS("Treat as errors");

        override fun toString(): String = label

        companion object {
            fun fromFlags(denyWarnings: Boolean, silentWarnings: Boolean): CheckWarningsHandling {
                return when {
                    denyWarnings -> TREAT_AS_ERRORS
                    silentWarnings -> IGNORE
                    else -> NORMAL
                }
            }
        }
    }

}
