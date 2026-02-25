package com.medusalabs.aiken.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel

class AikenRunConfigurationEditor : SettingsEditor<AikenRunConfiguration>() {
    private val buildCard = "build"
    private val addressCard = "address"
    private val applyCard = "apply"
    private val convertCard = "convert"
    private val checkCard = "check"

    private val commandLabel = JBLabel()
    private val commandSpecificPanel = JPanel(CardLayout())
    private var rootContent: JComponent? = null

    private val projectDirectoryField = TextFieldWithBrowseButton(JBTextField())
    private val aikenBinaryField = TextFieldWithBrowseButton(JBTextField())
    private val extraArgsField = JBTextField()

    private val buildDenyWarningsCheck = JBCheckBox("Treat warnings as errors")
    private val buildSilentWarningsCheck = JBCheckBox("Hide warnings output")
    private val buildWatchCheck = JBCheckBox("Re-run on file changes")

    private val checkDenyWarningsCheck = JBCheckBox("Treat warnings as errors")
    private val checkSilentWarningsCheck = JBCheckBox("Hide warnings output")
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
    private val addressGeneratePolicyIdCheck = JBCheckBox("Generate policy ID")
    private val addressArtifactsBasePathField = JBTextField()

    private val applyInField = JBTextField()
    private val applyOutField = JBTextField()
    private val applyModuleField = JBTextField()
    private val applyValidatorField = JBTextField()
    private val applyCborField = JBTextField()

    private val convertModuleField = JBTextField()
    private val convertValidatorField = JBTextField()
    private val convertToCombo = JComboBox(AikenBlueprintConvertTarget.entries.toTypedArray())
    private val convertTerminalOutputFileField = JBTextField()

    private val checkSkipTestsCheck = JBCheckBox("Skip tests")
    private val checkOutputModeCombo = JComboBox(AikenCheckOutputMode.entries.toTypedArray())
    private val checkDebugCheck = JBCheckBox("Pretty-print failing test UPLC")
    private val checkSeedField = JBTextField()
    private val checkMaxSuccessField = JBTextField()
    private val checkPropertyCoverageCombo = JComboBox(AikenPropertyCoverage.entries.toTypedArray())
    private val checkMatchTestsField = JBTextField()
    private val checkExactMatchCheck = JBCheckBox("Exact test name match")
    private val checkExactMatchPanel = JPanel(BorderLayout())
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

        applyInField.emptyText.text = "plutus.json"
        applyOutField.emptyText.text = "plutus.json"
        applyModuleField.emptyText.text = "e.g. my_module"
        applyValidatorField.emptyText.text = "e.g. my_validator"
        applyCborField.emptyText.text = "e.g. 182A"

        convertModuleField.emptyText.text = "e.g. my_module"
        convertValidatorField.emptyText.text = "e.g. my_validator"
        addressArtifactsBasePathField.emptyText.text = "artifacts"
        convertTerminalOutputFileField.emptyText.text = "artifacts"

        checkSeedField.emptyText.text = "e.g. 42"
        checkMaxSuccessField.emptyText.text = "100"
        checkMatchTestsField.emptyText.text = "e.g. list, aiken/list, aiken/list.{map}"
        checkEnvField.emptyText.text = "e.g. preprod"
        checkExactMatchPanel.isOpaque = false
        checkExactMatchPanel.border = JBUI.Borders.empty(2, 0)
        checkExactMatchPanel.add(checkExactMatchCheck, BorderLayout.WEST)
    }

    override fun resetEditorFrom(configuration: AikenRunConfiguration) {
        commandLabel.text = configuration.command.cliValue
        showCommandCard(configuration.command)
        updatePreferredSize(configuration.command)
        projectDirectoryField.text =
            configuration.projectDirectory.ifBlank { configuration.project.basePath ?: "" }
        aikenBinaryField.text = configuration.aikenBinaryPath.ifBlank { "aiken" }
        extraArgsField.text = configuration.extraArgs

        buildDenyWarningsCheck.isSelected = configuration.denyWarnings
        buildSilentWarningsCheck.isSelected = configuration.silentWarnings
        buildWatchCheck.isSelected = configuration.watch

        checkDenyWarningsCheck.isSelected = configuration.denyWarnings
        checkSilentWarningsCheck.isSelected = configuration.silentWarnings
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
        addressGeneratePolicyIdCheck.isSelected = configuration.addressGeneratePolicyId
        addressArtifactsBasePathField.text = configuration.addressArtifactsBasePath.ifBlank { "artifacts" }

        applyInField.text = configuration.applyInput.ifBlank { "plutus.json" }
        applyOutField.text = configuration.applyOut.ifBlank { "plutus.json" }
        applyModuleField.text = configuration.applyModule
        applyValidatorField.text = configuration.applyValidator
        applyCborField.text = configuration.applyCbor

        convertModuleField.text = configuration.convertModule
        convertValidatorField.text = configuration.convertValidator
        convertToCombo.selectedItem = configuration.convertTo
        convertTerminalOutputFileField.text =
            configuration.convertTerminalOutputFile.ifBlank { "artifacts" }

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
                configuration.denyWarnings = checkDenyWarningsCheck.isSelected
                configuration.silentWarnings = checkSilentWarningsCheck.isSelected
                configuration.watch = checkWatchCheck.isSelected
            }

            AikenRunCommand.ADDRESS -> {
                // Not used by `aiken blueprint address`; keep existing values untouched.
            }

            AikenRunCommand.APPLY -> {
                // No shared toggles used by `aiken blueprint apply`.
            }

            AikenRunCommand.CONVERT -> {
                // No shared toggles used by `aiken blueprint convert`.
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
        configuration.addressGeneratePolicyId = addressGeneratePolicyIdCheck.isSelected
        configuration.addressArtifactsBasePath =
            addressArtifactsBasePathField.text.trim().ifEmpty { "artifacts" }

        configuration.applyInput = applyInField.text.trim()
        configuration.applyOut = applyOutField.text.trim()
        configuration.applyModule = applyModuleField.text.trim()
        configuration.applyValidator = applyValidatorField.text.trim()
        configuration.applyCbor = applyCborField.text.trim()

        configuration.convertModule = convertModuleField.text.trim()
        configuration.convertValidator = convertValidatorField.text.trim()
        configuration.convertTo =
            (convertToCombo.selectedItem as? AikenBlueprintConvertTarget)
                ?: AikenBlueprintConvertTarget.CARDANO_CLI
        configuration.convertTerminalOutputFile =
            convertTerminalOutputFileField.text.trim().ifEmpty { "artifacts" }

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
        commandSpecificPanel.add(createBuildPanel(), buildCard)
        commandSpecificPanel.add(createAddressPanel(), addressCard)
        commandSpecificPanel.add(createApplyPanel(), applyCard)
        commandSpecificPanel.add(createConvertPanel(), convertCard)
        commandSpecificPanel.add(createCheckPanel(), checkCard)

        val content = panel {
            group("General") {
                row("Command:") {
                    cell(commandLabel)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }

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

        rootContent = content
        return ScrollPaneFactory.createScrollPane(content, true)
    }

    private fun createBuildPanel(): JComponent {
        return panel {
            group("build options") {
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

    private fun createAddressPanel(): JComponent {
        return panel {
            group("address options") {
                row("Input blueprint file:") {
                    cell(addressInField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Blueprint JSON file used to resolve validators and produce addresses.")
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

                row {
                    cell(addressGeneratePolicyIdCheck)
                        .comment("Also compute policy ID for each selected validator.")
                }

                row("Artifacts base path:") {
                    cell(addressArtifactsBasePathField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Output directory for auto-saved files: module.validator.addr/addr_test/policy.")
                }
            }
        }
    }

    private fun createCheckPanel(): JComponent {
        return panel {
            group("check options") {
                row {
                    cell(checkDenyWarningsCheck)
                }

                row {
                    cell(checkSilentWarningsCheck)
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
                }

                row {
                    cell(checkExactMatchPanel)
                        .resizableColumn()
                        .align(AlignX.FILL)
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

    private fun createApplyPanel(): JComponent {
        return panel {
            group("apply options") {
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

                row("Parameter CBOR:") {
                    cell(applyCborField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Plutus Data parameter as hex-encoded CBOR (for example 182A).")
                }
            }
        }
    }

    private fun createConvertPanel(): JComponent {
        return panel {
            group("convert options") {
                row("Module:") {
                    cell(convertModuleField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional module name. Leave empty when blueprint has a single validator.")
                }

                row("Validator:") {
                    cell(convertValidatorField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional validator name. Leave empty when blueprint has a single validator.")
                }

                row("Convert to:") {
                    cell(convertToCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Output format for conversion.")
                }

                row("Script output directory:") {
                    cell(convertTerminalOutputFileField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Directory where converted script is saved as module.validator.script.")
                }
            }
        }
    }

    private fun showCommandCard(command: AikenRunCommand) {
        val card =
            when (command) {
                AikenRunCommand.BUILD -> buildCard
                AikenRunCommand.ADDRESS -> addressCard
                AikenRunCommand.APPLY -> applyCard
                AikenRunCommand.CONVERT -> convertCard
                AikenRunCommand.CHECK -> checkCard
            }
        (commandSpecificPanel.layout as? CardLayout)?.show(commandSpecificPanel, card)
    }

    private fun updatePreferredSize(command: AikenRunCommand) {
        val preferredHeight =
            when (command) {
                AikenRunCommand.ADDRESS -> 640
                AikenRunCommand.APPLY -> 680
                AikenRunCommand.CONVERT -> 640
                AikenRunCommand.BUILD -> 680
                AikenRunCommand.CHECK -> 760
            }
        rootContent?.preferredSize = Dimension(860, preferredHeight)
        rootContent?.revalidate()
    }
}
