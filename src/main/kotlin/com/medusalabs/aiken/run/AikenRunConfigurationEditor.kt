package com.medusalabs.aiken.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
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
    private val checkDebugCheck = JBCheckBox("Pretty-print failing test UPLC")
    private val checkSeedField = JBTextField()
    private val checkMaxSuccessField = JBTextField()
    private val checkPropertyCoverageCombo = JComboBox(AikenPropertyCoverage.entries.toTypedArray())
    private val checkMatchTestsField = JBTextField()
    private val checkExactMatchCheck = JBCheckBox("Use exact test-name match")
    private val checkEnvField = JBTextField()
    private val checkTraceFilterCombo = JComboBox(AikenTraceFilter.entries.toTypedArray())
    private val checkTraceLevelCombo = JComboBox(AikenTraceLevel.entries.toTypedArray())

    init {
        projectDirectoryField.addBrowseFolderListener(
            "Select project directory",
            "Directory passed to `aiken ... [DIRECTORY]` and used as working directory.",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        aikenBinaryField.addBrowseFolderListener(
            "Select aiken binary",
            "Path to `aiken` executable.",
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        )

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
        checkMatchTestsField.emptyText.text = "e.g. \"aiken/list.{map}\""
        checkEnvField.emptyText.text = "e.g. preprod"
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

        return panel {
            group("General") {
                row("Command:") {
                    cell(commandLabel)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Command is fixed by the selected configuration type.")

                row("Project directory:") {
                    cell(projectDirectoryField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Used as working directory and passed as [DIRECTORY] to the command.")

                row("Aiken binary path:") {
                    cell(aikenBinaryField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Executable name or path for Aiken CLI.")

                row("Extra arguments:") {
                    cell(extraArgsField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Raw CLI args appended at the end. Useful for uncommon flags.")
            }

            row {
                cell(commandSpecificPanel)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }.also {
            it.preferredSize = Dimension(860, 760)
        }
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
                }.comment("Environment name for build (`--env <ENV>`).")

                row("Blueprint output file:") {
                    cell(buildOutField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Relative output path for blueprint (`--out <FILEPATH>`).")

                row("Trace filter:") {
                    cell(buildTraceFilterCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Choose traces included in generated programs (`--trace-filter`).")

                row("Trace level:") {
                    cell(buildTraceLevelCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Set trace verbosity for build (`--trace-level`).")
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
                }.comment("Plutus blueprint input file (`--in <FILEPATH>`).")

                row("Module:") {
                    cell(addressModuleField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Validator module name (`--module <MODULE>`).")

                row("Validator:") {
                    cell(addressValidatorField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Validator name in module (`--validator <VALIDATOR>`).")

                row("Delegated stake address:") {
                    cell(addressDelegatedToField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Stake address to attach (`--delegated-to <DELEGATED_TO>`).")

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

                row {
                    cell(checkDebugCheck)
                }

                row("Seed:") {
                    cell(checkSeedField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Initial unsigned seed for property tests (`--seed <UINT>`).")

                row("Max success:") {
                    cell(checkMaxSuccessField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Maximum successful runs for property tests (`--max-success <UINT>`).")

                row("Property coverage mode:") {
                    cell(checkPropertyCoverageCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Coverage mode for property labels (`--property-coverage`).")

                row("Match tests pattern:") {
                    cell(checkMatchTestsField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Run only tests matching the pattern (`--match-tests`).")

                row {
                    cell(checkExactMatchCheck)
                }

                row("Check environment:") {
                    cell(checkEnvField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Environment name for check (`--env <ENV>`).")

                row("Trace filter:") {
                    cell(checkTraceFilterCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Choose included traces for check (`--trace-filter`).")

                row("Trace level:") {
                    cell(checkTraceLevelCombo)
                        .resizableColumn()
                        .align(AlignX.FILL)
                }.comment("Set trace verbosity for check (`--trace-level`).")
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
