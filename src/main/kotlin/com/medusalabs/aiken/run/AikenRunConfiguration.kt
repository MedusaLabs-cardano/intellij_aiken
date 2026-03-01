package com.medusalabs.aiken.run

import com.intellij.build.BuildTreeConsoleView
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.MessageEvent
import com.intellij.build.progress.BuildRootProgressImpl
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionException
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.events.TreeNodeEvent
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeViewProvider
import com.intellij.execution.testframework.sm.runner.ui.TestTreeRenderer
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.pty4j.PtyProcessBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.execution.testframework.Filter
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jdom.Element
import java.io.File
import java.io.IOException
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Base64
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.intellij.util.messages.MessageBusConnection
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.terminal.TerminalExecutionConsole
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

class AikenRunConfiguration(
    project: com.intellij.openapi.project.Project,
    factory: ConfigurationFactory,
    name: String,
    initialCommand: AikenRunCommand = AikenRunCommand.CHECK
) : LocatableConfigurationBase<Any>(project, factory, name) {
    @JvmField
    var command: AikenRunCommand = initialCommand

    @JvmField
    var projectDirectory: String = project.basePath ?: ""

    @JvmField
    var aikenBinaryPath: String = "aiken"

    @JvmField
    var extraArgs: String = ""

    @JvmField
    var denyWarnings: Boolean = false

    @JvmField
    var silentWarnings: Boolean = false

    @JvmField
    var watch: Boolean = false

    @JvmField
    var buildUplc: Boolean = false

    @JvmField
    var buildEnv: String = ""

    @JvmField
    var buildOut: String = "plutus.json"

    @JvmField
    var buildTraceFilter: AikenTraceFilter = AikenTraceFilter.ALL

    @JvmField
    var buildTraceLevel: AikenTraceLevel = AikenTraceLevel.SILENT

    @JvmField
    var buildOutputMode: AikenBuildOutputMode = AikenBuildOutputMode.IDE_INTEGRATED

    @JvmField
    var addressInput: String = "plutus.json"

    @JvmField
    var addressModule: String = ""

    @JvmField
    var addressValidator: String = ""

    @JvmField
    var addressDelegatedTo: String = ""

    @JvmField
    var addressGeneratePolicyId: Boolean = true

    @JvmField
    var addressIncludeScriptCbor: Boolean = true

    @JvmField
    var addressIncludeTestnetAddress: Boolean = true

    @JvmField
    var addressIncludeMainnetAddress: Boolean = true

    @JvmField
    var addressScriptTemplate: String = DEFAULT_ARTIFACT_SCRIPT_TEMPLATE

    @JvmField
    var addressMainnetTemplate: String = DEFAULT_ARTIFACT_MAINNET_TEMPLATE

    @JvmField
    var addressTestnetTemplate: String = DEFAULT_ARTIFACT_TESTNET_TEMPLATE

    @JvmField
    var addressPolicyTemplate: String = DEFAULT_ARTIFACT_POLICY_TEMPLATE

    @JvmField
    var addressArtifactsBasePath: String = "artifacts"

    @JvmField
    var cleanTargetPath: String = "artifacts"

    @JvmField
    var applyInput: String = "plutus.json"

    @JvmField
    var applyOut: String = "plutus.json"

    @JvmField
    var applyModule: String = ""

    @JvmField
    var applyValidator: String = ""

    @JvmField
    var applyDefaultCborParameters: String = ""

    @JvmField
    var applyAutoUntilNoParameters: Boolean = true

    @JvmField
    var convertModule: String = ""

    @JvmField
    var convertValidator: String = ""

    @JvmField
    var convertTo: AikenBlueprintConvertTarget = AikenBlueprintConvertTarget.CARDANO_CLI

    @JvmField
    var convertTerminalOutputFile: String = "artifacts"

    @JvmField
    var checkSkipTests: Boolean = false

    @JvmField
    var checkOutputMode: AikenCheckOutputMode = AikenCheckOutputMode.IDE_INTEGRATED

    @JvmField
    var checkDebug: Boolean = false

    @JvmField
    var checkSeed: String = ""

    @JvmField
    var checkMaxSuccess: String = "100"

    @JvmField
    var checkPropertyCoverage: AikenPropertyCoverage = AikenPropertyCoverage.RELATIVE_TO_LABELS

    @JvmField
    var checkMatchTests: String = ""

    @JvmField
    var checkExactMatch: Boolean = false

    @JvmField
    var checkEnv: String = ""

    @JvmField
    var checkTraceFilter: AikenTraceFilter = AikenTraceFilter.ALL

    @JvmField
    var checkTraceLevel: AikenTraceLevel = AikenTraceLevel.VERBOSE

    override fun getConfigurationEditor(): SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> {
        return AikenRunConfigurationEditor()
    }

    override fun checkConfiguration() {
        validateDirectory(projectDirectory, "Project directory")
        validateFilePath(aikenBinaryPath, "Aiken binary path")
        if (command == AikenRunCommand.CHECK) {
            validateUnsignedInteger(checkSeed, "Seed")
            validateUnsignedInteger(checkMaxSuccess, "Max success")
            if (checkExactMatch && checkMatchTests.isBlank()) {
                throw RuntimeConfigurationError("Exact match requires a non-empty match-tests pattern.")
            }
        }
    }

    override fun suggestedName(): String = command.defaultConfigurationName()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (command == AikenRunCommand.ADDRESS || command == AikenRunCommand.CONVERT) {
            return AikenArtifactsRunState(environment)
        }

        if (command == AikenRunCommand.CLEAN) {
            return AikenCleanRunState(environment)
        }

        if (command == AikenRunCommand.APPLY && applyAutoUntilNoParameters) {
            return AikenApplyAutoRunState(environment)
        }

        if (
            command == AikenRunCommand.BUILD &&
            buildOutputMode == AikenBuildOutputMode.IDE_INTEGRATED
        ) {
            return AikenBuildMessagesRunState(environment)
        }

        if (
            command == AikenRunCommand.CHECK &&
            checkOutputMode == AikenCheckOutputMode.IDE_INTEGRATED
        ) {
            return AikenCheckTestRunState(environment)
        }

        return object : CommandLineState(environment) {
            private var startedHandler: ProcessHandler? = null

            override fun startProcess(): ProcessHandler {
                val executable = resolveAikenExecutable()
                val workDir = resolveProjectDirectory()
                val args = buildCommandParameters()
                val invocation = buildInvocation(executable, args, workDir)

                // `aiken check` and `aiken build` emit rich human-readable output in TTY mode.
                if (
                    (command == AikenRunCommand.CHECK && checkOutputMode == AikenCheckOutputMode.TTY) ||
                    (command == AikenRunCommand.BUILD && buildOutputMode == AikenBuildOutputMode.TTY)
                ) {
                    return createPtyProcessHandler(invocation, workDir).also { startedHandler = it }
                }
                if (command == AikenRunCommand.APPLY) {
                    return createPtyTerminalProcessHandler(invocation, workDir).also { startedHandler = it }
                }

                val commandLine = GeneralCommandLine()
                    .withCharset(StandardCharsets.UTF_8)
                    .withExePath(executable)
                workDir?.let { commandLine.withWorkDirectory(it) }
                commandLine.addParameters(commandInvocationTokens())
                commandLine.addParameters(args)
                workDir?.let { commandLine.addParameter(it) }

                return KillableColoredProcessHandler(commandLine).also { startedHandler = it }
            }

            override fun createConsole(executor: Executor): ConsoleView? {
                if (command == AikenRunCommand.APPLY) {
                    val handler = startedHandler
                    if (handler != null && TerminalExecutionConsole.isAcceptable(handler)) {
                        return TerminalExecutionConsole(project, handler)
                    }
                }
                return super.createConsole(executor)
            }
        }
    }

    private inner class AikenApplyAutoRunState(
        private val executionEnvironment: ExecutionEnvironment
    ) : RunProfileState {
        override fun execute(executor: Executor, runner: ProgramRunner<*>): com.intellij.execution.ExecutionResult {
            val executable = resolveAikenExecutable()
            val workDir = resolveProjectDirectory()
            val inspectionFile = resolveApplyInspectionFile(workDir)
            val sessionKey = applyAutoSessionKey(inspectionFile, workDir)
            val accumulatedParameters =
                APPLY_AUTO_CBOR_ACCUMULATOR.computeIfAbsent(sessionKey) { mutableListOf() }
            val cborQueue =
                APPLY_AUTO_CBOR_QUEUE.computeIfAbsent(sessionKey) {
                    ArrayDeque(configuredApplyCborParameters())
                }
            val hasNonConfiguredApplied =
                APPLY_AUTO_HAS_NON_CONFIGURED_APPLIES.computeIfAbsent(sessionKey) { AtomicBoolean(false) }

            val pendingBeforeStart = inspectionFile?.let { hasPendingApplyParameters(it) }
            if (pendingBeforeStart == false) {
                val completion = buildString {
                    append("Blueprint has no parameters left to apply\n")
                    val snapshot = synchronized(accumulatedParameters) { accumulatedParameters.toList() }
                    if (snapshot.isNotEmpty()) {
                        append(formatAppliedCborParametersLine(snapshot))
                        append('\n')
                        if (hasNonConfiguredApplied.get()) {
                            append(APPLY_PARAMETERS_HINT_LINE)
                            append('\n')
                        }
                    }
                }
                APPLY_AUTO_CBOR_ACCUMULATOR.remove(sessionKey)
                APPLY_AUTO_CBOR_QUEUE.remove(sessionKey)
                APPLY_AUTO_HAS_NON_CONFIGURED_APPLIES.remove(sessionKey)
                return createCompletedApplyResult(completion)
            }

            val cborForRun =
                synchronized(cborQueue) {
                    if (cborQueue.isEmpty()) null else cborQueue.removeFirst()
                }
            val args = buildApplyCommandParameters(cborForRun)
            val invocation = buildInvocation(executable, args, workDir)
            val handler = createPtyTerminalProcessHandler(invocation, workDir)
            val runOutput = StringBuilder()

            handler.addProcessListener(
                object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                        runOutput.append(event.text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        val appliedInRun = extractLastAppliedCborFromApplyOutput(runOutput.toString())
                        if (!appliedInRun.isNullOrBlank()) {
                            synchronized(accumulatedParameters) {
                                accumulatedParameters += appliedInRun
                            }
                            if (cborForRun.isNullOrBlank()) {
                                hasNonConfiguredApplied.set(true)
                            }
                        }

                        if (event.exitCode != 0) {
                            val snapshot = synchronized(accumulatedParameters) { accumulatedParameters.toList() }
                            if (snapshot.isNotEmpty()) {
                                val hintSuffix =
                                    if (hasNonConfiguredApplied.get()) {
                                        "\n$APPLY_PARAMETERS_HINT_LINE"
                                    } else {
                                        ""
                                    }
                                handler.notifyTextAvailable(
                                    "\n${formatAppliedCborParametersLine(snapshot)}$hintSuffix\n",
                                    ProcessOutputTypes.STDOUT
                                )
                            }
                            APPLY_AUTO_CBOR_ACCUMULATOR.remove(sessionKey)
                            APPLY_AUTO_CBOR_QUEUE.remove(sessionKey)
                            APPLY_AUTO_HAS_NON_CONFIGURED_APPLIES.remove(sessionKey)
                            return
                        }
                        val hasPending = inspectionFile?.let { hasPendingApplyParameters(it) }
                        if (hasPending == false) {
                            val completion = buildString {
                                append(ANSI_CLEAR_SCREEN_AND_HOME)
                                append("\rBlueprint has no parameters left to apply\r\n")
                                val snapshot = synchronized(accumulatedParameters) { accumulatedParameters.toList() }
                                if (snapshot.isNotEmpty()) {
                                    append("\r")
                                    append(formatAppliedCborParametersLine(snapshot))
                                    append("\r\n")
                                    if (hasNonConfiguredApplied.get()) {
                                        append("\r")
                                        append(APPLY_PARAMETERS_HINT_LINE)
                                        append("\r\n")
                                    }
                                }
                            }
                            handler.notifyTextAvailable(completion, ProcessOutputTypes.STDOUT)
                            APPLY_AUTO_CBOR_ACCUMULATOR.remove(sessionKey)
                            APPLY_AUTO_CBOR_QUEUE.remove(sessionKey)
                            APPLY_AUTO_HAS_NON_CONFIGURED_APPLIES.remove(sessionKey)
                            return
                        }
                        ApplicationManager.getApplication().invokeLater {
                            ExecutionUtil.restart(executionEnvironment)
                        }
                    }
                }
            )

            val console =
                if (TerminalExecutionConsole.isAcceptable(handler)) {
                    TerminalExecutionConsole(project, handler)
                } else {
                    TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { it.attachToProcess(handler) }
                }

            return DefaultExecutionResult(console, handler)
        }
    }

    private fun createCompletedApplyResult(message: String): com.intellij.execution.ExecutionResult {
        val handler = AikenAsyncProcessHandler()
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        handler.startNotify()
        handler.notifyTextAvailable(message, ProcessOutputTypes.STDOUT)
        handler.finish(0)
        return DefaultExecutionResult(console, handler)
    }

    private inner class AikenCheckTestRunState(
        private val executionEnvironment: ExecutionEnvironment
    ) : RunProfileState {
        override fun execute(executor: Executor, runner: ProgramRunner<*>): com.intellij.execution.ExecutionResult {
            val processHandler = AikenAsyncProcessHandler()
            val consoleProperties =
                object : SMTRunnerConsoleProperties(this@AikenRunConfiguration, "Aiken", executor),
                    SMTRunnerTestTreeViewProvider {
                    override fun getTestLocator(): SMTestLocator = AikenTestLocator

                    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction? {
                        val container = consoleView as? ComponentContainer ?: return null
                        return AikenRerunSelectedTestAction(container, this@AikenRunConfiguration)
                    }

                    override fun createSMTRunnerTestTreeView(): SMTRunnerTestTreeView {
                        return object : SMTRunnerTestTreeView() {
                            override fun getRenderer(properties: TestConsoleProperties): TreeCellRenderer {
                                return object : TestTreeRenderer(properties) {
                                    override fun customizeCellRenderer(
                                        tree: JTree,
                                        value: Any?,
                                        selected: Boolean,
                                        expanded: Boolean,
                                        leaf: Boolean,
                                        row: Int,
                                        hasFocus: Boolean
                                    ) {
                                        super.customizeCellRenderer(tree, value, selected, expanded, leaf, row, hasFocus)
                                        val proxy = SMTRunnerTestTreeView.getTestProxyFor(value) ?: return
                                        if (proxy.isErrorDiagnosticNode()) {
                                            icon = AllIcons.General.Error
                                        } else if (proxy.isWarningDiagnosticNode()) {
                                            icon = AllIcons.General.Warning
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            consoleProperties.setIdBasedTestTree(true)
            TestConsoleProperties.HIDE_PASSED_TESTS.primSet(consoleProperties, false)

            val console = SMTestRunnerConnectionUtil.createAndAttachConsole("Aiken", processHandler, consoleProperties)
            AppExecutorUtil.getAppExecutorService().execute {
                runCheckWithJsonParsing(processHandler, executionEnvironment)
            }
            return DefaultExecutionResult(console, processHandler)
        }

        private fun runCheckWithJsonParsing(handler: AikenAsyncProcessHandler, environment: ExecutionEnvironment) {
            handler.startNotify()
            val executable = resolveAikenExecutable()
            val workDir = resolveProjectDirectory()
            val ideWatchEnabled = watch && checkOutputMode == AikenCheckOutputMode.IDE_INTEGRATED
            val restartRequested = AtomicBoolean(false)
            val watchConnection =
                if (ideWatchEnabled) {
                    installIdeIntegratedWatch(environment, workDir, handler, restartRequested)
                } else {
                    null
                }
            var lastExitCode = 0

            try {
                val preflightRun = runTtyDiagnosticsPass(executable, workDir, handler)
                lastExitCode = preflightRun.exitCode

                if (preflightRun.exitCode != 0) {
                    val diagnostics = diagnosticsForFailedRun(preflightRun.output, treatWarningsAsErrors = denyWarnings)
                    emitDiagnosticsOnlyTeamCity(diagnostics, workDir, handler)
                    finishOrWatch(handler, ideWatchEnabled, restartRequested, preflightRun.exitCode)
                    return
                }

                if (checkSkipTests) {
                    val diagnostics = diagnosticsFromText(preflightRun.output)
                    emitDiagnosticsOnlyTeamCity(diagnostics, workDir, handler)
                    finishOrWatch(handler, ideWatchEnabled, restartRequested, preflightRun.exitCode)
                    return
                }

                var diagnostics = diagnosticsFromText(preflightRun.output)
                val args = buildCommandParameters()
                val invocation = buildInvocation(executable, args, workDir)
                val mainRun = runCommandCollectingOutput(invocation, workDir, handler, usePty = false)
                val output = mainRun.output
                lastExitCode = mainRun.exitCode
                val report = parseCheckReport(output, workDir)
                val parsedTestsCount = report?.modules?.sumOf { it.tests.size } ?: 0
                if (report != null) {
                    val prefix = output.substring(0, report.jsonStart).trimEnd()
                    val suffix = output.substring(report.jsonEndExclusive).trimStart()
                    diagnostics = mergeDiagnosticsSections(diagnostics, extractDiagnosticsSections(prefix, suffix))
                    if (prefix.isNotEmpty()) {
                        handler.notifyTextAvailable("$prefix\n", ProcessOutputTypes.STDOUT)
                    }
                    if (suffix.isNotEmpty()) {
                        handler.notifyTextAvailable("\n$suffix\n", ProcessOutputTypes.STDOUT)
                    }
                } else {
                    diagnostics =
                        if (mainRun.exitCode != 0) {
                            mergeDiagnosticsSections(
                                diagnostics,
                                diagnosticsForFailedRun(output, treatWarningsAsErrors = denyWarnings)
                            )
                        } else {
                            handler.notifyTextAvailable(output, ProcessOutputTypes.STDOUT)
                            mergeDiagnosticsSections(diagnostics, diagnosticsFromText(output))
                        }
                }

                if (report != null && parsedTestsCount > 0) {
                    emitTeamCityEvents(report, diagnostics, workDir, handler)
                } else {
                    val diagnosticsToEmit =
                        if (mainRun.exitCode != 0 && !checkSkipTests) {
                            val fallback = diagnosticsForFailedRun(output, treatWarningsAsErrors = denyWarnings)
                            mergeDiagnosticsSections(diagnostics, fallback)
                        } else {
                            diagnostics
                        }
                    emitDiagnosticsOnlyTeamCity(diagnosticsToEmit, workDir, handler)
                }

                finishOrWatch(handler, ideWatchEnabled, restartRequested, mainRun.exitCode)
            } catch (e: Exception) {
                handler.notifyTextAvailable(
                    "Aiken check integration failed: ${e.message}\n",
                    ProcessOutputTypes.STDERR
                )
                finishOrWatch(handler, ideWatchEnabled, restartRequested, if (lastExitCode != 0) lastExitCode else -1)
            } finally {
                watchConnection?.disconnect()
            }
        }

        private fun finishOrWatch(
            handler: AikenAsyncProcessHandler,
            ideWatchEnabled: Boolean,
            restartRequested: AtomicBoolean,
            exitCode: Int
        ) {
            if (!ideWatchEnabled) {
                handler.finish(exitCode)
                return
            }

            handler.notifyTextAvailable(
                "\n[watch] Save a file to rerun tests.\n",
                ProcessOutputTypes.STDOUT
            )

            while (!handler.isProcessTerminated && !restartRequested.get()) {
                Thread.sleep(150)
            }

            if (!handler.isProcessTerminated && !restartRequested.get()) {
                handler.finish(exitCode)
            }
        }

        private fun installIdeIntegratedWatch(
            environment: ExecutionEnvironment,
            workDir: String?,
            handler: AikenAsyncProcessHandler,
            restartRequested: AtomicBoolean
        ): MessageBusConnection? {
            val rootPath = normalizeWatchRootPath(workDir) ?: return null
            val lastRestartAt = AtomicLong(0L)
            val connection = project.messageBus.connect()
            connection.subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (handler.isProcessTerminated || handler.isProcessTerminating) return
                        val shouldRestart = events.any { event -> shouldTriggerWatchRestart(event, rootPath) }
                        if (!shouldRestart) return

                        val now = System.currentTimeMillis()
                        val previous = lastRestartAt.get()
                        if (now - previous < IDE_WATCH_RESTART_DEBOUNCE_MS) return
                        lastRestartAt.set(now)

                        if (!restartRequested.compareAndSet(false, true)) return

                        handler.notifyTextAvailable(
                            "\n[watch] Changes saved. Restarting Aiken check...\n",
                            ProcessOutputTypes.STDOUT
                        )

                        ApplicationManager.getApplication().invokeLater {
                            if (handler.isProcessTerminated || handler.isProcessTerminating) return@invokeLater
                            ExecutionUtil.restart(environment)
                        }
                    }
                }
            )
            return connection
        }

        private fun normalizeWatchRootPath(workDir: String?): String? {
            val root =
                when {
                    !workDir.isNullOrBlank() -> File(workDir)
                    !project.basePath.isNullOrBlank() -> File(project.basePath!!)
                    else -> return null
                }
            val path =
                try {
                    root.canonicalPath
                } catch (_: IOException) {
                    root.absolutePath
                }
            return path.replace('\\', '/').trimEnd('/')
        }

        private fun shouldTriggerWatchRestart(event: VFileEvent, rootPath: String): Boolean {
            if (!event.isFromSave) return false
            val rawPath = event.path.ifBlank { return false }
            val path = rawPath.replace('\\', '/')
            val insideRoot = path == rootPath || path.startsWith("$rootPath/")
            if (!insideRoot) return false
            if (path.contains("/.git/") || path.contains("/.idea/") || path.contains("/build/")) return false
            return path.endsWith(".ak") || path.endsWith(".toml") || path.endsWith(".json")
        }

        private fun runTtyDiagnosticsPass(
            executable: String,
            workDir: String?,
            handler: AikenAsyncProcessHandler
        ): CommandRunResult {
            val diagnosticArgs = buildCommandParameters(forceSkipTests = true)
            val invocation = buildInvocation(executable, diagnosticArgs, workDir)
            return runCommandCollectingOutput(invocation, workDir, handler, usePty = true)
        }

        private fun runCommandCollectingOutput(
            invocation: List<String>,
            workDir: String?,
            handler: AikenAsyncProcessHandler,
            usePty: Boolean
        ): CommandRunResult {
            val process =
                if (usePty) {
                    PtyProcessBuilder(invocation.toTypedArray())
                        .setDirectory(workDir ?: project.basePath)
                        .setEnvironment(HashMap(System.getenv()))
                        .setRedirectErrorStream(true)
                        .setWindowsAnsiColorEnabled(true)
                        .start()
                } else {
                    val processBuilder = ProcessBuilder(invocation)
                    if (!workDir.isNullOrBlank()) {
                        processBuilder.directory(File(workDir))
                    }
                    processBuilder.redirectErrorStream(true)
                    processBuilder.start()
                }

            handler.attachProcess(process)
            val output =
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.readText()
                }
            val exitCode = process.waitFor()
            return CommandRunResult(output = output, exitCode = exitCode)
        }

        private fun ensureTrailingNewline(text: String): String {
            return if (text.endsWith('\n')) text else "$text\n"
        }
    }

    private inner class AikenBuildMessagesRunState(
        private val executionEnvironment: ExecutionEnvironment
    ) : RunProfileState {
        override fun execute(executor: Executor, runner: ProgramRunner<*>): com.intellij.execution.ExecutionResult {
            val processHandler = AikenAsyncProcessHandler()
            val buildDescriptor = createBuildDescriptor(executionEnvironment)
            val baseConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            val buildConsole = BuildTreeConsoleView(project, buildDescriptor, baseConsole)
            buildConsole.attachToProcess(processHandler)
            val buildProgress = createBuildProgress(buildConsole, buildDescriptor)
            AppExecutorUtil.getAppExecutorService().execute {
                runBuildWithIdeIntegration(processHandler, executionEnvironment, buildProgress)
            }
            return DefaultExecutionResult(buildConsole, processHandler)
        }

        private fun createBuildDescriptor(environment: ExecutionEnvironment): DefaultBuildDescriptor {
            val workDir = resolveProjectDirectory().orEmpty()
            val buildId = "aiken-build-${System.currentTimeMillis()}"
            return DefaultBuildDescriptor(
                buildId,
                "Aiken build",
                workDir,
                System.currentTimeMillis()
            ).withExecutionEnvironment(environment)
        }

        private fun createBuildProgress(
            buildConsole: BuildTreeConsoleView,
            descriptor: DefaultBuildDescriptor
        ): BuildProgress<BuildProgressDescriptor> {
            val buildProgress: BuildProgress<BuildProgressDescriptor> = BuildRootProgressImpl(buildConsole)
            val progressDescriptor =
                object : BuildProgressDescriptor {
                    override fun getTitle(): String = descriptor.title

                    override fun getBuildDescriptor(): DefaultBuildDescriptor = descriptor
                }
            buildProgress.start(progressDescriptor)
            return buildProgress
        }

        private fun runBuildWithIdeIntegration(
            handler: AikenAsyncProcessHandler,
            environment: ExecutionEnvironment,
            buildProgress: BuildProgress<BuildProgressDescriptor>
        ) {
            handler.startNotify()
            val executable = resolveAikenExecutable()
            val workDir = resolveProjectDirectory()
            val ideWatchEnabled = watch && buildOutputMode == AikenBuildOutputMode.IDE_INTEGRATED
            val restartRequested = AtomicBoolean(false)
            val watchConnection =
                if (ideWatchEnabled) {
                    installIdeIntegratedWatch(environment, workDir, handler, restartRequested, restartMessage = "build")
                } else {
                    null
                }
            var lastExitCode = 0

            try {
                buildProgress.progress("Running aiken build")
                val args = buildCommandParameters()
                val invocation = buildInvocation(executable, args, workDir)
                val run = runCommandCollectingOutput(invocation, workDir, handler, usePty = true)
                lastExitCode = run.exitCode
                if (run.output.isNotBlank()) {
                    buildProgress.output(ensureTrailingNewline(stripAnsi(run.output)), false)
                }

                val diagnostics =
                    if (run.exitCode == 0) {
                        diagnosticsFromText(run.output)
                    } else {
                        diagnosticsForFailedRun(run.output, treatWarningsAsErrors = denyWarnings)
                    }
                emitBuildMessages(buildProgress, diagnostics)

                val buildSucceeded = run.exitCode == 0 && diagnostics.errors.isEmpty()
                if (buildSucceeded) {
                    buildProgress.message(
                        "build succeeded",
                        "Aiken build finished successfully.",
                        MessageEvent.Kind.INFO,
                        null
                    )
                    buildProgress.finish(System.currentTimeMillis(), false, "Build finished")
                } else {
                    buildProgress.fail(System.currentTimeMillis(), "Build failed")
                }
                finishOrWatch(handler, ideWatchEnabled, restartRequested, run.exitCode, idleMessage = "build")
            } catch (e: Exception) {
                handler.notifyTextAvailable(
                    "Aiken build integration failed: ${e.message}\n",
                    ProcessOutputTypes.STDERR
                )
                buildProgress.fail(System.currentTimeMillis(), "Build integration failed: ${e.message}")
                finishOrWatch(handler, ideWatchEnabled, restartRequested, if (lastExitCode != 0) lastExitCode else -1, idleMessage = "build")
            } finally {
                watchConnection?.disconnect()
            }
        }

        private fun emitBuildMessages(
            buildProgress: BuildProgress<BuildProgressDescriptor>,
            diagnostics: DiagnosticsSections
        ) {
            diagnostics.warnings.forEachIndexed { index, block ->
                val sanitizedBlock = stripAnsi(block)
                buildProgress.message(
                    buildDiagnosticNodeTitle("warnings", index, sanitizedBlock),
                    sanitizedBlock,
                    MessageEvent.Kind.WARNING,
                    null
                )
            }
            diagnostics.errors.forEachIndexed { index, block ->
                val sanitizedBlock = stripAnsi(block)
                buildProgress.message(
                    buildDiagnosticNodeTitle("errors", index, sanitizedBlock),
                    sanitizedBlock,
                    MessageEvent.Kind.ERROR,
                    null
                )
            }
        }
    }

    private inner class AikenCleanRunState(
        private val executionEnvironment: ExecutionEnvironment
    ) : RunProfileState {
        override fun execute(executor: Executor, runner: ProgramRunner<*>): com.intellij.execution.ExecutionResult {
            val processHandler = AikenAsyncProcessHandler()
            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            console.attachToProcess(processHandler)
            AppExecutorUtil.getAppExecutorService().execute {
                runCleanDirectory(processHandler)
            }
            return DefaultExecutionResult(console, processHandler)
        }

        private fun runCleanDirectory(handler: AikenAsyncProcessHandler) {
            handler.startNotify()
            val workDir = resolveProjectDirectory()
            val targetDirectory = resolveCleanTargetDirectory(workDir)

            if (targetDirectory == null) {
                handler.notifyTextAvailable(
                    "Unable to resolve clean target directory.\n",
                    ProcessOutputTypes.STDERR
                )
                handler.finish(1)
                return
            }

            val targetPath = normalizePathForDisplay(targetDirectory)
            handler.notifyTextAvailable("Cleaning directory contents: $targetPath\n", ProcessOutputTypes.STDOUT)

            if (!targetDirectory.exists()) {
                handler.notifyTextAvailable("Directory does not exist. Nothing to clean.\n", ProcessOutputTypes.STDOUT)
                handler.finish(0)
                return
            }

            if (!targetDirectory.isDirectory) {
                handler.notifyTextAvailable(
                    "Target path is not a directory: $targetPath\n",
                    ProcessOutputTypes.STDERR
                )
                handler.finish(1)
                return
            }

            if (!isSafeCleanTarget(targetDirectory, workDir)) {
                handler.notifyTextAvailable(
                    "Refusing to clean this directory for safety reasons: $targetPath\n",
                    ProcessOutputTypes.STDERR
                )
                handler.finish(1)
                return
            }

            val children = targetDirectory.listFiles()?.toList().orEmpty()
            if (children.isEmpty()) {
                handler.notifyTextAvailable("Directory is already empty.\n", ProcessOutputTypes.STDOUT)
                handler.finish(0)
                return
            }

            var removed = 0
            val failed = ArrayList<String>()
            for (child in children) {
                if (child.deleteRecursively()) {
                    removed += 1
                } else {
                    failed += normalizePathForDisplay(child)
                }
            }

            handler.notifyTextAvailable(
                "Removed $removed ${if (removed == 1) "entry" else "entries"}.\n",
                ProcessOutputTypes.STDOUT
            )

            if (failed.isNotEmpty()) {
                handler.notifyTextAvailable(
                    "Failed to remove ${failed.size} ${if (failed.size == 1) "entry" else "entries"}:\n",
                    ProcessOutputTypes.STDERR
                )
                failed.forEach { path ->
                    handler.notifyTextAvailable("  - $path\n", ProcessOutputTypes.STDERR)
                }
                handler.finish(1)
            } else {
                handler.notifyTextAvailable("Clean finished successfully.\n", ProcessOutputTypes.STDOUT)
                handler.finish(0)
            }
        }

        private fun resolveCleanTargetDirectory(workDir: String?): File? {
            val configured = cleanTargetPath.trim().ifEmpty {
                addressArtifactsBasePath.trim().ifEmpty { "artifacts" }
            }
            val file = File(configured)
            if (file.isAbsolute) return file
            val base = workDir ?: project.basePath ?: return null
            return File(base, configured)
        }

        private fun isSafeCleanTarget(target: File, workDir: String?): Boolean {
            val targetCanonical = canonicalFile(target)

            val projectRoot = (workDir ?: project.basePath)?.let { canonicalFile(File(it)) }
            if (projectRoot != null && targetCanonical == projectRoot) {
                return false
            }

            if (targetCanonical.parentFile == null) {
                return false
            }

            return true
        }

        private fun canonicalFile(file: File): File {
            return try {
                file.canonicalFile
            } catch (_: IOException) {
                file.absoluteFile
            }
        }

        private fun normalizePathForDisplay(file: File): String {
            return try {
                file.canonicalPath
            } catch (_: IOException) {
                file.absolutePath
            }
        }
    }

    private data class AddressTarget(
        val module: String?,
        val validator: String?,
        val blueprintTitle: String?
    )

    private enum class AddressNetwork(val label: String, val isMainnet: Boolean) {
        TESTNET("testnet", false),
        MAINNET("mainnet", true)
    }

    private data class AddressVariant(
        val network: AddressNetwork,
        val address: String?,
        val error: String?
    )

    private data class ArtifactsEntry(
        val module: String?,
        val validator: String?,
        val blueprintTitle: String?,
        val stakeKey: String?,
        val variants: List<AddressVariant>,
        val scriptJson: String?,
        val scriptCbor: String?,
        val scriptError: String?,
        val policyId: String?,
        val policyError: String?,
        val commonError: String?,
        val savedFiles: List<File> = emptyList()
    )

    private data class ArtifactsPersistSummary(
        val directory: File,
        val totalFiles: Int,
        val entriesByStem: Map<String, List<File>>,
        val saveError: String?
    )

    private data class ArtifactNameContext(
        val module: String,
        val validator: String
    )

    private inner class AikenArtifactsRunState(
        private val executionEnvironment: ExecutionEnvironment
    ) : RunProfileState {
        override fun execute(executor: Executor, runner: ProgramRunner<*>): com.intellij.execution.ExecutionResult {
            val processHandler = AikenAsyncProcessHandler()
            val console = AikenArtifactsExecutionConsole()
            AppExecutorUtil.getAppExecutorService().execute {
                runArtifactsIdeIntegrated(processHandler, console)
            }
            return DefaultExecutionResult(console, processHandler)
        }

        private fun runArtifactsIdeIntegrated(
            handler: AikenAsyncProcessHandler,
            console: AikenArtifactsExecutionConsole
        ) {
            handler.startNotify()
            val executable = resolveAikenExecutable()
            val workDir = resolveProjectDirectory()

            try {
                val targets = resolveAddressTargets(workDir)
                if (targets.isEmpty()) {
                    console.showFatalError("No validators found in blueprint for artifacts generation.")
                    handler.finish(1)
                    return
                }

                val entries = ArrayList<ArtifactsEntry>(targets.size)
                val stakeKey = addressDelegatedTo.trim().ifEmpty { null }

                for ((index, target) in targets.withIndex()) {
                    val displayName = buildAddressTargetDisplayName(target)
                    console.showStatus("Generating artifacts (${index + 1}/${targets.size}): $displayName")

                    var scriptJson: String? = null
                    var scriptCbor: String? = null
                    var scriptError: String? = null
                    var commonError: String? = null

                    val convertArgs = buildConvertCommandParameters(target)
                    val convertInvocation = buildBlueprintInvocation(executable, "convert", convertArgs, workDir)
                    val convertRun =
                        runBlueprintCommandCollectingOutput(
                            invocation = convertInvocation,
                            workDir = workDir,
                            handler = handler,
                            preferPty = false
                        )
                    if (convertRun.exitCode != 0) {
                        commonError =
                            formatCommandFailureDetails(
                                commandName = "aiken blueprint convert",
                                exitCode = convertRun.exitCode,
                                rawOutput = convertRun.output
                            )
                    } else {
                        scriptJson = sanitizeConvertOutputForScript(convertRun.output, convertInvocation)
                        if (addressIncludeScriptCbor) {
                            scriptCbor = parseScriptCborFromConvertedScript(scriptJson)
                            if (scriptCbor.isNullOrBlank()) {
                                scriptError =
                                    "Unable to parse script CBOR from converted contract output:\n${stripAnsi(convertRun.output).trim()}"
                            }
                        }
                    }

                    val selectedNetworks = selectedAddressNetworks()
                    val variants = ArrayList<AddressVariant>(selectedNetworks.size)
                    if (commonError == null) {
                        for (network in selectedNetworks) {
                            val args = buildAddressCommandParameters(target, network)
                            val invocation = buildBlueprintInvocation(executable, "address", args, workDir)
                            val run = runBlueprintCommandCollectingOutput(invocation, workDir, handler)
                            if (run.exitCode != 0) {
                                commonError =
                                    formatCommandFailureDetails(
                                        commandName = "aiken blueprint address",
                                        exitCode = run.exitCode,
                                        rawOutput = run.output
                                    )
                                break
                            }

                            val parsedAddress = parseAddressFromOutput(run.output)
                            if (parsedAddress.isNullOrBlank()) {
                                commonError = "Unable to parse address from output:\n${stripAnsi(run.output).trim()}"
                                break
                            }

                            variants += AddressVariant(network, parsedAddress, null)
                        }
                    }

                    var policyId: String? = null
                    var policyError: String? = null
                    if (commonError == null && addressGeneratePolicyId) {
                        val policyArgs = buildPolicyCommandParameters(target)
                        val policyInvocation = buildBlueprintInvocation(executable, "policy", policyArgs, workDir)
                        val policyRun =
                            runBlueprintCommandCollectingOutput(policyInvocation, workDir, handler)
                        if (policyRun.exitCode != 0) {
                            policyError =
                                formatCommandFailureDetails(
                                    commandName = "aiken blueprint policy",
                                    exitCode = policyRun.exitCode,
                                    rawOutput = policyRun.output
                                )
                        } else {
                            val parsedPolicy = parsePolicyFromOutput(policyRun.output)
                            if (parsedPolicy.isNullOrBlank()) {
                                policyError =
                                    "Unable to parse policy ID from output:\n${stripAnsi(policyRun.output).trim()}"
                            } else {
                                policyId = parsedPolicy
                            }
                        }
                    }

                    entries += ArtifactsEntry(
                        module = target.module,
                        validator = target.validator,
                        blueprintTitle = target.blueprintTitle,
                        stakeKey = stakeKey,
                        variants = variants,
                        scriptJson = scriptJson,
                        scriptCbor = scriptCbor,
                        scriptError = scriptError,
                        policyId = policyId,
                        policyError = policyError,
                        commonError = commonError
                    )
                }

                val persistSummary = persistArtifacts(entries, workDir)
                val persistedEntries = persistSummary?.entriesByStem ?: emptyMap()
                val finalizedEntries =
                    entries.map { entry ->
                        val stem = buildArtifactStem(entry)
                        entry.copy(savedFiles = persistedEntries[stem].orEmpty())
                    }

                console.showResults(finalizedEntries)
                val hasErrors =
                    finalizedEntries.any { entry ->
                        entry.commonError != null ||
                            entry.scriptError != null ||
                            entry.variants.any { it.error != null } ||
                            entry.policyError != null
                    }

                val status = buildArtifactsStatusMessage(hasErrors, persistSummary)
                console.showStatus(status)
                handler.finish(if (hasErrors) 1 else 0)
            } catch (e: Exception) {
                console.showFatalError("Artifacts generation failed: ${e.message.orEmpty()}")
                handler.finish(-1)
            }
        }

        private fun resolveAddressTargets(workDir: String?): List<AddressTarget> {
            val moduleFilters = parseAddressSelectors(addressModule)
            val validatorFilters = parseAddressSelectors(addressValidator)
            val blueprintTargets = readAddressTargetsFromBlueprint(workDir)

            if (blueprintTargets.isNotEmpty()) {
                val filtered =
                    blueprintTargets.filter { target ->
                        (moduleFilters.isEmpty() || moduleFilters.contains(target.module)) &&
                            (validatorFilters.isEmpty() || validatorFilters.contains(target.validator))
                    }

                if (moduleFilters.isNotEmpty() || validatorFilters.isNotEmpty()) {
                    if (filtered.isEmpty()) {
                        val requestedParts = ArrayList<String>(2)
                        if (moduleFilters.isNotEmpty()) {
                            requestedParts += "modules=${moduleFilters.joinToString(",")}"
                        }
                        if (validatorFilters.isNotEmpty()) {
                            requestedParts += "validators=${validatorFilters.joinToString(",")}"
                        }
                        val requested =
                            listOfNotNull(
                                requestedParts.takeIf { it.isNotEmpty() }?.joinToString(", ")
                            ).joinToString("")
                        throw ExecutionException("No validators in blueprint match: $requested")
                    }
                    return filtered
                }

                return filtered
            }

            if (moduleFilters.isNotEmpty() || validatorFilters.isNotEmpty()) {
                val modules = if (moduleFilters.isEmpty()) listOf<String?>(null) else moduleFilters
                val validators = if (validatorFilters.isEmpty()) listOf<String?>(null) else validatorFilters
                val targets = LinkedHashSet<AddressTarget>()
                for (module in modules) {
                    for (validator in validators) {
                        targets += AddressTarget(
                            module = module,
                            validator = validator,
                            blueprintTitle = null
                        )
                    }
                }
                return targets.toList()
            }

            return listOf(AddressTarget(module = null, validator = null, blueprintTitle = null))
        }

        private fun parseAddressSelectors(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            return raw
                .split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        private fun readAddressTargetsFromBlueprint(workDir: String?): List<AddressTarget> {
            val blueprintFile = resolveBlueprintFile(workDir) ?: return emptyList()
            if (!blueprintFile.exists() || !blueprintFile.isFile) return emptyList()

            val root =
                try {
                    JsonParser.parseString(blueprintFile.readText(StandardCharsets.UTF_8)).asJsonObject
                } catch (_: Exception) {
                    return emptyList()
                }

            val validators = root["validators"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            val unique = LinkedHashMap<String, AddressTarget>()
            for (element in validators) {
                val obj = element.asJsonObjectOrNull() ?: continue
                val title = obj.getString("title") ?: continue
                val (moduleName, validatorName) = parseAddressTargetFromTitle(title) ?: continue
                val key = "$moduleName::$validatorName"
                unique.putIfAbsent(
                    key,
                    AddressTarget(
                        module = moduleName,
                        validator = validatorName,
                        blueprintTitle = "$moduleName.$validatorName"
                    )
                )
            }
            return unique.values.toList()
        }

        private fun parseAddressTargetFromTitle(title: String): Pair<String, String>? {
            val firstDot = title.indexOf('.')
            if (firstDot <= 0) return null
            val secondDot = title.indexOf('.', firstDot + 1)
            val validatorEndExclusive = if (secondDot > firstDot + 1) secondDot else title.length
            if (validatorEndExclusive <= firstDot + 1) return null

            val moduleName = title.substring(0, firstDot).trim()
            val validatorName = title.substring(firstDot + 1, validatorEndExclusive).trim()
            if (moduleName.isEmpty() || validatorName.isEmpty()) return null
            return moduleName to validatorName
        }

        private fun resolveBlueprintFile(workDir: String?): File? {
            val configured = addressInput.trim().ifEmpty { "plutus.json" }
            val explicit = File(configured)
            return if (explicit.isAbsolute) explicit else {
                val base = workDir ?: project.basePath ?: return null
                File(base, configured)
            }
        }

        private fun buildAddressCommandParameters(
            target: AddressTarget,
            network: AddressNetwork
        ): List<String> {
            val parameters = ArrayList<String>()
            appendValueOption(parameters, "--in", addressInput)
            appendValueOption(parameters, "--module", target.module.orEmpty())
            appendValueOption(parameters, "--validator", target.validator.orEmpty())
            appendValueOption(parameters, "--delegated-to", addressDelegatedTo)
            if (network.isMainnet) {
                parameters += "--mainnet"
            }

            val extra = extraArgs.trim()
            if (extra.isNotEmpty()) {
                parameters += ParametersListUtil.parse(extra)
            }
            return parameters
        }

        private fun selectedAddressNetworks(): List<AddressNetwork> {
            val selected = ArrayList<AddressNetwork>(2)
            if (addressIncludeTestnetAddress) selected += AddressNetwork.TESTNET
            if (addressIncludeMainnetAddress) selected += AddressNetwork.MAINNET
            return selected
        }

        private fun buildPolicyCommandParameters(target: AddressTarget): List<String> {
            val parameters = ArrayList<String>()
            appendValueOption(parameters, "--in", addressInput)
            appendValueOption(parameters, "--module", target.module.orEmpty())
            appendValueOption(parameters, "--validator", target.validator.orEmpty())
            return parameters
        }

        private fun buildConvertCommandParameters(target: AddressTarget): List<String> {
            val parameters = ArrayList<String>()
            appendValueOption(parameters, "--module", target.module.orEmpty())
            appendValueOption(parameters, "--validator", target.validator.orEmpty())
            parameters += listOf("--to", AikenBlueprintConvertTarget.CARDANO_CLI.cliValue)
            return parameters
        }

        private fun buildBlueprintInvocation(
            executable: String,
            subcommand: String,
            args: List<String>,
            directoryArg: String?
        ): List<String> {
            val invocation = ArrayList<String>(args.size + 5)
            invocation += executable
            invocation += "blueprint"
            invocation += subcommand
            invocation += args
            directoryArg?.let { invocation += it }
            return invocation
        }

        private fun parseAddressFromOutput(raw: String): String? {
            val lines =
                stripAnsi(raw)
                    .replace("\r\n", "\n")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()

            return lines.lastOrNull { ADDRESS_OUTPUT_LINE_REGEX.matches(it) }
        }

        private fun parsePolicyFromOutput(raw: String): String? {
            val lines =
                stripAnsi(raw)
                    .replace("\r\n", "\n")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            return lines.lastOrNull { POLICY_ID_OUTPUT_LINE_REGEX.matches(it) }
        }

        private fun runBlueprintCommandCollectingOutput(
            invocation: List<String>,
            workDir: String?,
            handler: AikenAsyncProcessHandler,
            preferPty: Boolean = true
        ): CommandRunResult {
            return try {
                runCommandCollectingOutput(invocation, workDir, handler, usePty = preferPty)
            } catch (_: Exception) {
                runCommandCollectingOutput(invocation, workDir, handler, usePty = false)
            }
        }

        private fun formatCommandFailureDetails(
            commandName: String,
            exitCode: Int,
            rawOutput: String
        ): String {
            val details = stripAnsi(rawOutput).trim()
            if (details.isNotEmpty()) {
                return details
            }
            return buildString {
                append(commandName)
                append(" failed with exit code ")
                append(exitCode)
                append(".\n")
                append("Check blueprint path, module/validator filters, and whether parameters are still unapplied.")
            }
        }

        private fun buildAddressTargetDisplayName(target: AddressTarget): String {
            val module = target.module.orEmpty()
            val validator = target.validator.orEmpty()
            val combined = listOf(module, validator).filter { it.isNotBlank() }.joinToString(".")
            return combined.ifBlank { "auto-select from blueprint" }
        }

        private fun parseScriptCborFromConvertedScript(scriptJson: String): String? {
            val root =
                try {
                    JsonParser.parseString(scriptJson)
                } catch (_: Exception) {
                    return null
                }
            return findScriptCbor(root)
        }

        private fun findScriptCbor(element: JsonElement?): String? {
            if (element == null || element.isJsonNull) return null
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                return null
            }
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                listOf("cborHex", "cbor_hex", "cbor").forEach { key ->
                    val candidate = obj[key]
                    if (candidate != null && candidate.isJsonPrimitive && candidate.asJsonPrimitive.isString) {
                        val value = candidate.asString.trim()
                        if (value.isNotEmpty()) return value
                    }
                }
                for ((_, value) in obj.entrySet()) {
                    val nested = findScriptCbor(value)
                    if (!nested.isNullOrBlank()) return nested
                }
            }
            if (element.isJsonArray) {
                for (value in element.asJsonArray) {
                    val nested = findScriptCbor(value)
                    if (!nested.isNullOrBlank()) return nested
                }
            }
            return null
        }

        private fun persistArtifacts(entries: List<ArtifactsEntry>, workDir: String?): ArtifactsPersistSummary? {
            val directory = resolveArtifactsDirectory(workDir) ?: return null
            val entriesByStem = LinkedHashMap<String, List<File>>()
            var totalSaved = 0

            try {
                directory.mkdirs()

                for (entry in entries) {
                    val stem = buildArtifactStem(entry)
                    val nameContext = resolveArtifactNameContext(entry)
                    val savedFiles = ArrayList<File>(4)

                    val scriptJson = entry.scriptJson?.trim().orEmpty()
                    if (addressIncludeScriptCbor && scriptJson.isNotEmpty()) {
                        val file = resolveArtifactOutputFile(
                            directory = directory,
                            template = addressScriptTemplate,
                            defaultTemplate = DEFAULT_ARTIFACT_SCRIPT_TEMPLATE,
                            context = nameContext
                        ) ?: throw IOException("Invalid script template: $addressScriptTemplate")
                        file.parentFile?.mkdirs()
                        file.writeText(scriptJson + "\n", StandardCharsets.UTF_8)
                        savedFiles += file
                    }

                    val mainnetAddress = entry.variants.firstOrNull { it.network == AddressNetwork.MAINNET }?.address
                    val testnetAddress = entry.variants.firstOrNull { it.network == AddressNetwork.TESTNET }?.address

                    if (!mainnetAddress.isNullOrBlank()) {
                        val file = resolveArtifactOutputFile(
                            directory = directory,
                            template = addressMainnetTemplate,
                            defaultTemplate = DEFAULT_ARTIFACT_MAINNET_TEMPLATE,
                            context = nameContext
                        ) ?: throw IOException("Invalid mainnet address template: $addressMainnetTemplate")
                        file.parentFile?.mkdirs()
                        file.writeText(mainnetAddress.trim() + "\n", StandardCharsets.UTF_8)
                        savedFiles += file
                    }

                    if (!testnetAddress.isNullOrBlank()) {
                        val file = resolveArtifactOutputFile(
                            directory = directory,
                            template = addressTestnetTemplate,
                            defaultTemplate = DEFAULT_ARTIFACT_TESTNET_TEMPLATE,
                            context = nameContext
                        ) ?: throw IOException("Invalid testnet address template: $addressTestnetTemplate")
                        file.parentFile?.mkdirs()
                        file.writeText(testnetAddress.trim() + "\n", StandardCharsets.UTF_8)
                        savedFiles += file
                    }

                    if (addressGeneratePolicyId && !entry.policyId.isNullOrBlank()) {
                        val file = resolveArtifactOutputFile(
                            directory = directory,
                            template = addressPolicyTemplate,
                            defaultTemplate = DEFAULT_ARTIFACT_POLICY_TEMPLATE,
                            context = nameContext
                        ) ?: throw IOException("Invalid policy template: $addressPolicyTemplate")
                        file.parentFile?.mkdirs()
                        file.writeText(entry.policyId.trim() + "\n", StandardCharsets.UTF_8)
                        savedFiles += file
                    }

                    entriesByStem[stem] = savedFiles
                    totalSaved += savedFiles.size
                }

                return ArtifactsPersistSummary(
                    directory = directory,
                    totalFiles = totalSaved,
                    entriesByStem = entriesByStem,
                    saveError = null
                )
            } catch (e: Exception) {
                return ArtifactsPersistSummary(
                    directory = directory,
                    totalFiles = totalSaved,
                    entriesByStem = entriesByStem,
                    saveError = "Failed to save artifacts: ${e.message.orEmpty()}"
                )
            }
        }

        private fun buildArtifactsStatusMessage(hasErrors: Boolean, summary: ArtifactsPersistSummary?): String {
            val headline =
                if (hasErrors) {
                    "Artifacts generation completed with errors."
                } else {
                    "Artifacts generation completed."
                }
            if (summary == null) return headline

            val body = ArrayList<String>()
            body += headline
            for ((stem, files) in summary.entriesByStem) {
                if (files.isNotEmpty()) {
                    body += "$stem: saved ${files.size} files to ${summary.directory.absolutePath}"
                }
            }
            if (!summary.saveError.isNullOrBlank()) {
                body += summary.saveError
            }
            return body.joinToString("\n")
        }

        private fun resolveArtifactsDirectory(workDir: String?): File? {
            val raw = addressArtifactsBasePath.trim().ifEmpty { "artifacts" }
            val file = File(raw)
            if (file.isAbsolute) return file
            val base = workDir ?: resolveProjectDirectory() ?: project.basePath ?: return file
            return File(base, raw)
        }

        private fun buildArtifactStem(entry: ArtifactsEntry): String {
            val context = resolveArtifactNameContext(entry)
            return "${context.module}.${context.validator}"
        }

        private fun resolveArtifactNameContext(entry: ArtifactsEntry): ArtifactNameContext {
            val fromTitle = parseAddressTargetFromTitle(entry.blueprintTitle.orEmpty())
            val module = entry.module?.takeIf { it.isNotBlank() } ?: fromTitle?.first ?: "module"
            val validator = entry.validator?.takeIf { it.isNotBlank() } ?: fromTitle?.second ?: "validator"
            return ArtifactNameContext(
                module = sanitizeArtifactSegment(module),
                validator = sanitizeArtifactSegment(validator)
            )
        }

        private fun resolveArtifactOutputFile(
            directory: File,
            template: String,
            defaultTemplate: String,
            context: ArtifactNameContext
        ): File? {
            val effectiveTemplate = template.trim().ifEmpty { defaultTemplate }
            val rendered =
                effectiveTemplate
                    .replace("%module%", context.module)
                    .replace("%validator%", context.validator)
                    .replace('\\', '/')
                    .trim()
            if (rendered.isEmpty()) return null

            val relativePath =
                try {
                    Paths.get(rendered).normalize()
                } catch (_: Exception) {
                    return null
                }
            if (relativePath.isAbsolute) return null
            if (relativePath.toString().isBlank()) return null
            if (relativePath.startsWith("..")) return null

            return directory.toPath().resolve(relativePath).normalize().toFile()
        }
    }

    private inner class AikenArtifactsExecutionConsole : ExecutionConsole {
        private val rootPanel = JPanel(BorderLayout())
        private val statusLabel =
            JBTextArea("Preparing artifacts generation...").apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty()
                alignmentX = JComponent.LEFT_ALIGNMENT
                isOpaque = false
            }
        private val contentPanel = JPanel()
        private val contentHolder = JPanel(BorderLayout())
        private val disposed = AtomicBoolean(false)

        init {
            contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
            contentPanel.border = JBUI.Borders.empty(8)
            contentHolder.add(contentPanel, BorderLayout.NORTH)
            contentHolder.border = JBUI.Borders.empty()

            val scrollPane = ScrollPaneFactory.createScrollPane(contentHolder, true)
            scrollPane.border = JBUI.Borders.empty()

            rootPanel.border = JBUI.Borders.empty(8)
            rootPanel.add(statusLabel, BorderLayout.NORTH)
            rootPanel.add(scrollPane, BorderLayout.CENTER)
        }

        override fun getComponent(): JComponent = rootPanel

        override fun getPreferredFocusableComponent(): JComponent = rootPanel

        override fun dispose() {
            disposed.set(true)
        }

        fun showStatus(text: String) {
            updateUi {
                statusLabel.text = text
            }
        }

        fun showFatalError(message: String) {
            updateUi {
                statusLabel.text = "Artifacts generation failed."
                contentPanel.removeAll()
                contentPanel.add(createMessageBlock(message))
                refreshUi()
            }
        }

        fun showResults(entries: List<ArtifactsEntry>) {
            updateUi {
                contentPanel.removeAll()
                if (entries.isEmpty()) {
                    contentPanel.add(createMessageBlock("No artifacts were produced."))
                    refreshUi()
                    return@updateUi
                }

                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        contentPanel.add(JBUI.Panels.simplePanel().apply {
                            border = JBUI.Borders.empty(6)
                        })
                    }
                    contentPanel.add(createAddressEntryPanel(entry))
                }
                refreshUi()
            }
        }

        private fun createAddressEntryPanel(entry: ArtifactsEntry): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.border = JBUI.Borders.empty(6, 8)

            panel.add(createMetaRow("Module", entry.module ?: "auto"))
            panel.add(createMetaRow("Validator", entry.validator ?: "auto"))
            panel.add(createMetaRow("Stake key", entry.stakeKey ?: "not set"))

            val commonError = entry.commonError
            if (!commonError.isNullOrBlank()) {
                panel.add(createErrorRow("artifact generation", commonError))
                panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
                return panel
            }

            val scriptCbor = entry.scriptCbor
            val scriptError = entry.scriptError
            if (!scriptCbor.isNullOrBlank()) {
                panel.add(createCopyableValueRow("script CBOR", scriptCbor))
            } else if (!scriptError.isNullOrBlank()) {
                panel.add(createErrorRow("script CBOR", scriptError))
            }

            val policyId = entry.policyId
            val policyError = entry.policyError
            if (!policyId.isNullOrBlank()) {
                panel.add(createCopyableValueRow("policy ID", policyId))
            } else if (!policyError.isNullOrBlank()) {
                panel.add(createErrorRow("policy ID", policyError))
            }

            for (variant in entry.variants) {
                val address = variant.address
                val error = variant.error
                if (!address.isNullOrBlank()) {
                    panel.add(createCopyableValueRow("${variant.network.label} address", address))
                } else if (!error.isNullOrBlank()) {
                    panel.add(createErrorRow("${variant.network.label} address", error))
                }
            }

            val savedFiles = entry.savedFiles
            if (savedFiles.isNotEmpty()) {
                panel.add(createMetaRow("Saved", savedFiles.joinToString(", ") { it.name }))
            }

            panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
            return panel
        }

        private fun createMetaRow(label: String, value: String): JComponent {
            return JBLabel("$label: $value").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            }
        }

        private fun createCopyableValueRow(label: String, value: String): JComponent {
            val row = JPanel(BorderLayout(8, 0))
            row.alignmentX = JComponent.LEFT_ALIGNMENT
            row.border = JBUI.Borders.emptyTop(4)

            val field =
                object : EditorTextField() {
                    override fun getPreferredSize(): Dimension {
                        val size = super.getPreferredSize()
                        return Dimension(JBUI.scale(420), size.height + JBUI.scale(5))
                    }
                }.apply {
                    text = value
                    setOneLineMode(true)
                    isViewer = true
                    minimumSize = Dimension(0, JBUI.scale(31))
                }

            val copyButton = JButton(AllIcons.Actions.Copy).apply {
                toolTipText = "Copy $label"
                margin = JBUI.emptyInsets()
                isFocusable = false
                val width = JBUI.scale(33)
                val height = JBUI.scale(31)
                preferredSize = Dimension(width, height)
                minimumSize = Dimension(width, height)
                maximumSize = Dimension(width, height)
                addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(value))
                }
            }

            val labelComponent = JBLabel("$label:")
            val labelColumnWidth = JBUI.scale(130)
            labelComponent.preferredSize = Dimension(labelColumnWidth, labelComponent.preferredSize.height)
            labelComponent.minimumSize = Dimension(labelColumnWidth, labelComponent.minimumSize.height)
            labelComponent.maximumSize = Dimension(labelColumnWidth, Int.MAX_VALUE)

            row.add(labelComponent, BorderLayout.WEST)
            row.add(field, BorderLayout.CENTER)
            row.add(copyButton, BorderLayout.EAST)
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
            return row
        }

        private fun createErrorRow(label: String, value: String): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.border = JBUI.Borders.emptyTop(6)

            panel.add(JBLabel("$label: failed").apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            panel.add(
                JBTextArea(value).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    border = JBUI.Borders.empty(2, 0)
                    alignmentX = JComponent.LEFT_ALIGNMENT
                }
            )
            return panel
        }

        private fun createMessageBlock(message: String): JComponent {
            return JBTextArea(message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
        }

        private fun updateUi(block: () -> Unit) {
            if (disposed.get()) return
            ApplicationManager.getApplication().invokeLater {
                if (disposed.get()) return@invokeLater
                block()
            }
        }

        private fun refreshUi() {
            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    private fun finishOrWatch(
        handler: AikenAsyncProcessHandler,
        ideWatchEnabled: Boolean,
        restartRequested: AtomicBoolean,
        exitCode: Int,
        idleMessage: String
    ) {
        if (!ideWatchEnabled) {
            handler.finish(exitCode)
            return
        }

        handler.notifyTextAvailable(
            "\n[watch] Save a file to rerun $idleMessage.\n",
            ProcessOutputTypes.STDOUT
        )

        while (!handler.isProcessTerminated && !restartRequested.get()) {
            Thread.sleep(150)
        }

        if (!handler.isProcessTerminated && !restartRequested.get()) {
            handler.finish(exitCode)
        }
    }

    private fun installIdeIntegratedWatch(
        environment: ExecutionEnvironment,
        workDir: String?,
        handler: AikenAsyncProcessHandler,
        restartRequested: AtomicBoolean,
        restartMessage: String
    ): MessageBusConnection? {
        val rootPath = normalizeWatchRootPath(workDir) ?: return null
        val lastRestartAt = AtomicLong(0L)
        val connection = project.messageBus.connect()
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (handler.isProcessTerminated || handler.isProcessTerminating) return
                    val shouldRestart = events.any { event -> shouldTriggerWatchRestart(event, rootPath) }
                    if (!shouldRestart) return

                    val now = System.currentTimeMillis()
                    val previous = lastRestartAt.get()
                    if (now - previous < IDE_WATCH_RESTART_DEBOUNCE_MS) return
                    lastRestartAt.set(now)

                    if (!restartRequested.compareAndSet(false, true)) return

                    handler.notifyTextAvailable(
                        "\n[watch] Changes saved. Restarting Aiken $restartMessage...\n",
                        ProcessOutputTypes.STDOUT
                    )

                    ApplicationManager.getApplication().invokeLater {
                        if (handler.isProcessTerminated || handler.isProcessTerminating) return@invokeLater
                        ExecutionUtil.restart(environment)
                    }
                }
            }
        )
        return connection
    }

    private fun normalizeWatchRootPath(workDir: String?): String? {
        val root =
            when {
                !workDir.isNullOrBlank() -> File(workDir)
                !project.basePath.isNullOrBlank() -> File(project.basePath!!)
                else -> return null
            }
        val path =
            try {
                root.canonicalPath
            } catch (_: IOException) {
                root.absolutePath
            }
        return path.replace('\\', '/').trimEnd('/')
    }

    private fun shouldTriggerWatchRestart(event: VFileEvent, rootPath: String): Boolean {
        if (!event.isFromSave) return false
        val rawPath = event.path.ifBlank { return false }
        val path = rawPath.replace('\\', '/')
        val insideRoot = path == rootPath || path.startsWith("$rootPath/")
        if (!insideRoot) return false
        if (path.contains("/.git/") || path.contains("/.idea/") || path.contains("/build/")) return false
        return path.endsWith(".ak") || path.endsWith(".toml") || path.endsWith(".json")
    }

    private fun runCommandCollectingOutput(
        invocation: List<String>,
        workDir: String?,
        handler: AikenAsyncProcessHandler,
        usePty: Boolean
    ): CommandRunResult {
        val process =
            if (usePty) {
                PtyProcessBuilder(invocation.toTypedArray())
                    .setDirectory(workDir ?: project.basePath)
                    .setEnvironment(HashMap(System.getenv()))
                    .setRedirectErrorStream(true)
                    .setWindowsAnsiColorEnabled(true)
                    .start()
            } else {
                val processBuilder = ProcessBuilder(invocation)
                if (!workDir.isNullOrBlank()) {
                    processBuilder.directory(File(workDir))
                }
                processBuilder.redirectErrorStream(true)
                processBuilder.start()
            }

        handler.attachProcess(process)
        val output =
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        val exitCode = process.waitFor()
        return CommandRunResult(output = output, exitCode = exitCode)
    }

    private fun ensureTrailingNewline(text: String): String {
        return if (text.endsWith('\n')) text else "$text\n"
    }

    private inner class AikenRerunSelectedTestAction(
        container: ComponentContainer,
        private val configuration: AikenRunConfiguration
    ) : AbstractRerunFailedTestsAction(container) {
        init {
            templatePresentation.text = "Rerun selected Aiken test"
            templatePresentation.description = "Rerun the selected Aiken test with `aiken check -m`."
        }

        override fun isActive(e: AnActionEvent): Boolean {
            return collectSelectedLeafTests().isNotEmpty()
        }

        override fun getFilter(project: Project, scope: GlobalSearchScope): Filter<AbstractTestProxy> {
            val selected = collectSelectedLeafTests()
            if (selected.isEmpty()) {
                return super.getFilter(project, scope)
            }
            val selectedSet = selected.toHashSet()
            return object : Filter<AbstractTestProxy>() {
                override fun shouldAccept(test: AbstractTestProxy): Boolean {
                    var current: AbstractTestProxy? = test
                    while (current != null) {
                        if (selectedSet.contains(current)) {
                            return true
                        }
                        current = current.parent
                    }
                    return false
                }
            }
        }

        override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
            val selectedLeaf = collectSelectedLeafTests().firstOrNull() ?: return null
            val testName = selectedLeaf.name.trim()
            if (testName.isEmpty()) return null

            return object : MyRunProfile(configuration) {
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
                    val rerunConfig = configuration.clone() as? AikenRunConfiguration ?: return null
                    rerunConfig.command = AikenRunCommand.CHECK
                    rerunConfig.watch = false
                    rerunConfig.checkSkipTests = false
                    rerunConfig.checkExactMatch = true
                    rerunConfig.checkMatchTests = testName
                    return rerunConfig.getState(executor, environment)
                }
            }
        }

        private fun collectSelectedLeafTests(): List<AbstractTestProxy> {
            val model = model ?: return emptyList()
            val tree = model.treeView
            val paths = tree.selectionPaths ?: return emptyList()

            val collected = LinkedHashSet<AbstractTestProxy>()
            for (path in paths) {
                val selected = tree.getSelectedTest(path) ?: continue
                if (selected.isLeaf) {
                    collected += selected
                    continue
                }
                for (candidate in selected.getAllTests()) {
                    if (candidate.isLeaf) {
                        collected += candidate
                    }
                }
            }
            return collected.toList()
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        command = readEnum(element, "command", AikenRunCommand.CHECK)
        projectDirectory = readString(element, "projectDirectory", projectDirectory)
        aikenBinaryPath = readString(element, "aikenBinaryPath", aikenBinaryPath)
        extraArgs = readString(element, "extraArgs", extraArgs)

        denyWarnings = readBoolean(element, "denyWarnings", false)
        silentWarnings = readBoolean(element, "silentWarnings", false)
        watch = readBoolean(element, "watch", false)

        buildUplc = readBoolean(element, "buildUplc", false)
        buildEnv = readString(element, "buildEnv", buildEnv)
        buildOut = readString(element, "buildOut", buildOut)
        buildTraceFilter = readEnum(element, "buildTraceFilter", AikenTraceFilter.ALL)
        buildTraceLevel = readEnum(element, "buildTraceLevel", AikenTraceLevel.SILENT)
        buildOutputMode = readEnum(element, "buildOutputMode", AikenBuildOutputMode.IDE_INTEGRATED)

        addressInput = readString(element, "addressInput", addressInput)
        addressModule = readString(element, "addressModule", addressModule)
        addressValidator = readString(element, "addressValidator", addressValidator)
        addressDelegatedTo = readString(element, "addressDelegatedTo", addressDelegatedTo)
        addressGeneratePolicyId = readBoolean(element, "addressGeneratePolicyId", true)
        addressIncludeScriptCbor = readBoolean(element, "addressIncludeScriptCbor", true)
        addressIncludeTestnetAddress = readBoolean(element, "addressIncludeTestnetAddress", true)
        addressIncludeMainnetAddress = readBoolean(element, "addressIncludeMainnetAddress", true)
        addressScriptTemplate =
            readString(element, "addressScriptTemplate", DEFAULT_ARTIFACT_SCRIPT_TEMPLATE)
        addressMainnetTemplate =
            readString(element, "addressMainnetTemplate", DEFAULT_ARTIFACT_MAINNET_TEMPLATE)
        addressTestnetTemplate =
            readString(element, "addressTestnetTemplate", DEFAULT_ARTIFACT_TESTNET_TEMPLATE)
        addressPolicyTemplate =
            readString(element, "addressPolicyTemplate", DEFAULT_ARTIFACT_POLICY_TEMPLATE)
        addressArtifactsBasePath = readString(element, "addressArtifactsBasePath", addressArtifactsBasePath)
        cleanTargetPath = readString(element, "cleanTargetPath", cleanTargetPath)

        applyInput = readString(element, "applyInput", applyInput)
        applyOut = readString(element, "applyOut", applyOut)
        applyModule = readString(element, "applyModule", applyModule)
        applyValidator = readString(element, "applyValidator", applyValidator)
        applyDefaultCborParameters = readString(element, "applyDefaultCborParameters", applyDefaultCborParameters)
        applyAutoUntilNoParameters = readBoolean(element, "applyAutoUntilNoParameters", true)

        convertModule = readString(element, "convertModule", convertModule)
        convertValidator = readString(element, "convertValidator", convertValidator)
        convertTo = readEnum(element, "convertTo", AikenBlueprintConvertTarget.CARDANO_CLI)
        convertTerminalOutputFile =
            readString(element, "convertTerminalOutputFile", convertTerminalOutputFile)

        checkSkipTests = readBoolean(element, "checkSkipTests", false)
        checkOutputMode = readEnum(element, "checkOutputMode", AikenCheckOutputMode.IDE_INTEGRATED)
        checkDebug = readBoolean(element, "checkDebug", false)
        checkSeed = readString(element, "checkSeed", checkSeed)
        checkMaxSuccess = readString(element, "checkMaxSuccess", checkMaxSuccess)
        checkPropertyCoverage =
            readEnum(element, "checkPropertyCoverage", AikenPropertyCoverage.RELATIVE_TO_LABELS)
        checkMatchTests = readString(element, "checkMatchTests", checkMatchTests)
        checkExactMatch = readBoolean(element, "checkExactMatch", false)
        checkEnv = readString(element, "checkEnv", checkEnv)
        checkTraceFilter = readEnum(element, "checkTraceFilter", AikenTraceFilter.ALL)
        checkTraceLevel = readEnum(element, "checkTraceLevel", AikenTraceLevel.VERBOSE)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        writeField(element, "command", command.name)
        writeField(element, "projectDirectory", projectDirectory)
        writeField(element, "aikenBinaryPath", aikenBinaryPath)
        writeField(element, "extraArgs", extraArgs)

        writeField(element, "denyWarnings", denyWarnings.toString())
        writeField(element, "silentWarnings", silentWarnings.toString())
        writeField(element, "watch", watch.toString())

        writeField(element, "buildUplc", buildUplc.toString())
        writeField(element, "buildEnv", buildEnv)
        writeField(element, "buildOut", buildOut)
        writeField(element, "buildTraceFilter", buildTraceFilter.name)
        writeField(element, "buildTraceLevel", buildTraceLevel.name)
        writeField(element, "buildOutputMode", buildOutputMode.name)

        writeField(element, "addressInput", addressInput)
        writeField(element, "addressModule", addressModule)
        writeField(element, "addressValidator", addressValidator)
        writeField(element, "addressDelegatedTo", addressDelegatedTo)
        writeField(element, "addressGeneratePolicyId", addressGeneratePolicyId.toString())
        writeField(element, "addressIncludeScriptCbor", addressIncludeScriptCbor.toString())
        writeField(element, "addressIncludeTestnetAddress", addressIncludeTestnetAddress.toString())
        writeField(element, "addressIncludeMainnetAddress", addressIncludeMainnetAddress.toString())
        writeField(element, "addressScriptTemplate", addressScriptTemplate)
        writeField(element, "addressMainnetTemplate", addressMainnetTemplate)
        writeField(element, "addressTestnetTemplate", addressTestnetTemplate)
        writeField(element, "addressPolicyTemplate", addressPolicyTemplate)
        writeField(element, "addressArtifactsBasePath", addressArtifactsBasePath)
        writeField(element, "cleanTargetPath", cleanTargetPath)

        writeField(element, "applyInput", applyInput)
        writeField(element, "applyOut", applyOut)
        writeField(element, "applyModule", applyModule)
        writeField(element, "applyValidator", applyValidator)
        writeField(element, "applyDefaultCborParameters", applyDefaultCborParameters)
        writeField(element, "applyAutoUntilNoParameters", applyAutoUntilNoParameters.toString())

        writeField(element, "convertModule", convertModule)
        writeField(element, "convertValidator", convertValidator)
        writeField(element, "convertTo", convertTo.name)
        writeField(element, "convertTerminalOutputFile", convertTerminalOutputFile)

        writeField(element, "checkSkipTests", checkSkipTests.toString())
        writeField(element, "checkOutputMode", checkOutputMode.name)
        writeField(element, "checkDebug", checkDebug.toString())
        writeField(element, "checkSeed", checkSeed)
        writeField(element, "checkMaxSuccess", checkMaxSuccess)
        writeField(element, "checkPropertyCoverage", checkPropertyCoverage.name)
        writeField(element, "checkMatchTests", checkMatchTests)
        writeField(element, "checkExactMatch", checkExactMatch.toString())
        writeField(element, "checkEnv", checkEnv)
        writeField(element, "checkTraceFilter", checkTraceFilter.name)
        writeField(element, "checkTraceLevel", checkTraceLevel.name)
    }

    private fun resolveAikenExecutable(): String {
        return aikenBinaryPath.trim().ifEmpty { "aiken" }
    }

    private fun resolveProjectDirectory(): String? {
        val configured = projectDirectory.trim()
        if (configured.isNotEmpty()) return configured
        return project.basePath?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildCommandParameters(forceSkipTests: Boolean = false): List<String> {
        val parameters = ArrayList<String>()

        when (command) {
            AikenRunCommand.BUILD -> {
                if (denyWarnings) parameters += "--deny"
                if (silentWarnings) parameters += "--silent"
                if (watch && buildOutputMode != AikenBuildOutputMode.IDE_INTEGRATED) parameters += "--watch"
                if (buildUplc) parameters += "--uplc"
                appendValueOption(parameters, "--env", buildEnv)
                appendValueOption(parameters, "--out", buildOut)
                parameters += listOf("--trace-filter", buildTraceFilter.cliValue)
                parameters += listOf("--trace-level", buildTraceLevel.cliValue)
            }

            AikenRunCommand.ADDRESS -> {
                appendValueOption(parameters, "--in", addressInput)
                appendValueOption(parameters, "--module", addressModule)
                appendValueOption(parameters, "--validator", addressValidator)
                appendValueOption(parameters, "--delegated-to", addressDelegatedTo)
            }

            AikenRunCommand.CLEAN -> {
                // Custom IDE-integrated cleaner does not call Aiken CLI.
            }

            AikenRunCommand.APPLY -> {
                val cbor = configuredApplyCborParameters().firstOrNull()
                parameters += buildApplyCommandParameters(cbor, includeExtraArgs = false)
            }

            AikenRunCommand.CONVERT -> {
                appendValueOption(parameters, "--module", convertModule)
                appendValueOption(parameters, "--validator", convertValidator)
                parameters += listOf("--to", convertTo.cliValue)
            }

            AikenRunCommand.CHECK -> {
                if (denyWarnings) parameters += "--deny"
                if (silentWarnings) parameters += "-S"
                if (watch && checkOutputMode != AikenCheckOutputMode.IDE_INTEGRATED) parameters += "--watch"
                if (checkSkipTests || forceSkipTests) parameters += "--skip-tests"
                if (checkDebug) parameters += "--debug"
                appendValueOption(parameters, "--seed", checkSeed)
                appendValueOption(parameters, "--max-success", checkMaxSuccess)
                parameters += listOf("--property-coverage", checkPropertyCoverage.cliValue)
                appendValueOption(parameters, "--match-tests", normalizeMatchTestsPattern(checkMatchTests))
                if (checkExactMatch) parameters += "--exact-match"
                appendValueOption(parameters, "--env", checkEnv)
                parameters += listOf("--trace-filter", checkTraceFilter.cliValue)
                parameters += listOf("--trace-level", checkTraceLevel.cliValue)
            }
        }

        val extra = extraArgs.trim()
        if (extra.isNotEmpty()) {
            parameters += ParametersListUtil.parse(extra)
        }
        return parameters
    }

    private fun normalizeMatchTestsPattern(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val parts =
            trimmed
                .split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        return when (parts.size) {
            0 -> ""
            1 -> parts.first()
            else -> parts.joinToString(",")
        }
    }

    private fun configuredApplyCborParameters(): List<String> {
        return parseCborValues(applyDefaultCborParameters)
    }

    private fun parseCborValues(raw: String): List<String> {
        return raw
            .trim()
            .split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun buildApplyCommandParameters(
        singleCborParameter: String?,
        includeExtraArgs: Boolean = true
    ): List<String> {
        val parameters = ArrayList<String>()
        appendValueOption(parameters, "--in", absolutizeApplyPath(applyInput))
        appendValueOption(parameters, "--out", absolutizeApplyPath(applyOut))
        appendValueOption(parameters, "--module", applyModule)
        appendValueOption(parameters, "--validator", applyValidator)
        singleCborParameter?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters += it }

        if (includeExtraArgs) {
            val extra = extraArgs.trim()
            if (extra.isNotEmpty()) {
                parameters += ParametersListUtil.parse(extra)
            }
        }
        return parameters
    }

    private fun formatAppliedCborParametersLine(parameters: List<String>): String {
        return "Applied CBOR parameters: ${parameters.joinToString(" ")}"
    }

    private fun extractLastAppliedCborFromApplyOutput(rawOutput: String): String? {
        val lines = stripAnsi(rawOutput).replace("\r\n", "\n").replace('\r', '\n').lines()
        var index = 0
        var lastApplied: String? = null

        while (index < lines.size) {
            val line = lines[index]
            val applyingMatch = APPLYING_LINE_REGEX.find(line)
            if (applyingMatch == null) {
                index += 1
                continue
            }

            val chunks = ArrayList<String>()
            val firstChunk = applyingMatch.groupValues.getOrNull(1).orEmpty().trim()
            if (firstChunk.isNotEmpty() && APPLY_HEX_CHUNK_REGEX.matches(firstChunk)) {
                chunks += firstChunk
            }

            var cursor = index + 1
            while (cursor < lines.size) {
                val raw = lines[cursor]
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) break
                val isContinuation =
                    raw.startsWith(" ") &&
                        trimmed.split(Regex("\\s+")).all { APPLY_HEX_CHUNK_REGEX.matches(it) }
                if (!isContinuation) break

                chunks += trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
                cursor += 1
            }

            if (chunks.isNotEmpty()) {
                lastApplied = chunks.joinToString("")
            }
            index = cursor
        }

        return lastApplied
    }

    private fun applyAutoSessionKey(inspectionFile: File?, workDir: String?): String {
        val blueprintPath =
            when {
                inspectionFile != null -> {
                    try {
                        inspectionFile.canonicalPath
                    } catch (_: IOException) {
                        inspectionFile.absolutePath
                    }
                }
                !workDir.isNullOrBlank() -> workDir
                else -> project.basePath.orEmpty()
            }
        return buildString {
            append(project.locationHash)
            append("::")
            append(name)
            append("::")
            append(blueprintPath)
            append("::")
            append(applyModule.trim())
            append("::")
            append(applyValidator.trim())
        }
    }

    private fun buildInvocation(
        executable: String,
        args: List<String>,
        directoryArg: String?
    ): List<String> {
        val commandTokens = commandInvocationTokens()
        val invocation = ArrayList<String>(args.size + commandTokens.size + 2)
        invocation += executable
        invocation += commandTokens
        invocation += args
        if (command != AikenRunCommand.APPLY) {
            directoryArg?.let { invocation += it }
        }
        return invocation
    }

    private fun commandInvocationTokens(): List<String> =
        when (command) {
            AikenRunCommand.APPLY -> listOf("blueprint", "apply")
            AikenRunCommand.CONVERT -> listOf("blueprint", "convert")
            else -> listOf(command.cliValue)
        }

    private fun absolutizeApplyPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return trimmed

        val path = File(trimmed)
        if (path.isAbsolute) return trimmed

        val baseDir = resolveProjectDirectory() ?: return trimmed
        val resolved = File(baseDir, trimmed)
        return try {
            resolved.canonicalPath
        } catch (_: IOException) {
            resolved.absolutePath
        }
    }

    private data class ParsedCheckReport(
        val modules: List<ParsedModule>,
        val jsonStart: Int,
        val jsonEndExclusive: Int
    )

    private data class ParsedModule(
        val name: String,
        val tests: List<ParsedTest>
    )

    private data class ParsedTest(
        val title: String,
        val kind: String?,
        val passed: Boolean,
        val details: String?,
        val traces: List<String>,
        val memUnits: Long?,
        val cpuUnits: Long?,
        val locationHint: String?
    )

    private fun parseCheckReport(rawOutput: String, workDir: String?): ParsedCheckReport? {
        val extracted = extractJsonPayload(rawOutput) ?: return null
        val payload = resolveCheckPayload(extracted.root)
        val modulesArray = payload["modules"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        val modules = ArrayList<ParsedModule>()
        for (moduleElement in modulesArray) {
            val moduleObject = moduleElement.asJsonObjectOrNull() ?: continue
            val moduleName = moduleObject.getString("name") ?: "<module>"
            val tests = ArrayList<ParsedTest>()
            for (testObject in extractTestObjects(moduleObject)) {
                val title = testObject.getString("title") ?: "<test>"
                val kind = testObject.getString("kind")
                val passed = testObject.getString("status")?.equals("pass", ignoreCase = true) == true
                val traces = testObject.getStringArray("traces")
                val executionUnits = testObject["execution_units"]?.asJsonObjectOrNull()
                val details = buildFailureDetails(testObject, kind, traces)
                tests += ParsedTest(
                    title = title,
                    kind = kind,
                    passed = passed,
                    details = details,
                    traces = traces,
                    memUnits = executionUnits?.getLong("mem"),
                    cpuUnits = executionUnits?.getLong("cpu"),
                    locationHint = null
                )
            }
            modules += ParsedModule(moduleName, tests)
        }

        val withLocations = attachTestLocations(modules, workDir)
        return ParsedCheckReport(
            modules = withLocations,
            jsonStart = extracted.start,
            jsonEndExclusive = extracted.endExclusive
        )
    }

    private fun extractTestObjects(moduleObject: JsonObject): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        appendTestsFromContainer(result, moduleObject["tests"])
        appendTestsFromContainer(result, moduleObject["test"])
        return result
    }

    private fun appendTestsFromContainer(target: MutableList<JsonObject>, container: JsonElement?) {
        when {
            container == null || container.isJsonNull -> return
            container.isJsonArray -> {
                for (element in container.asJsonArray) {
                    element.asJsonObjectOrNull()?.let { target += it }
                }
            }
            container.isJsonObject -> {
                val obj = container.asJsonObject
                if (looksLikeTestObject(obj)) {
                    target += obj
                    return
                }
                for ((name, value) in obj.entrySet()) {
                    val valueObject = value.asJsonObjectOrNull() ?: continue
                    if (!looksLikeTestObject(valueObject)) continue
                    if (valueObject.getString("title") != null) {
                        target += valueObject
                    } else {
                        val copy = valueObject.deepCopy()
                        copy.addProperty("title", name)
                        target += copy
                    }
                }
            }
        }
    }

    private fun looksLikeTestObject(obj: JsonObject): Boolean {
        if (obj.getString("title") != null) return true
        if (obj.getString("status") != null) return true
        if (obj.getString("on_failure") != null) return true
        if (obj["execution_units"]?.isJsonObject == true) return true
        if (obj["iterations"]?.isJsonPrimitive == true) return true
        return false
    }

    private fun resolveCheckPayload(root: JsonObject): JsonObject {
        val commandCheck = root["command[check]"]
        return if (commandCheck != null && commandCheck.isJsonObject) commandCheck.asJsonObject else root
    }

    private data class ExtractedJsonPayload(
        val root: JsonObject,
        val start: Int,
        val endExclusive: Int
    )

    private fun extractJsonPayload(rawOutput: String): ExtractedJsonPayload? {
        var best: ExtractedJsonPayload? = null
        var start = rawOutput.indexOf('{')
        while (start >= 0) {
            val endInclusive = findJsonObjectEnd(rawOutput, start)
            if (endInclusive != null) {
                val candidate = rawOutput.substring(start, endInclusive + 1)
                try {
                    val parsed = JsonParser.parseString(candidate)
                    if (parsed.isJsonObject) {
                        val root = parsed.asJsonObject
                        val payload = resolveCheckPayload(root)
                        if (payload["modules"]?.isJsonArray == true) {
                            best =
                                ExtractedJsonPayload(
                                    root = root,
                                    start = start,
                                    endExclusive = endInclusive + 1
                                )
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed candidate and continue searching.
                }
            }
            start = rawOutput.indexOf('{', start + 1)
        }
        return best
    }

    private fun findJsonObjectEnd(text: String, start: Int): Int? {
        if (start !in text.indices || text[start] != '{') return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return i
                    }
                    if (depth < 0) {
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun buildFailureDetails(test: JsonObject, kind: String?, traces: List<String>): String? {
        val details = ArrayList<String>()

        if (!kind.isNullOrBlank()) {
            details += "kind: $kind"
        }

        val iterations = test.getInt("iterations")
        if (iterations != null) {
            details += "iterations: $iterations"
        }

        val labels = test["labels"]?.asJsonObjectOrNull()
        if (labels != null && labels.entrySet().isNotEmpty()) {
            val renderedLabels =
                labels.entrySet().joinToString(", ") { (name, count) ->
                    val renderedCount =
                        if (count.isJsonPrimitive && count.asJsonPrimitive.isNumber) count.asInt.toString()
                        else count.toString()
                    "$name=$renderedCount"
                }
            details += "labels: $renderedLabels"
        }

        val assertion = test.getString("assertion")
        if (!assertion.isNullOrBlank()) {
            details += "assertion: $assertion"
        }

        val counterexample = test["counterexample"]
        if (counterexample != null && !counterexample.isJsonNull) {
            val rendered =
                when {
                    counterexample.isJsonPrimitive -> counterexample.asJsonPrimitive.toString().trim('"')
                    else -> counterexample.toString()
                }
            if (rendered.isNotBlank()) {
                details += "counterexample: $rendered"
            }
        }

        val onFailure = test.getString("on_failure")
        if (!onFailure.isNullOrBlank()) {
            details += "on_failure: $onFailure"
        }

        if (traces.isNotEmpty()) {
            details += "traces:\n" + traces.joinToString("\n")
        }

        if (details.isEmpty()) return null
        return details.joinToString("\n")
    }

    private fun emitTeamCityEvents(
        report: ParsedCheckReport,
        diagnostics: DiagnosticsSections,
        workDir: String?,
        handler: ProcessHandler
    ) {
        val rootId = "aiken-root"
        val rootLeafCount =
            report.modules.sumOf { it.tests.size } +
                diagnostics.warnings.size +
                diagnostics.errors.size
        val rootDisplayName = withLeafCount("aiken check", rootLeafCount)
        val allTests =
            report.modules.sumOf { it.tests.size } +
                diagnostics.errors.size
        emitServiceMessage(
            handler,
            "testSuiteStarted",
            linkedMapOf(
                "name" to rootDisplayName,
                "nodeId" to rootId,
                "parentNodeId" to TreeNodeEvent.ROOT_NODE_ID
            )
        )
        emitServiceMessage(handler, "testCount", linkedMapOf("count" to allTests.toString()))

        emitDiagnosticsTreeSection(
            handler = handler,
            rootId = rootId,
            sectionName = "warnings",
            sectionId = "diag-warnings",
            entries = diagnostics.warnings,
            workDir = workDir,
            asError = false,
            asTestCases = false
        )
        emitDiagnosticsTreeSection(
            handler = handler,
            rootId = rootId,
            sectionName = "errors",
            sectionId = "diag-errors",
            entries = diagnostics.errors,
            workDir = workDir,
            asError = true,
            asTestCases = false
        )

        val testsLeafCount = report.modules.sumOf { it.tests.size }
        val testsRootId = "tests-root"
        val testsRootDisplayName = withLeafCount("tests", testsLeafCount)
        emitServiceMessage(
            handler,
            "testSuiteStarted",
            linkedMapOf(
                "name" to testsRootDisplayName,
                "nodeId" to testsRootId,
                "parentNodeId" to rootId
            )
        )

        for ((moduleIndex, module) in report.modules.withIndex()) {
            val moduleId = "module-$moduleIndex"
            val moduleDisplayName = withLeafCount(moduleDisplayName(module.name), module.tests.size)
            emitServiceMessage(
                handler,
                "testSuiteStarted",
                linkedMapOf(
                    "name" to moduleDisplayName,
                    "nodeId" to moduleId,
                    "parentNodeId" to testsRootId
                )
            )
            for ((testIndex, test) in module.tests.withIndex()) {
                val testId = "test-$moduleIndex-$testIndex"
                val startedAttributes =
                    linkedMapOf(
                        "name" to test.title,
                        "nodeId" to testId,
                        "parentNodeId" to moduleId
                    )
                if (!test.locationHint.isNullOrBlank()) {
                    startedAttributes["locationHint"] = test.locationHint
                }
                emitServiceMessage(handler, "testStarted", startedAttributes)
                val stdOut = buildTestLeafStdOut(test)
                if (!stdOut.isNullOrBlank()) {
                    emitServiceMessage(
                        handler,
                        "testStdOut",
                        linkedMapOf(
                            "name" to test.title,
                            "nodeId" to testId,
                            "out" to stdOut
                        )
                    )
                }
                if (!test.passed) {
                    emitServiceMessage(
                        handler,
                        "testFailed",
                        linkedMapOf(
                            "name" to test.title,
                            "nodeId" to testId,
                            "message" to "Aiken test failed",
                            "details" to (test.details ?: "")
                        )
                    )
                }
                emitServiceMessage(
                    handler,
                    "testFinished",
                    linkedMapOf(
                        "name" to test.title,
                        "nodeId" to testId
                    )
                )
            }
            emitServiceMessage(
                handler,
                "testSuiteFinished",
                linkedMapOf(
                    "name" to moduleDisplayName,
                    "nodeId" to moduleId
                )
            )
        }

        emitServiceMessage(
            handler,
            "testSuiteFinished",
            linkedMapOf(
                "name" to testsRootDisplayName,
                "nodeId" to testsRootId
            )
        )

        emitServiceMessage(
            handler,
            "testSuiteFinished",
            linkedMapOf(
                "name" to rootDisplayName,
                "nodeId" to rootId
            )
        )
    }

    private fun emitDiagnosticsOnlyTeamCity(
        diagnostics: DiagnosticsSections,
        workDir: String?,
        handler: ProcessHandler
    ) {
        val rootId = "aiken-root"
        val rootLeafCount = diagnostics.warnings.size + diagnostics.errors.size
        val rootDisplayName = withLeafCount("aiken check", rootLeafCount)
        val allTests = diagnostics.errors.size
        emitServiceMessage(
            handler,
            "testSuiteStarted",
            linkedMapOf(
                "name" to rootDisplayName,
                "nodeId" to rootId,
                "parentNodeId" to TreeNodeEvent.ROOT_NODE_ID
            )
        )
        emitServiceMessage(handler, "testCount", linkedMapOf("count" to allTests.toString()))

        emitDiagnosticsTreeSection(
            handler = handler,
            rootId = rootId,
            sectionName = "warnings",
            sectionId = "diag-warnings",
            entries = diagnostics.warnings,
            workDir = workDir,
            asError = false,
            asTestCases = false
        )
        emitDiagnosticsTreeSection(
            handler = handler,
            rootId = rootId,
            sectionName = "errors",
            sectionId = "diag-errors",
            entries = diagnostics.errors,
            workDir = workDir,
            asError = true,
            asTestCases = false
        )

        emitServiceMessage(
            handler,
            "testSuiteFinished",
            linkedMapOf(
                "name" to rootDisplayName,
                "nodeId" to rootId
            )
        )
    }

    private fun emitDiagnosticsTreeSection(
        handler: ProcessHandler,
        rootId: String,
        sectionName: String,
        sectionId: String,
        entries: List<String>,
        workDir: String?,
        asError: Boolean,
        asTestCases: Boolean
    ) {
        if (entries.isEmpty()) return
        val sectionDisplayName = withLeafCount(sectionName, entries.size)

        emitServiceMessage(
            handler,
            "testSuiteStarted",
            linkedMapOf(
                "name" to sectionDisplayName,
                "nodeId" to sectionId,
                "parentNodeId" to rootId
            )
        )

        for ((index, block) in entries.withIndex()) {
            val diagnosticId = "$sectionId-$index"
            val title =
                if (asError) {
                    "[error ${index + 1}]"
                } else {
                    buildDiagnosticNodeTitle(sectionName, index, block)
                }
            if (asTestCases) {
                val startedAttributes =
                    linkedMapOf(
                        "name" to title,
                        "nodeId" to diagnosticId,
                        "parentNodeId" to sectionId
                    )
                extractDiagnosticLocationHint(block, workDir)?.let { locationHint ->
                    startedAttributes["locationHint"] = locationHint
                }

                emitServiceMessage(handler, "testStarted", startedAttributes)
                if (!asError) {
                    emitServiceMessage(
                        handler,
                        "testStdOut",
                        linkedMapOf(
                            "name" to title,
                            "nodeId" to diagnosticId,
                            "out" to (if (block.endsWith('\n')) block else "$block\n")
                        )
                    )
                }

                if (asError) {
                    emitServiceMessage(
                        handler,
                        "testFailed",
                        linkedMapOf(
                            "name" to title,
                            "nodeId" to diagnosticId,
                            "message" to "Aiken error",
                            "details" to block
                        )
                    )
                } else {
                    emitServiceMessage(
                        handler,
                        "testIgnored",
                        linkedMapOf(
                            "name" to title,
                            "nodeId" to diagnosticId,
                            "message" to "Aiken warning"
                        )
                    )
                }

                emitServiceMessage(
                    handler,
                    "testFinished",
                    linkedMapOf(
                        "name" to title,
                        "nodeId" to diagnosticId
                    )
                )
            } else {
                val warningSuiteId = "$diagnosticId-suite"
                emitServiceMessage(
                    handler,
                    "testSuiteStarted",
                    linkedMapOf(
                        "name" to title,
                        "nodeId" to warningSuiteId,
                        "parentNodeId" to sectionId
                    )
                )
                emitServiceMessage(
                    handler,
                    "testStdOut",
                    linkedMapOf(
                        "name" to title,
                        "nodeId" to warningSuiteId,
                        "out" to (if (block.endsWith('\n')) block else "$block\n")
                    )
                )
                emitServiceMessage(
                    handler,
                    "testSuiteFinished",
                    linkedMapOf(
                        "name" to title,
                        "nodeId" to warningSuiteId
                    )
                )
            }
        }

        emitServiceMessage(
            handler,
            "testSuiteFinished",
            linkedMapOf(
                "name" to sectionDisplayName,
                "nodeId" to sectionId
            )
        )
    }

    private fun SMTestProxy.isWarningDiagnosticNode(): Boolean {
        val currentName = name.trim()
        if (currentName.startsWith("warnings", ignoreCase = true)) return true
        if (currentName.startsWith("[warning ", ignoreCase = true)) return true

        val parentName = parent?.name?.trim().orEmpty()
        if (parentName.startsWith("warnings", ignoreCase = true)) return true
        if (parentName.startsWith("[warning ", ignoreCase = true)) return true

        return false
    }

    private fun SMTestProxy.isErrorDiagnosticNode(): Boolean {
        val currentName = name.trim()
        if (currentName.startsWith("errors", ignoreCase = true)) return true
        if (currentName.startsWith("[error ", ignoreCase = true)) return true

        val parentName = parent?.name?.trim().orEmpty()
        if (parentName.startsWith("errors", ignoreCase = true)) return true
        if (parentName.startsWith("[error ", ignoreCase = true)) return true

        return false
    }

    private fun withLeafCount(name: String, leafCount: Int): String = "$name ($leafCount)"

    private fun moduleDisplayName(moduleName: String): String {
        val normalized = moduleName.trim().replace('\\', '/')
        return normalized.removePrefix("tests/").ifBlank { moduleName }
    }

    private fun buildDiagnosticNodeTitle(sectionName: String, index: Int, block: String): String {
        val firstMeaningfulLine = extractDiagnosticSummaryLine(block)

        val base = if (firstMeaningfulLine.isNotEmpty()) firstMeaningfulLine else sectionName
        return "[${sectionName.dropLast(1)} ${index + 1}] ${shrinkMiddle(base, 120)}"
    }

    private fun extractDiagnosticSummaryLine(block: String): String {
        val candidates =
            stripAnsi(block)
                .lineSequence()
                .map { line ->
                    line.trim()
                        .replace(DIAGNOSTIC_LEADING_SYMBOLS_REGEX, "")
                        .replace(DIAGNOSTIC_PREFIX_REGEX, "")
                        .trim()
                }
                .filter { it.isNotEmpty() }
                .toList()

        return candidates.firstOrNull { line ->
            val lowered = line.lowercase()
            if (NON_DIAGNOSTIC_PROGRESS_REGEX.containsMatchIn(lowered)) return@firstOrNull false
            if (SUMMARY_ERRORS_WARNINGS_REGEX.containsMatchIn(lowered)) return@firstOrNull false
            if (DIAGNOSTIC_ERROR_TYPE_LINE_REGEX.matches(line)) return@firstOrNull false
            if (DIAGNOSTIC_GENERIC_WHILE_LINE_REGEX.matches(line)) return@firstOrNull false
            if (DIAGNOSTIC_LOCATION_BRACKET_LINE_REGEX.matches(line)) return@firstOrNull false
            if (line.startsWith("help:", ignoreCase = true)) return@firstOrNull false
            true
        }.orEmpty()
    }

    private fun extractDiagnosticLocationHint(block: String, workDir: String?): String? {
        val match = DIAGNOSTIC_LOCATION_REGEX.find(stripAnsi(block)) ?: return null
        val rawPath = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val line = match.groupValues.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: return null
        if (rawPath.isEmpty()) return null

        val file = File(rawPath).let { candidate ->
            if (candidate.isAbsolute || workDir.isNullOrBlank()) candidate else File(workDir, rawPath)
        }
        val absolutePath =
            try {
                file.canonicalPath
            } catch (_: IOException) {
                file.absolutePath
            }

        return TestSourceLocation(absolutePath, line).toLocationHint()
    }

    private fun shrinkMiddle(text: String, maxLength: Int): String {
        if (maxLength <= 0 || text.length <= maxLength) return text
        if (maxLength <= 3) return text.take(maxLength)

        val keep = maxLength - 1
        val left = keep / 2
        val right = keep - left
        return text.take(left) + "..." + text.takeLast(right)
    }

    private data class DiagnosticsSections(
        val warnings: List<String>,
        val errors: List<String>
    )

    private data class CommandRunResult(
        val output: String,
        val exitCode: Int
    )

    private fun diagnosticsFromText(text: String): DiagnosticsSections {
        if (text.isBlank()) {
            return DiagnosticsSections(
                warnings = emptyList(),
                errors = emptyList()
            )
        }
        val split = splitDiagnosticBlocks(text)
        return DiagnosticsSections(
            warnings = split.warnings,
            errors = split.errors
        )
    }

    private fun diagnosticsForFailedRun(
        rawOutput: String,
        treatWarningsAsErrors: Boolean = false
    ): DiagnosticsSections {
        val fullText =
            if (rawOutput.isNotBlank()) {
                rawOutput
            } else {
                "Aiken check failed without detailed diagnostics."
            }

        val split = splitDiagnosticBlocks(fullText)
        if (treatWarningsAsErrors && split.errors.isEmpty() && split.warnings.isNotEmpty()) {
            return DiagnosticsSections(
                warnings = emptyList(),
                errors = split.warnings
            )
        }

        if (split.errors.isNotEmpty()) {
            return DiagnosticsSections(
                warnings = if (treatWarningsAsErrors) emptyList() else split.warnings,
                errors = split.errors
            )
        }

        return DiagnosticsSections(
            warnings = emptyList(),
            errors = listOf(fullText)
        )
    }

    private fun extractDiagnosticsSections(prefix: String, suffix: String): DiagnosticsSections {
        val prefixSplit = diagnosticsFromText(prefix)
        val suffixSplit = diagnosticsFromText(suffix)
        return DiagnosticsSections(
            warnings = prefixSplit.warnings + suffixSplit.warnings,
            errors = prefixSplit.errors + suffixSplit.errors
        )
    }

    private data class DiagnosticSplit(
        val warnings: List<String>,
        val errors: List<String>
    )

    private fun splitDiagnosticBlocks(text: String): DiagnosticSplit {
        if (text.isBlank()) {
            return DiagnosticSplit(
                warnings = emptyList(),
                errors = emptyList()
            )
        }

        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        val summaryLineIndex =
            lines.indexOfFirst { line ->
                SUMMARY_ERRORS_WARNINGS_REGEX.containsMatchIn(stripAnsi(line).lowercase())
            }.let { index -> if (index >= 0) index else lines.size }

        val markers = extractDiagnosticMarkers(lines, summaryLineIndex)
        if (markers.isNotEmpty()) {
            val warnings = ArrayList<String>()
            val errors = ArrayList<String>()
            for ((index, marker) in markers.withIndex()) {
                if (marker.startLine >= summaryLineIndex) continue
                val nextStart =
                    if (index + 1 < markers.size) markers[index + 1].startLine
                    else summaryLineIndex
                if (nextStart <= marker.startLine) continue
                val block =
                    lines.subList(marker.startLine, nextStart)
                        .joinToString("\n")
                        .trim('\n', '\r')
                if (block.isBlank()) continue
                when (marker.kind) {
                    DiagnosticKind.WARNING -> warnings += block
                    DiagnosticKind.ERROR -> errors += block
                    DiagnosticKind.NONE -> Unit
                }
            }
            return DiagnosticSplit(
                warnings = warnings,
                errors = errors
            )
        }

        return splitDiagnosticBlocksLegacy(normalized)
    }

    private enum class DiagnosticKind {
        WARNING,
        ERROR,
        NONE
    }

    private data class DiagnosticMarker(
        val kind: DiagnosticKind,
        val startLine: Int
    )

    private fun extractDiagnosticMarkers(
        lines: List<String>,
        summaryLineIndex: Int
    ): List<DiagnosticMarker> {
        val markers = ArrayList<DiagnosticMarker>()
        for (index in 0 until summaryLineIndex) {
            val trimmed = stripAnsi(lines[index]).trimStart()
            if (trimmed.startsWith("⚠")) {
                markers += DiagnosticMarker(DiagnosticKind.WARNING, index)
                continue
            }
            if (trimmed.startsWith("×")) {
                var start = index
                val previous = index - 1
                if (previous >= 0) {
                    val previousTrimmed = stripAnsi(lines[previous]).trim()
                    if (DIAGNOSTIC_ERROR_TYPE_LINE_REGEX.matches(previousTrimmed)) {
                        start = previous
                    }
                }
                if (markers.lastOrNull()?.startLine != start) {
                    markers += DiagnosticMarker(DiagnosticKind.ERROR, start)
                }
            }
        }
        return markers
    }

    private fun splitDiagnosticBlocksLegacy(normalized: String): DiagnosticSplit {
        val blocks = normalized.split(DIAGNOSTIC_BLOCK_SEPARATOR_REGEX)
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        var previousKind = DiagnosticKind.NONE
        for (rawBlock in blocks) {
            val block = rawBlock.trim('\n', '\r')
            if (block.isBlank()) continue

            when (classifyDiagnosticBlock(block)) {
                DiagnosticKind.WARNING -> {
                    warnings += block
                    previousKind = DiagnosticKind.WARNING
                }
                DiagnosticKind.ERROR -> {
                    if (errors.isNotEmpty() && shouldMergeWithPreviousError(errors.last(), block)) {
                        errors[errors.lastIndex] = errors.last() + "\n\n" + block
                    } else {
                        errors += block
                    }
                    previousKind = DiagnosticKind.ERROR
                }
                DiagnosticKind.NONE -> {
                    if (previousKind == DiagnosticKind.WARNING && warnings.isNotEmpty() && isDiagnosticContinuationBlock(block)) {
                        warnings[warnings.lastIndex] = warnings.last() + "\n\n" + block
                    } else if (previousKind == DiagnosticKind.ERROR && errors.isNotEmpty() && isDiagnosticContinuationBlock(block)) {
                        errors[errors.lastIndex] = errors.last() + "\n\n" + block
                    } else {
                        previousKind = DiagnosticKind.NONE
                    }
                }
            }
        }
        return DiagnosticSplit(
            warnings = warnings,
            errors = errors
        )
    }

    private fun classifyDiagnosticBlock(block: String): DiagnosticKind {
        val sanitizedBlock = stripAnsi(block)
        val loweredBlock = sanitizedBlock.lowercase()

        val firstNonBlankLine =
            sanitizedBlock.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return DiagnosticKind.NONE

        val firstLower = firstNonBlankLine.lowercase()
        if (SUMMARY_ERRORS_WARNINGS_REGEX.containsMatchIn(loweredBlock) || SUMMARY_ERRORS_WARNINGS_REGEX.containsMatchIn(firstLower)) {
            return DiagnosticKind.NONE
        }

        if (WARNING_COUNTER_ONLY_REGEX.containsMatchIn(loweredBlock) || ERROR_COUNTER_ONLY_REGEX.containsMatchIn(loweredBlock)) {
            return DiagnosticKind.NONE
        }

        if (ERROR_HEADER_REGEX.containsMatchIn(firstNonBlankLine)) {
            return DiagnosticKind.ERROR
        }
        if (WARNING_HEADER_REGEX.containsMatchIn(firstNonBlankLine)) {
            return DiagnosticKind.WARNING
        }

        if (loweredBlock.contains("warning")) return DiagnosticKind.WARNING
        if (loweredBlock.contains("error")) return DiagnosticKind.ERROR

        return DiagnosticKind.NONE
    }

    private fun isDiagnosticContinuationBlock(block: String): Boolean {
        val sanitizedBlock = stripAnsi(block)
        val loweredBlock = sanitizedBlock.lowercase()
        val firstNonBlankLine =
            sanitizedBlock.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return false

        if (SUMMARY_ERRORS_WARNINGS_REGEX.containsMatchIn(loweredBlock)) return false
        if (NON_DIAGNOSTIC_PROGRESS_REGEX.containsMatchIn(firstNonBlankLine.lowercase())) return false
        return true
    }

    private fun shouldMergeWithPreviousError(previousBlock: String, currentBlock: String): Boolean {
        val previousFirstLine =
            stripAnsi(previousBlock)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return false
        if (!DIAGNOSTIC_ERROR_TYPE_LINE_REGEX.matches(previousFirstLine)) return false

        val currentFirstLine =
            stripAnsi(currentBlock)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return false

        return currentFirstLine.startsWith("×") ||
            currentFirstLine.startsWith("╰─▶") ||
            currentFirstLine.startsWith("╭─[") ||
            currentFirstLine.startsWith("help:", ignoreCase = true)
    }

    private fun stripAnsi(text: String): String {
        val withoutAnsi = ANSI_ESCAPE_REGEX.replace(text, "")
        val withoutBrokenAnsi = BROKEN_ANSI_ESCAPE_REGEX.replace(withoutAnsi, "")
        return STRAY_CSI_REGEX.replace(withoutBrokenAnsi, "")
    }

    private fun emitDiagnosticsSections(sections: DiagnosticsSections, handler: ProcessHandler) {
        if (sections.warnings.isNotEmpty()) {
            val payload = buildString {
                append("\nWarnings:\n")
                sections.warnings.forEachIndexed { index, block ->
                    append("\n[warning ").append(index + 1).append("]\n")
                    append(if (block.endsWith('\n')) block else "$block\n")
                }
            }
            handler.notifyTextAvailable(payload, ProcessOutputTypes.STDOUT)
        }
        if (sections.errors.isNotEmpty()) {
            val payload = buildString {
                append("\nErrors:\n")
                sections.errors.forEachIndexed { index, block ->
                    append("\n[error ").append(index + 1).append("]\n")
                    append(if (block.endsWith('\n')) block else "$block\n")
                }
            }
            handler.notifyTextAvailable(payload, ProcessOutputTypes.STDERR)
        }
    }

    private fun mergeDiagnosticsSections(vararg sections: DiagnosticsSections): DiagnosticsSections {
        val warnings = LinkedHashSet<String>()
        val errors = LinkedHashSet<String>()
        for (section in sections) {
            warnings += section.warnings
            errors += section.errors
        }
        return DiagnosticsSections(
            warnings = warnings.toList(),
            errors = errors.toList()
        )
    }

    private fun attachTestLocations(modules: List<ParsedModule>, workDir: String?): List<ParsedModule> {
        if (workDir.isNullOrBlank()) return modules
        val root = File(workDir)
        if (!root.exists() || !root.isDirectory) return modules

        val resolver = AikenTestSourceResolver(root)
        return modules.map { module ->
            val withLocations =
                module.tests.map { test ->
                    val source = resolver.findLocation(module.name, test.title)
                    val locationHint = source?.toLocationHint()
                    test.copy(locationHint = locationHint)
                }
            module.copy(tests = withLocations)
        }
    }

    private fun emitServiceMessage(
        handler: ProcessHandler,
        messageName: String,
        attributes: LinkedHashMap<String, String>
    ) {
        val renderedAttributes =
            attributes.entries.joinToString(" ") { (key, value) ->
                "$key='${escapeTeamCity(value)}'"
            }
        val payload =
            if (renderedAttributes.isBlank()) "##teamcity[$messageName]"
            else "##teamcity[$messageName $renderedAttributes]"
        handler.notifyTextAvailable("$payload\n", ProcessOutputTypes.STDOUT)
    }

    private fun escapeTeamCity(value: String): String {
        return buildString(value.length + 16) {
            for (ch in value) {
                when (ch) {
                    '|' -> append("||")
                    '\'' -> append("|'")
                    '\n' -> append("|n")
                    '\r' -> append("|r")
                    '[' -> append("|[")
                    ']' -> append("|]")
                    else -> append(ch)
                }
            }
        }
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
        return if (this != null && this.isJsonObject) this.asJsonObject else null
    }

    private fun JsonObject.getString(name: String): String? {
        val value = this[name] ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun JsonObject.getInt(name: String): Int? {
        val value = this[name] ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asInt
    }

    private fun JsonObject.getLong(name: String): Long? {
        val value = this[name] ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asLong
    }

    private fun buildTestLeafStdOut(test: ParsedTest): String? {
        val sections = ArrayList<String>(4)

        val status = if (test.passed) ansiGreen("PASS") else ansiRed("FAIL")
        val metrics = formatExecutionUnits(test.memUnits, test.cpuUnits)
        val title = ansiBlue(test.title)
        sections +=
            buildString {
                append(status)
                if (metrics != null) {
                    append(" [").append(metrics).append("]")
                }
                append(" ").append(title)
            }

        extractAssertionText(test.details)?.let { assertion ->
            val rendered =
                assertion.lineSequence()
                    .mapIndexed { index, line ->
                        if (index == 0 && !line.trimStart().startsWith("×")) {
                            "× ${ansiRed(line)}"
                        } else if (index == 0) {
                            ansiRed(line)
                        } else {
                            line
                        }
                    }
                    .joinToString("\n")
            sections += rendered
        }

        if (test.traces.isNotEmpty()) {
            val tracesBlock =
                buildString {
                    append(". with traces\n")
                    test.traces.forEach { trace ->
                        append("| ").append(trace).append('\n')
                    }
                }.trimEnd()
            sections += tracesBlock
        }

        sections += "________"
        return sections.joinToString("\n", postfix = "\n")
    }

    private fun formatExecutionUnits(memUnits: Long?, cpuUnits: Long?): String? {
        if (memUnits == null && cpuUnits == null) return null
        val parts = ArrayList<String>(2)
        memUnits?.let { parts += "mem: ${ansiCyan(formatUnit(it.toDouble() / 1_000.0, "K"))}" }
        cpuUnits?.let { parts += "cpu: ${ansiCyan(formatUnit(it.toDouble() / 1_000_000.0, "M"))}" }
        return parts.joinToString(", ")
    }

    private fun formatUnit(value: Double, suffix: String): String {
        return String.format(Locale.US, "%.2f %s", value, suffix)
    }

    private fun extractAssertionText(details: String?): String? {
        if (details.isNullOrBlank()) return null
        val marker = "assertion:"
        val line =
            details.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith(marker, ignoreCase = true) }
                ?: return null
        val value = line.substringAfter(':', "").trim()
        return value.ifBlank { null }
    }

    private fun ansiGreen(text: String): String = "$ANSI_GREEN$text$ANSI_RESET"

    private fun ansiRed(text: String): String = "$ANSI_RED$text$ANSI_RESET"

    private fun ansiBlue(text: String): String = "$ANSI_BLUE$text$ANSI_RESET"

    private fun ansiCyan(text: String): String = "$ANSI_CYAN$text$ANSI_RESET"

    private fun JsonObject.getStringArray(name: String): List<String> {
        val value = this[name] ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
        }
    }

    private fun createPtyProcessHandler(invocation: List<String>, workDir: String?): ProcessHandler {
        try {
            val ptyProcess =
                PtyProcessBuilder(invocation.toTypedArray())
                    .setDirectory(workDir ?: project.basePath)
                    .setEnvironment(HashMap(System.getenv()))
                    .setRedirectErrorStream(true)
                    .setWindowsAnsiColorEnabled(true)
                    .start()

            return KillableColoredProcessHandler(
                ptyProcess,
                invocation.joinToString(" "),
                StandardCharsets.UTF_8
            ).also { handler ->
                (handler as? OSProcessHandler)?.setHasPty(true)
            }
        } catch (e: IOException) {
            throw ExecutionException("Failed to start Aiken via PTY: ${e.message}", e)
        }
    }

    private fun createPtyTerminalProcessHandler(invocation: List<String>, workDir: String?): ProcessHandler {
        try {
            val ptyProcess =
                PtyProcessBuilder(invocation.toTypedArray())
                    .setDirectory(workDir ?: project.basePath)
                    .setEnvironment(HashMap(System.getenv()))
                    .setRedirectErrorStream(true)
                    .setWindowsAnsiColorEnabled(true)
                    .start()

            return KillableProcessHandler(
                ptyProcess,
                "",
                StandardCharsets.UTF_8
            ).also { handler ->
                handler.setHasPty(true)
            }
        } catch (e: IOException) {
            throw ExecutionException("Failed to start Aiken via PTY: ${e.message}", e)
        }
    }

    private fun resolveApplyInspectionFile(workDir: String?): File? {
        val preferredOut = absolutizeApplyPath(applyOut).trim()
        val preferredIn = absolutizeApplyPath(applyInput).trim()
        val selectedPath = when {
            preferredOut.isNotEmpty() -> preferredOut
            preferredIn.isNotEmpty() -> preferredIn
            else -> return null
        }

        val file = File(selectedPath)
        if (file.isAbsolute) return file

        val baseDir = workDir ?: resolveProjectDirectory() ?: project.basePath ?: return null
        return File(baseDir, selectedPath)
    }

    private fun hasPendingApplyParameters(blueprintFile: File): Boolean? {
        if (!blueprintFile.exists() || !blueprintFile.isFile) return null

        val root =
            try {
                JsonParser.parseString(blueprintFile.readText(StandardCharsets.UTF_8)).asJsonObject
            } catch (_: Exception) {
                return null
            }

        val validators = root["validators"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val moduleFilter = applyModule.trim().ifEmpty { null }
        val validatorFilter = applyValidator.trim().ifEmpty { null }
        val useAllValidators = moduleFilter == null && validatorFilter == null

        var matchedValidators = 0
        for (element in validators) {
            val obj = element.asJsonObjectOrNull() ?: continue

            if (!useAllValidators) {
                val title = obj.getString("title") ?: continue
                val parsedTarget = parseValidatorTargetFromTitle(title) ?: continue
                if (moduleFilter != null && parsedTarget.first != moduleFilter) continue
                if (validatorFilter != null && parsedTarget.second != validatorFilter) continue
            }

            matchedValidators += 1

            val parameters = obj["parameters"]
            if (parameters != null && parameters.isJsonArray && parameters.asJsonArray.size() > 0) {
                return true
            }
        }

        if (matchedValidators == 0) return null
        return false
    }

    private fun resolveConvertOutputDirectory(workDir: String?): File? {
        val trimmed = convertTerminalOutputFile.trim().ifEmpty { "artifacts" }
        val output = File(trimmed)
        if (output.isAbsolute) return output

        val base = workDir ?: resolveProjectDirectory() ?: project.basePath ?: return output
        return File(base, trimmed)
    }

    private fun resolveConvertArtifactStem(workDir: String?): String {
        val configuredModule = convertModule.trim()
        val configuredValidator = convertValidator.trim()
        if (configuredModule.isNotEmpty() && configuredValidator.isNotEmpty()) {
            return "${sanitizeArtifactSegment(configuredModule)}.${sanitizeArtifactSegment(configuredValidator)}"
        }

        val inferred = resolveSingleValidatorFromBlueprint(workDir)
        val modulePart = sanitizeArtifactSegment(configuredModule.ifEmpty { inferred?.first ?: "module" })
        val validatorPart = sanitizeArtifactSegment(configuredValidator.ifEmpty { inferred?.second ?: "validator" })
        return "$modulePart.$validatorPart"
    }

    private fun sanitizeConvertOutputForScript(raw: String, invocation: List<String>): String {
        val text = stripAnsi(raw).replace("\r\n", "\n").replace('\r', '\n')
        val extractedJson = extractConvertJsonObject(text)
        if (!extractedJson.isNullOrBlank()) {
            return extractedJson.trimEnd() + "\n"
        }

        val lines = text.lines().toMutableList()
        val invocationLine = invocation.joinToString(" ").trim()

        while (lines.isNotEmpty()) {
            val first = lines.first().trim()
            if (first.isEmpty()) {
                lines.removeAt(0)
                continue
            }
            if (first == invocationLine || first.startsWith("aiken blueprint convert ")) {
                lines.removeAt(0)
                continue
            }
            break
        }

        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun extractConvertJsonObject(rawOutput: String): String? {
        var best: String? = null
        var start = rawOutput.indexOf('{')
        while (start >= 0) {
            val endInclusive = findJsonObjectEnd(rawOutput, start)
            if (endInclusive != null) {
                val candidate = rawOutput.substring(start, endInclusive + 1)
                try {
                    val parsed = JsonParser.parseString(candidate)
                    if (parsed.isJsonObject) {
                        val obj = parsed.asJsonObject
                        if (looksLikeConvertScriptJson(obj)) {
                            best = candidate
                        } else if (best == null) {
                            best = candidate
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed candidates and continue.
                }
            }
            start = rawOutput.indexOf('{', start + 1)
        }
        return best
    }

    private fun looksLikeConvertScriptJson(root: JsonObject): Boolean {
        if (root["cborHex"]?.isJsonPrimitive == true) return true
        if (root["script"]?.isJsonObject == true) {
            val scriptObject = root["script"].asJsonObject
            if (scriptObject["cborHex"]?.isJsonPrimitive == true) return true
        }
        val type = root.getString("type")
        return !type.isNullOrBlank() && type.contains("PlutusScript", ignoreCase = true)
    }

    private fun resolveSingleValidatorFromBlueprint(workDir: String?): Pair<String, String>? {
        val baseDir = workDir ?: resolveProjectDirectory() ?: project.basePath ?: return null
        val blueprintFile = File(baseDir, "plutus.json")
        if (!blueprintFile.exists() || !blueprintFile.isFile) return null

        val root =
            try {
                JsonParser.parseString(blueprintFile.readText(StandardCharsets.UTF_8)).asJsonObject
            } catch (_: Exception) {
                return null
            }

        val validators = root["validators"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val parsedTargets = LinkedHashSet<Pair<String, String>>()
        for (element in validators) {
            val obj = element.asJsonObjectOrNull() ?: continue
            val title = obj.getString("title") ?: continue
            val parsed = parseValidatorTargetFromTitle(title) ?: continue
            parsedTargets += parsed
        }
        if (parsedTargets.isEmpty()) return null

        val moduleFilter = convertModule.trim().ifEmpty { null }
        val validatorFilter = convertValidator.trim().ifEmpty { null }
        val filtered =
            parsedTargets.filter { (module, validator) ->
                (moduleFilter == null || module == moduleFilter) &&
                    (validatorFilter == null || validator == validatorFilter)
            }
        return filtered.singleOrNull()
    }

    private fun parseValidatorTargetFromTitle(title: String): Pair<String, String>? {
        val firstDot = title.indexOf('.')
        if (firstDot <= 0) return null
        val secondDot = title.indexOf('.', firstDot + 1)
        val end = if (secondDot > firstDot + 1) secondDot else title.length
        if (end <= firstDot + 1) return null
        val module = title.substring(0, firstDot).trim()
        val validator = title.substring(firstDot + 1, end).trim()
        if (module.isEmpty() || validator.isEmpty()) return null
        return module to validator
    }

    private fun sanitizeArtifactSegment(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "value"
        return trimmed
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifEmpty { "value" }
    }

    private fun appendValueOption(target: MutableList<String>, option: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        target += option
        target += trimmed
    }

    private data class TestSourceLocation(
        val absolutePath: String,
        val line: Int
    )

    private fun TestSourceLocation.toLocationHint(): String {
        val encodedPath =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(absolutePath.toByteArray(StandardCharsets.UTF_8))
        val normalizedLine = line.coerceAtLeast(1)
        return "$AIKEN_TEST_LOCATION_PROTOCOL://$encodedPath:$normalizedLine"
    }

    private class AikenTestSourceResolver(
        private val rootDir: File
    ) {
        private val moduleFileCache = HashMap<String, File?>()
        private val fileTestsCache = HashMap<String, Map<String, Int>>()
        private val allAkFiles: List<File> by lazy { discoverAkFiles(rootDir) }
        private val globalTestsByName: Map<String, List<TestSourceLocation>> by lazy {
            val mapping = HashMap<String, MutableList<TestSourceLocation>>()
            for (file in allAkFiles) {
                for ((name, line) in parseTestDeclarations(file)) {
                    mapping.getOrPut(name) { ArrayList() } += TestSourceLocation(file.absolutePath, line)
                }
            }
            mapping
        }

        fun findLocation(moduleName: String, testName: String): TestSourceLocation? {
            val moduleFile = moduleFileCache.getOrPut(moduleName) { resolveModuleFile(moduleName) }
            if (moduleFile != null) {
                val line = getTestDeclarations(moduleFile)[testName]
                if (line != null) {
                    return TestSourceLocation(moduleFile.absolutePath, line)
                }
            }

            val byName = globalTestsByName[testName].orEmpty()
            if (byName.size == 1) return byName.first()
            return null
        }

        private fun resolveModuleFile(moduleName: String): File? {
            val normalized = normalizeModuleName(moduleName)
            if (normalized.isBlank()) return null

            val directCandidates =
                linkedSetOf(
                    "$normalized.ak",
                    normalized.replace('.', '/') + ".ak",
                    normalized.replace("::", "/") + ".ak"
                )

            for (candidate in directCandidates) {
                val file = File(rootDir, candidate)
                if (file.isFile) return file
            }

            for (file in allAkFiles) {
                val relative = toRelativePath(file)
                val stem = if (relative.endsWith(".ak")) relative.dropLast(3) else relative
                if (stem == normalized || stem.endsWith("/$normalized")) {
                    return file
                }
            }

            return null
        }

        private fun getTestDeclarations(file: File): Map<String, Int> {
            return fileTestsCache.getOrPut(file.absolutePath) { parseTestDeclarations(file) }
        }

        private fun parseTestDeclarations(file: File): Map<String, Int> {
            val result = LinkedHashMap<String, Int>()
            if (!file.isFile) return result

            var lineNumber = 0
            file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { rawLine ->
                    lineNumber += 1
                    val match = TEST_DECLARATION_REGEX.find(rawLine) ?: return@forEach
                    val name = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        result.putIfAbsent(name, lineNumber)
                    }
                }
            }
            return result
        }

        private fun toRelativePath(file: File): String {
            return rootDir.toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        }

        private fun normalizeModuleName(name: String): String {
            return name.trim()
                .removeSuffix(".ak")
                .trim('/')
                .replace('\\', '/')
        }

        private fun discoverAkFiles(root: File): List<File> {
            val skipDirs = setOf(".git", ".idea", ".gradle", ".intellijPlatform", "build")
            return root.walkTopDown()
                .onEnter { dir -> dir == root || dir.name !in skipDirs }
                .filter { it.isFile && it.extension == "ak" }
                .toList()
        }
    }

    private object AikenTestLocator : SMTestLocator {
        override fun getLocation(
            protocol: String,
            path: String,
            project: Project,
            scope: GlobalSearchScope
        ): List<Location<*>> {
            return resolve(protocol, path, project)
        }

        override fun getLocation(
            path: String,
            project: Project,
            scope: GlobalSearchScope
        ): List<Location<*>> {
            val protocol = VirtualFileManager.extractProtocol(path) ?: return emptyList()
            val extractedPath = VirtualFileManager.extractPath(path)
            return resolve(protocol, extractedPath, project)
        }

        override fun getLocation(
            protocol: String,
            path: String,
            metainfo: String?,
            project: Project,
            scope: GlobalSearchScope
        ): List<Location<*>> {
            return resolve(protocol, path, project)
        }

        private fun resolve(
            protocol: String,
            path: String,
            project: Project
        ): List<Location<*>> {
            if (!protocol.equals(AIKEN_TEST_LOCATION_PROTOCOL, ignoreCase = true)) {
                return emptyList()
            }

            val decoded = decodeLocationHintPath(path) ?: return emptyList()
            val virtualFile =
                LocalFileSystem.getInstance()
                    .findFileByPath(decoded.absolutePath.replace('\\', '/'))
                    ?: return emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return emptyList()
            val element = findPsiElementAtLine(project, psiFile, decoded.line)
            return listOf(PsiLocation.fromPsiElement(project, element))
        }

        private data class DecodedLocationHint(
            val absolutePath: String,
            val line: Int
        )

        private fun decodeLocationHintPath(path: String): DecodedLocationHint? {
            val separator = path.lastIndexOf(':')
            val encodedPath = if (separator == -1) path else path.substring(0, separator)
            val line =
                if (separator == -1) {
                    1
                } else {
                    path.substring(separator + 1).toIntOrNull()?.coerceAtLeast(1) ?: return null
                }
            return try {
                val decoded = String(Base64.getUrlDecoder().decode(encodedPath), StandardCharsets.UTF_8)
                if (decoded.isBlank()) null else DecodedLocationHint(decoded, line)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun findPsiElementAtLine(project: Project, file: PsiFile, oneBasedLine: Int): PsiElement {
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return file
            if (document.lineCount <= 0) return file
            val lineIndex = (oneBasedLine - 1).coerceIn(0, document.lineCount - 1)
            val offset = document.getLineStartOffset(lineIndex)
            return file.findElementAt(offset) ?: file
        }
    }

    private fun validateDirectory(path: String, label: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        val file = File(trimmed)
        if (!file.exists() || !file.isDirectory) {
            throw RuntimeConfigurationError("$label does not exist or is not a directory: $trimmed")
        }
    }

    private fun validateFilePath(path: String, label: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        if (!looksLikeExplicitPath(trimmed)) return
        val file = File(trimmed)
        if (!file.exists() || !file.isFile) {
            throw RuntimeConfigurationError("$label does not exist or is not a file: $trimmed")
        }
    }

    private fun looksLikeExplicitPath(value: String): Boolean {
        if (value.contains('/') || value.contains('\\')) return true
        if (value.startsWith(".")) return true
        if (value.length >= 2 && value[1] == ':') return true // Windows drive letter.
        return false
    }

    private class AikenAsyncProcessHandler : NopProcessHandler() {
        private val processRef = AtomicReference<Process?>()
        private val finished = AtomicBoolean(false)

        fun attachProcess(process: Process) {
            processRef.set(process)
        }

        fun finish(exitCode: Int) {
            if (finished.compareAndSet(false, true)) {
                notifyProcessTerminated(exitCode)
            }
        }

        override fun destroyProcessImpl() {
            processRef.getAndSet(null)?.destroyForcibly()
            finish(-1)
        }
    }

    private fun validateUnsignedInteger(value: String, label: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val parsed = trimmed.toULongOrNull()
        if (parsed == null) {
            throw RuntimeConfigurationError("$label must be an unsigned integer.")
        }
    }

    private fun writeField(element: Element, fieldName: String, value: String) {
        JDOMExternalizerUtil.writeField(element, fieldName, value)
    }

    private fun readString(element: Element, fieldName: String, defaultValue: String): String {
        return JDOMExternalizerUtil.readField(element, fieldName, defaultValue)
    }

    private fun readBoolean(element: Element, fieldName: String, defaultValue: Boolean): Boolean {
        val value = JDOMExternalizerUtil.readField(element, fieldName, defaultValue.toString())
        return value.toBooleanStrictOrNull() ?: defaultValue
    }

    private fun AikenRunCommand.defaultConfigurationName(): String =
        when (this) {
            AikenRunCommand.CHECK -> "Run checks"
            AikenRunCommand.BUILD -> "Build blueprint"
            AikenRunCommand.ADDRESS -> "Make artifacts"
            AikenRunCommand.CLEAN -> "Clean artifacts"
            AikenRunCommand.APPLY -> "Parametrize"
            AikenRunCommand.CONVERT -> "Make artifacts"
        }

    private inline fun <reified T : Enum<T>> readEnum(
        element: Element,
        fieldName: String,
        defaultValue: T
    ): T {
        val raw = JDOMExternalizerUtil.readField(element, fieldName, defaultValue.name)
        return enumValues<T>().firstOrNull { it.name == raw } ?: defaultValue
    }

    private companion object {
        const val ANSI_RESET = "\u001B[0m"
        const val ANSI_RED = "\u001B[31m"
        const val ANSI_GREEN = "\u001B[32m"
        const val ANSI_BLUE = "\u001B[34m"
        const val ANSI_CYAN = "\u001B[36m"
        const val ANSI_CLEAR_SCREEN_AND_HOME = "\u001B[2J\u001B[H"
        const val APPLY_PARAMETERS_HINT_LINE =
            "Tip: paste these values into 'Auto aplied CBOR parameters' to automate parameterization in future runs."
        const val AIKEN_TEST_LOCATION_PROTOCOL = "aiken-test"
        val TEST_DECLARATION_REGEX = Regex("""^\s*(pub\s+)?test\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        val DIAGNOSTIC_BLOCK_SEPARATOR_REGEX = Regex("""\n{2,}""")
        val WARNING_HEADER_REGEX = Regex("""^(warning(\[[^\]]+])?:|.+:\s*warning(\[[^\]]+])?:|⚠\s+.*)$""", RegexOption.IGNORE_CASE)
        val ERROR_HEADER_REGEX = Regex("""^(error(\[[^\]]+])?:|.+:\s*error(\[[^\]]+])?:|×\s+.*)$""", RegexOption.IGNORE_CASE)
        val SUMMARY_ERRORS_WARNINGS_REGEX = Regex("""\bsummary\b.*\berrors?\b.*\bwarnings?\b""")
        val WARNING_COUNTER_ONLY_REGEX = Regex("""\b\d+\s+warnings?\b""")
        val ERROR_COUNTER_ONLY_REGEX = Regex("""\b\d+\s+errors?\b""")
        val NON_DIAGNOSTIC_PROGRESS_REGEX = Regex(
            """^(compiling|collecting|testing|building|resolving|watching|generating|dumping|packages?\s+downloaded|summary)\b""",
            RegexOption.IGNORE_CASE
        )
        val DIAGNOSTIC_LOCATION_REGEX = Regex("""\[(.+?\.ak):(\d+)(?::\d+)?]""")
        val DIAGNOSTIC_LOCATION_BRACKET_LINE_REGEX = Regex("""^\[.+:\d+(?::\d+)?]$""")
        val DIAGNOSTIC_ERROR_TYPE_LINE_REGEX = Regex("""^error\s+[A-Za-z0-9_:.\\/-]+$""", RegexOption.IGNORE_CASE)
        val DIAGNOSTIC_GENERIC_WHILE_LINE_REGEX = Regex("""^while\s+.+\.\.\.$""", RegexOption.IGNORE_CASE)
        val DIAGNOSTIC_PREFIX_REGEX = Regex("""^(warning|error)(\[[^\]]+])?:\s*""", RegexOption.IGNORE_CASE)
        val DIAGNOSTIC_LEADING_SYMBOLS_REGEX = Regex("""^[^A-Za-z0-9]+""")
        val ANSI_ESCAPE_REGEX = Regex("""(?:\u001B\[|\u009B)[0-?]*[ -/]*[@-~]""")
        val BROKEN_ANSI_ESCAPE_REGEX = Regex("""\uFFFD\[[0-?]*[ -/]*[@-~]""")
        val STRAY_CSI_REGEX = Regex("""[\u001B\u009B\uFFFD]""")
        val ADDRESS_OUTPUT_LINE_REGEX = Regex("""^addr(?:_test)?1[a-z0-9]+$""")
        val POLICY_ID_OUTPUT_LINE_REGEX = Regex("""^[0-9a-f]{56}$""")
        val APPLYING_LINE_REGEX = Regex("""^\s*Applying(?:\s+([0-9a-fA-F]+))?\s*$""", RegexOption.IGNORE_CASE)
        val APPLY_HEX_CHUNK_REGEX = Regex("""^[0-9a-fA-F]+$""")
        const val DEFAULT_ARTIFACT_SCRIPT_TEMPLATE = "%module%.%validator%.script"
        const val DEFAULT_ARTIFACT_MAINNET_TEMPLATE = "%module%.%validator%.addr"
        const val DEFAULT_ARTIFACT_TESTNET_TEMPLATE = "%module%.%validator%.addr_test"
        const val DEFAULT_ARTIFACT_POLICY_TEMPLATE = "%module%.%validator%.policy"
        const val IDE_WATCH_RESTART_DEBOUNCE_MS = 450L
        val APPLY_AUTO_CBOR_ACCUMULATOR = ConcurrentHashMap<String, MutableList<String>>()
        val APPLY_AUTO_CBOR_QUEUE = ConcurrentHashMap<String, ArrayDeque<String>>()
        val APPLY_AUTO_HAS_NON_CONFIGURED_APPLIES = ConcurrentHashMap<String, AtomicBoolean>()
    }
}

enum class AikenRunCommand(val cliValue: String, private val label: String) {
    BUILD("build", "build"),
    ADDRESS("address", "artifacts"),
    CLEAN("clean", "clean"),
    APPLY("blueprint apply", "blueprint apply"),
    CONVERT("blueprint convert", "artifacts"),
    CHECK("check", "check");

    override fun toString(): String = label
}

enum class AikenTraceFilter(val cliValue: String, private val label: String) {
    USER_DEFINED("user-defined", "user-defined"),
    COMPILER_GENERATED("compiler-generated", "compiler-generated"),
    ALL("all", "all");

    override fun toString(): String = label
}

enum class AikenTraceLevel(val cliValue: String, private val label: String) {
    SILENT("silent", "silent"),
    COMPACT("compact", "compact"),
    VERBOSE("verbose", "verbose");

    override fun toString(): String = label
}

enum class AikenPropertyCoverage(val cliValue: String, private val label: String) {
    RELATIVE_TO_LABELS("relative-to-labels", "relative-to-labels"),
    RELATIVE_TO_TESTS("relative-to-tests", "relative-to-tests");

    override fun toString(): String = label
}

enum class AikenCheckOutputMode(private val label: String) {
    TTY("tty"),
    JSON("json"),
    IDE_INTEGRATED("ide integrated");

    override fun toString(): String = label
}

enum class AikenBuildOutputMode(private val label: String) {
    TTY("tty"),
    IDE_INTEGRATED("ide integrated");

    override fun toString(): String = label
}

enum class AikenBlueprintConvertTarget(val cliValue: String, private val label: String) {
    CARDANO_CLI("cardano-cli", "cardano-cli");

    override fun toString(): String = label
}
