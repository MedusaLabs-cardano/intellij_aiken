package com.medusalabs.aiken.run

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.pty4j.PtyProcessBuilder
import com.intellij.util.execution.ParametersListUtil
import org.jdom.Element
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class AikenRunConfiguration(
    project: com.intellij.openapi.project.Project,
    factory: com.intellij.execution.configurations.ConfigurationFactory,
    name: String,
    initialCommand: AikenRunCommand = AikenRunCommand.CHECK
) : RunConfigurationBase<Any>(project, factory, name) {
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
    var addressInput: String = "plutus.json"

    @JvmField
    var addressModule: String = ""

    @JvmField
    var addressValidator: String = ""

    @JvmField
    var addressDelegatedTo: String = ""

    @JvmField
    var addressMainnet: Boolean = false

    @JvmField
    var checkSkipTests: Boolean = false

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

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val executable = resolveAikenExecutable()
                val workDir = resolveProjectDirectory()
                val args = buildCommandParameters()
                val invocation = buildInvocation(executable, args, workDir)

                // `aiken check` emits rich human-readable output only for TTY.
                // Running via PTY keeps output identical to terminal instead of JSON fallback.
                if (command == AikenRunCommand.CHECK) {
                    return createPtyProcessHandler(invocation, workDir)
                }

                val commandLine = GeneralCommandLine()
                    .withCharset(StandardCharsets.UTF_8)
                    .withExePath(executable)
                workDir?.let { commandLine.withWorkDirectory(it) }
                commandLine.addParameter(command.cliValue)
                commandLine.addParameters(args)
                workDir?.let { commandLine.addParameter(it) }

                return KillableColoredProcessHandler(commandLine)
            }
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

        addressInput = readString(element, "addressInput", addressInput)
        addressModule = readString(element, "addressModule", addressModule)
        addressValidator = readString(element, "addressValidator", addressValidator)
        addressDelegatedTo = readString(element, "addressDelegatedTo", addressDelegatedTo)
        addressMainnet = readBoolean(element, "addressMainnet", false)

        checkSkipTests = readBoolean(element, "checkSkipTests", false)
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

        writeField(element, "addressInput", addressInput)
        writeField(element, "addressModule", addressModule)
        writeField(element, "addressValidator", addressValidator)
        writeField(element, "addressDelegatedTo", addressDelegatedTo)
        writeField(element, "addressMainnet", addressMainnet.toString())

        writeField(element, "checkSkipTests", checkSkipTests.toString())
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

    private fun buildCommandParameters(): List<String> {
        val parameters = ArrayList<String>()

        when (command) {
            AikenRunCommand.BUILD -> {
                if (denyWarnings) parameters += "--deny"
                if (silentWarnings) parameters += "--silent"
                if (watch) parameters += "--watch"
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
                if (addressMainnet) parameters += "--mainnet"
            }

            AikenRunCommand.CHECK -> {
                if (denyWarnings) parameters += "--deny"
                if (silentWarnings) parameters += "--silent"
                if (watch) parameters += "--watch"
                if (checkSkipTests) parameters += "--skip-tests"
                if (checkDebug) parameters += "--debug"
                appendValueOption(parameters, "--seed", checkSeed)
                appendValueOption(parameters, "--max-success", checkMaxSuccess)
                parameters += listOf("--property-coverage", checkPropertyCoverage.cliValue)
                appendValueOption(parameters, "--match-tests", checkMatchTests)
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

    private fun buildInvocation(
        executable: String,
        args: List<String>,
        directoryArg: String?
    ): List<String> {
        val invocation = ArrayList<String>(args.size + 3)
        invocation += executable
        invocation += command.cliValue
        invocation += args
        directoryArg?.let { invocation += it }
        return invocation
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
            )
        } catch (e: IOException) {
            throw ExecutionException("Failed to start Aiken via PTY: ${e.message}", e)
        }
    }

    private fun appendValueOption(target: MutableList<String>, option: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        target += option
        target += trimmed
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

    private inline fun <reified T : Enum<T>> readEnum(
        element: Element,
        fieldName: String,
        defaultValue: T
    ): T {
        val raw = JDOMExternalizerUtil.readField(element, fieldName, defaultValue.name)
        return enumValues<T>().firstOrNull { it.name == raw } ?: defaultValue
    }
}

enum class AikenRunCommand(val cliValue: String, private val label: String) {
    BUILD("build", "build"),
    ADDRESS("address", "address"),
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
