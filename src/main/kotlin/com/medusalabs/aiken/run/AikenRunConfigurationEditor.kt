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
    private val checkCard = "check"

    private val commandLabel = JBLabel()
    private val commandSpecificPanel = JPanel(CardLayout())

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

    private val addressInField = JBTextField()
    private val addressModuleField = JBTextField()
    private val addressValidatorField = JBTextField()
    private val addressDelegatedToField = JBTextField()
    private val addressMainnetCheck = JBCheckBox("Generate mainnet address")

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
        addressModuleField.emptyText.text = "e.g. nse_housing"
        addressValidatorField.emptyText.text = "e.g. spend"
        addressDelegatedToField.emptyText.text = "e.g. stake_test1..."

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

        addressInField.text = configuration.addressInput.ifBlank { "plutus.json" }
        addressModuleField.text = configuration.addressModule
        addressValidatorField.text = configuration.addressValidator
        addressDelegatedToField.text = configuration.addressDelegatedTo
        addressMainnetCheck.isSelected = configuration.addressMainnet

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
                // Not used by `aiken address`; keep existing values untouched.
            }
        }

        configuration.buildUplc = buildUplcCheck.isSelected
        configuration.buildEnv = buildEnvField.text.trim()
        configuration.buildOut = buildOutField.text.trim()
        configuration.buildTraceFilter =
            (buildTraceFilterCombo.selectedItem as? AikenTraceFilter) ?: AikenTraceFilter.ALL
        configuration.buildTraceLevel =
            (buildTraceLevelCombo.selectedItem as? AikenTraceLevel) ?: AikenTraceLevel.SILENT

        configuration.addressInput = addressInField.text.trim()
        configuration.addressModule = addressModuleField.text.trim()
        configuration.addressValidator = addressValidatorField.text.trim()
        configuration.addressDelegatedTo = addressDelegatedToField.text.trim()
        configuration.addressMainnet = addressMainnetCheck.isSelected

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

        content.preferredSize = Dimension(860, 760)
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
                        .comment("Blueprint JSON file to read validators from.")
                }

                row("Module:") {
                    cell(addressModuleField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Validator module name inside the blueprint.")
                }

                row("Validator:") {
                    cell(addressValidatorField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Validator function name in the selected module.")
                }

                row("Delegated stake address:") {
                    cell(addressDelegatedToField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment("Optional stake address to include in the resulting address.")
                }

                row {
                    cell(addressMainnetCheck)
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

    private fun showCommandCard(command: AikenRunCommand) {
        val card =
            when (command) {
                AikenRunCommand.BUILD -> buildCard
                AikenRunCommand.ADDRESS -> addressCard
                AikenRunCommand.CHECK -> checkCard
            }
        (commandSpecificPanel.layout as? CardLayout)?.show(commandSpecificPanel, card)
    }
}
