package com.medusalabs.aiken.tooling

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.medusalabs.aiken.run.AikenCliCompatibility
import com.intellij.util.io.HttpRequests
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object AikenNodeToolchain {
    const val AIKEN_NPM_PACKAGE = "@aiken-lang/aiken"
    const val DEFAULT_AIKEN_VERSION = "latest"
    const val LOCAL_TOOLCHAIN_DIRECTORY = "bin"
    const val MIN_SUPPORTED_AIKEN_VERSION = "1.1.1"
    private const val NPM_REGISTRY_URL = "https://registry.npmjs.org/"
    private const val HTTP_TIMEOUT_MILLIS = 5000
    private const val VERSION_FETCH_TIMEOUT_SECONDS = 10L
    private const val PROCESS_TIMEOUT_SECONDS = 5L
    private const val NPM_VIEW_TIMEOUT_SECONDS = 20L
    private const val NPM_INSTALL_TIMEOUT_SECONDS = 300L
    private const val NPM_EXEC_TIMEOUT_SECONDS = 120L
    private val LOG = Logger.getInstance(AikenNodeToolchain::class.java)
    private val AIKEN_TOML_COMPILER_REGEX = Regex("""(?m)^compiler\s*=\s*["']([^"']+)["']\s*$""")
    @Volatile
    private var cachedSystemNpmPath: String? = null
    @Volatile
    private var cachedVersionCatalog: VersionCatalog? = null

    data class ProcessResult(val exitCode: Int, val output: String)

    data class VersionCatalog(
        val latest: String,
        val versions: List<String>
    )

    data class NpmAvailability(
        val available: Boolean,
        val message: String
    )

    internal enum class LocalAikenState {
        HEALTHY,
        MISSING,
        BROKEN
    }

    internal data class LocalAikenProbe(
        val state: LocalAikenState,
        val executable: String? = null,
        val resolvedVersion: AikenCliCompatibility.SemanticVersion? = null,
        val details: String? = null
    )

    private val MIN_SUPPORTED_AIKEN_SEMANTIC_VERSION =
        checkNotNull(AikenCliCompatibility.SemanticVersion.parse(MIN_SUPPORTED_AIKEN_VERSION)) {
            "Invalid minimum supported Aiken version: $MIN_SUPPORTED_AIKEN_VERSION"
        }

    fun normalizeRequestedVersion(version: String): String =
        version
            .trim()
            .ifEmpty { DEFAULT_AIKEN_VERSION }
            .let { normalized ->
                if (normalized.equals(DEFAULT_AIKEN_VERSION, ignoreCase = true)) {
                    DEFAULT_AIKEN_VERSION
                } else if (
                    normalized.startsWith("v") &&
                    AikenCliCompatibility.SemanticVersion.parse(normalized.removePrefix("v")) != null
                ) {
                    normalized.removePrefix("v")
                } else {
                    normalized
                }
            }

    fun describeNpmAvailability(project: Project): NpmAvailability {
        val systemNpm = resolveSystemNpmExecutable()
        if (systemNpm != null) {
            val message = if (systemNpm == systemNpmExecutable()) {
                "System npm detected in PATH."
            } else {
                "System npm detected via shell environment: $systemNpm"
            }
            return NpmAvailability(true, message)
        }

        val ideMessage = IdeNodeIntegration.describeAvailability(project)
        if (ideMessage != null) {
            return NpmAvailability(true, ideMessage)
        }

        val configureHint = if (IdeNodeIntegration.isAvailable()) {
            " Configure Node.js in the IDE or install npm in PATH."
        } else {
            " Install npm in PATH."
        }
        return NpmAvailability(false, "Node.js / npm is not configured.$configureHint")
    }

    fun openNodeInterpreterDialog(project: Project): Boolean =
        IdeNodeIntegration.openInterpreterDialog(project) || openNodeSettings(project)

    fun fetchAvailableAikenVersions(project: Project, baseDirectory: VirtualFile?): CompletableFuture<VersionCatalog> {
        cachedVersionCatalog?.let { return CompletableFuture.completedFuture(it) }
        val future = CompletableFuture<VersionCatalog>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                LOG.info("Loading Aiken versions for new-project/toolchain UI")
                val catalog = fetchAvailableVersionsBlocking(project, baseDirectory)
                cachedVersionCatalog = catalog
                LOG.info("Loaded Aiken versions successfully; latest=${catalog.latest}, count=${catalog.versions.size}")
                future.complete(catalog)
            } catch (t: Throwable) {
                LOG.warn("Failed to load Aiken versions", t)
                future.completeExceptionally(t)
            }
        }
        return future.orTimeout(VERSION_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun buildScaffoldExecArguments(
        version: String,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean
    ): List<String> {
        val args = mutableListOf(
            "--yes",
            "--package=$AIKEN_NPM_PACKAGE@${normalizeRequestedVersion(version)}",
            "--",
            "aiken",
            "new"
        )
        if (libraryOnly) {
            args += "-l"
        }
        args += "$vendor/$projectName"
        return args
    }

    fun buildLocalInstallArguments(version: String): List<String> = listOf(
        "--prefix",
        LOCAL_TOOLCHAIN_DIRECTORY,
        "--no-save",
        "--no-package-lock",
        "$AIKEN_NPM_PACKAGE@${normalizeRequestedVersion(version)}",
        "--no-fund",
        "--no-audit"
    )

    fun buildRepairInstallArguments(version: String): List<String> = listOf(
        "--prefix",
        LOCAL_TOOLCHAIN_DIRECTORY,
        "--no-save",
        "--no-package-lock",
        "--force",
        "$AIKEN_NPM_PACKAGE@${normalizeRequestedVersion(version)}",
        "--no-fund",
        "--no-audit"
    )

    fun runNpmCommand(
        project: Project,
        workingDirectory: Path,
        commandName: String,
        arguments: List<String>
    ): ProcessResult {
        val systemNpm = resolveSystemNpmExecutable()
        if (systemNpm != null) {
            val cli = buildList {
                add(systemNpm)
                add(commandName.lowercase())
                addAll(arguments)
            }
            return runProcess(workingDirectory.toFile(), cli, timeoutForNpmCommand(commandName))
        }

        IdeNodeIntegration.runNpmCommand(project, workingDirectory, commandName, arguments, timeoutForNpmCommand(commandName))
            ?.let { return it }

        throw IllegalStateException("npm is not available in PATH and IDE Node.js integration is not configured.")
    }

    fun isSystemNpmAvailable(): Boolean = resolveSystemNpmExecutable() != null

    fun applyProjectLocalAikenToTerminalEnvironment(project: Project, environment: MutableMap<String, String>): Boolean {
        val settings = project.service<AikenProjectToolchainSettings>()
        if (settings.getMode() != AikenToolchainMode.LOCAL) {
            return false
        }

        val projectDirectory = project.basePath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val executable = resolveProjectLocalAikenExecutable(projectDirectory) ?: return false
        val executableDirectory = Path.of(executable).parent?.toString() ?: return false
        prependPathEntry(environment, executableDirectory)
        return true
    }

    fun isSupportedAikenVersion(version: String): Boolean {
        val normalized = normalizeRequestedVersion(version)
        if (normalized == DEFAULT_AIKEN_VERSION) {
            return true
        }

        val semantic = AikenCliCompatibility.SemanticVersion.parse(normalized) ?: return false
        return semantic >= MIN_SUPPORTED_AIKEN_SEMANTIC_VERSION
    }

    internal fun resolveSystemNpmExecutable(): String? {
        cachedSystemNpmPath?.let { cached ->
            if (isExecutableUsable(cached)) {
                return cached
            }
            cachedSystemNpmPath = null
        }

        val direct = systemNpmExecutable().takeIf(::isExecutableUsable)
        if (direct != null) {
            cachedSystemNpmPath = direct
            return direct
        }

        val shellResolved = resolveNpmFromLoginShell()
        if (shellResolved != null) {
            cachedSystemNpmPath = shellResolved
            return shellResolved
        }

        return null
    }

    fun resolveProjectLocalAikenExecutable(projectDirectory: String?): String? {
        val probe = inspectProjectLocalAiken(projectDirectory)
        return if (probe.state == LocalAikenState.HEALTHY) probe.executable else null
    }

    internal fun inspectProjectLocalAiken(projectDirectory: String?): LocalAikenProbe {
        val basePath = projectDirectory?.trim()?.takeIf { it.isNotEmpty() }
            ?: return LocalAikenProbe(LocalAikenState.MISSING)
        val projectRoot = Path.of(basePath)
        val layoutRoot = localToolchainRoot(projectRoot)

        val realBinary = realBinaryCandidates(layoutRoot).firstOrNull { Files.isRegularFile(it) }
        if (realBinary != null) {
            return runCatching {
                val result = runProcess(File(basePath), listOf(realBinary.toString(), "--version"), PROCESS_TIMEOUT_SECONDS)
                if (result.exitCode == 0) {
                    LocalAikenProbe(
                        state = LocalAikenState.HEALTHY,
                        executable = realBinary.toString(),
                        resolvedVersion = AikenCliCompatibility.extractVersion(result.output)
                    )
                } else {
                    LocalAikenProbe(
                        state = LocalAikenState.BROKEN,
                        executable = realBinary.toString(),
                        details = result.output.ifBlank { "Local Aiken exited with code ${result.exitCode}." }
                    )
                }
            }.getOrElse { error ->
                LocalAikenProbe(
                    state = LocalAikenState.BROKEN,
                    executable = realBinary.toString(),
                    details = error.message
                )
            }
        }

        val wrapperBinary = wrapperCandidates(layoutRoot).firstOrNull { Files.isRegularFile(it) }
        if (wrapperBinary != null) {
            return LocalAikenProbe(
                state = LocalAikenState.BROKEN,
                details = "The local Aiken wrapper exists, but the packaged binary is missing or not ready yet."
            )
        }

        val packageDirectory = packageDirectory(layoutRoot)
        if (Files.exists(packageDirectory)) {
            return LocalAikenProbe(
                state = LocalAikenState.BROKEN,
                details = "The local Aiken package exists, but the packaged binary is missing."
            )
        }

        return LocalAikenProbe(LocalAikenState.MISSING)
    }

    fun resolvePreferredAikenExecutable(project: Project?, projectDirectory: String? = project?.basePath): String {
        val settings = project?.service<AikenProjectToolchainSettings>()
        val fallbackGlobal = settings?.getGlobalAikenCommand()?.trim().orEmpty().ifEmpty {
            if (SystemInfo.isWindows) "aiken.exe" else "aiken"
        }
        if (settings?.getMode() == AikenToolchainMode.GLOBAL) {
            return fallbackGlobal
        }

        return resolveProjectLocalAikenExecutable(projectDirectory)
            ?: fallbackGlobal
    }

    fun resolveDesiredLocalAikenVersion(project: Project?, projectDirectory: String?): String {
        val fromManifest =
            projectDirectory
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Path.of(it) }
                ?.let(::readCompilerVersionFromManifest)
        if (!fromManifest.isNullOrBlank()) {
            return fromManifest
        }

        val settings = project?.service<AikenProjectToolchainSettings>()
        return settings?.getLocalAikenVersion() ?: DEFAULT_AIKEN_VERSION
    }

    internal fun readCompilerVersionFromManifest(projectDirectory: Path): String? {
        val manifestPath = projectDirectory.resolve("aiken.toml")
        if (!Files.isRegularFile(manifestPath)) return null
        val compilerVersion =
            AIKEN_TOML_COMPILER_REGEX
                .find(Files.readString(manifestPath, StandardCharsets.UTF_8))
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return normalizeRequestedVersion(compilerVersion)
    }

    internal fun installedVersionMatchesRequested(
        probe: LocalAikenProbe,
        requestedVersion: String
    ): Boolean {
        if (probe.state != LocalAikenState.HEALTHY) return false

        val normalizedRequested = normalizeRequestedVersion(requestedVersion)
        if (normalizedRequested == DEFAULT_AIKEN_VERSION) {
            return true
        }

        val requestedSemantic = AikenCliCompatibility.SemanticVersion.parse(normalizedRequested) ?: return false
        return probe.resolvedVersion == requestedSemantic
    }

    fun cleanupLegacyToolchainManifest(projectDirectory: Path): Boolean {
        var changed = false
        for (root in listOf(projectDirectory, localToolchainRoot(projectDirectory))) {
            val manifestPath = root.resolve("package.json")
            if (!Files.isRegularFile(manifestPath)) {
                continue
            }

            val manifest =
                runCatching {
                    JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8)).asJsonObject
                }.getOrNull() ?: continue

            if (!looksLikeManagedToolchainManifest(manifest)) {
                continue
            }

            Files.deleteIfExists(manifestPath)
            Files.deleteIfExists(root.resolve("package-lock.json"))
            changed = true
        }
        return changed
    }

    private fun fetchAvailableVersionsBlocking(project: Project, baseDirectory: VirtualFile?): VersionCatalog {
        runCatching {
            LOG.info("Attempting to load Aiken versions from npm registry")
            return fetchVersionCatalogFromRegistry().also {
                LOG.info("Loaded Aiken versions from npm registry")
            }
        }.onFailure {
            LOG.warn("Loading Aiken versions from npm registry failed, falling back to npm view", it)
        }

        val workingDirectory = baseDirectory?.path?.let { Path.of(it) }
            ?: project.basePath?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"))

        LOG.info("Attempting to load Aiken versions via npm view from $workingDirectory")
        val latestResult = runNpmCommand(
            project = project,
            workingDirectory = workingDirectory,
            commandName = "VIEW",
            arguments = listOf(AIKEN_NPM_PACKAGE, "dist-tags.latest", "--json")
        )
        if (latestResult.exitCode != 0) {
            throw IllegalStateException(latestResult.output.ifBlank { "npm view dist-tags.latest failed with exit code ${latestResult.exitCode}" })
        }

        val versionsResult = runNpmCommand(
            project = project,
            workingDirectory = workingDirectory,
            commandName = "VIEW",
            arguments = listOf(AIKEN_NPM_PACKAGE, "versions", "--json")
        )
        if (versionsResult.exitCode != 0) {
            throw IllegalStateException(versionsResult.output.ifBlank { "npm view versions failed with exit code ${versionsResult.exitCode}" })
        }

        return parseVersionCatalog(latestResult.output, versionsResult.output).also {
            LOG.info("Loaded Aiken versions via npm view; latest=${it.latest}, count=${it.versions.size}")
        }
    }

    internal fun parseRegistryVersionCatalog(rawJson: String): VersionCatalog {
        val root = JsonParser.parseString(rawJson).asJsonObject
        val latestFromRegistry = root.getAsJsonObject("dist-tags")
            ?.get("latest")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?: DEFAULT_AIKEN_VERSION

        val versions = LinkedHashSet<String>()
        root.getAsJsonObject("versions")
            ?.keySet()
            ?.let(::sortVersionsDescending)
            ?.filter(::isSupportedAikenVersion)
            ?.forEach { versions += it }

        val latest =
            latestFromRegistry.takeIf(::isSupportedAikenVersion)
                ?: versions.firstOrNull()
                ?: DEFAULT_AIKEN_VERSION
        if (latest != DEFAULT_AIKEN_VERSION) {
            versions += latest
        }

        return VersionCatalog(latest, versions.toList())
    }

    internal fun parseVersionCatalog(latestJson: String, versionsJson: String): VersionCatalog {
        val latestElement = JsonParser.parseString(latestJson)
        val latestFromNpm = when {
            latestElement.isJsonPrimitive -> latestElement.asString
            latestElement.isJsonObject -> latestElement.asJsonObject
                .get("latest")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?: latestElement.asJsonObject
                    .get("version")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                ?: DEFAULT_AIKEN_VERSION
            else -> DEFAULT_AIKEN_VERSION
        }

        val versions = LinkedHashSet<String>()

        val versionsElement = JsonParser.parseString(versionsJson)
        when {
            versionsElement.isJsonArray -> {
                versionsElement.asJsonArray
                    .mapNotNull { element -> element.takeUnless { it.isJsonNull }?.asString }
                    .let(::sortVersionsDescending)
                    .filter(::isSupportedAikenVersion)
                    .forEach { versions += it }
            }

            versionsElement.isJsonObject -> {
                versionsElement.asJsonObject
                    .getAsJsonArray("versions")
                    ?.mapNotNull { element -> element.takeUnless { it.isJsonNull }?.asString }
                    ?.let(::sortVersionsDescending)
                    ?.filter(::isSupportedAikenVersion)
                    ?.forEach { versions += it }
            }
        }

        val latest =
            latestFromNpm.takeIf(::isSupportedAikenVersion)
                ?: versions.firstOrNull()
                ?: DEFAULT_AIKEN_VERSION
        if (latest != DEFAULT_AIKEN_VERSION) {
            versions += latest
        }

        return VersionCatalog(latest, versions.toList())
    }

    private fun fetchVersionCatalogFromRegistry(): VersionCatalog {
        val packagePath = URLEncoder.encode(AIKEN_NPM_PACKAGE, StandardCharsets.UTF_8)
        val rawJson = HttpRequests.request("$NPM_REGISTRY_URL$packagePath")
            .productNameAsUserAgent()
            .connectTimeout(HTTP_TIMEOUT_MILLIS)
            .readTimeout(HTTP_TIMEOUT_MILLIS)
            .connect { request -> request.readString() }
        return parseRegistryVersionCatalog(rawJson)
    }

    internal fun sortVersionsDescending(versions: Collection<String>): List<String> =
        versions
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith { left, right ->
                val leftVersion = AikenCliCompatibility.SemanticVersion.parse(left.removePrefix("v"))
                val rightVersion = AikenCliCompatibility.SemanticVersion.parse(right.removePrefix("v"))
                when {
                    leftVersion != null && rightVersion != null -> rightVersion.compareTo(leftVersion)
                    leftVersion != null -> -1
                    rightVersion != null -> 1
                    else -> right.compareTo(left)
                }
            }
            .toList()

    internal fun prependPathEntry(environment: MutableMap<String, String>, entry: String) {
        val trimmedEntry = entry.trim()
        if (trimmedEntry.isEmpty()) return

        val pathKey =
            environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) }
                ?: if (SystemInfo.isWindows) "Path" else "PATH"
        val separator = File.pathSeparator
        val normalizedEntry = normalizePathEntry(trimmedEntry)
        val existingEntries =
            environment[pathKey]
                .orEmpty()
                .split(separator)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { normalizePathEntry(it) == normalizedEntry }

        environment[pathKey] = buildString {
            append(trimmedEntry)
            if (existingEntries.isNotEmpty()) {
                append(separator)
                append(existingEntries.joinToString(separator))
            }
        }
    }

    private fun normalizePathEntry(value: String): String =
        if (SystemInfo.isWindows) value.lowercase() else value

    private fun runCommandLine(commandLine: GeneralCommandLine, timeoutSeconds: Long): ProcessResult {
        val process = commandLine.createProcess()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Timed out while running ${commandLine.commandLineString}")
        }
        val output = buildString {
            append(process.inputStream.readBytes().toString(StandardCharsets.UTF_8))
            append(process.errorStream.readBytes().toString(StandardCharsets.UTF_8))
        }.trim()
        val exitCode = process.exitValue()
        return ProcessResult(exitCode, output)
    }

    private fun runProcess(workingDirectory: File, commandLine: List<String>, timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS): ProcessResult {
        val process = ProcessBuilder(commandLine)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Timed out while running ${commandLine.joinToString(" ")}")
        }
        val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
        val exitCode = process.exitValue()
        return ProcessResult(exitCode, output)
    }

    private fun isExecutableUsable(command: String): Boolean =
        try {
            runProcess(
                File(System.getProperty("user.home")),
                listOf(command, "--version"),
                PROCESS_TIMEOUT_SECONDS
            ).exitCode == 0
        } catch (_: Exception) {
            false
        }

    private fun localToolchainRoot(projectRoot: Path): Path =
        projectRoot.resolve(LOCAL_TOOLCHAIN_DIRECTORY)

    private fun packageDirectory(layoutRoot: Path): Path =
        layoutRoot
            .resolve("node_modules")
            .resolve("@aiken-lang")
            .resolve("aiken")

    private fun realBinaryCandidates(layoutRoot: Path): Sequence<Path> {
        val binDirectory = packageDirectory(layoutRoot)
            .resolve("node_modules")
            .resolve(".bin_real")
        val candidates = if (SystemInfo.isWindows) {
            listOf("aiken.exe", "aiken.cmd", "aiken.bat")
        } else {
            listOf("aiken")
        }
        return candidates.asSequence().map { binDirectory.resolve(it) }
    }

    private fun wrapperCandidates(layoutRoot: Path): Sequence<Path> {
        val binDirectory = layoutRoot.resolve("node_modules").resolve(".bin")
        val candidates = if (SystemInfo.isWindows) {
            listOf("aiken.cmd", "aiken.exe", "aiken.bat")
        } else {
            listOf("aiken")
        }
        return candidates.asSequence().map { binDirectory.resolve(it) }
    }

    private fun timeoutForNpmCommand(commandName: String): Long =
        when (commandName.uppercase()) {
            "EXEC" -> NPM_EXEC_TIMEOUT_SECONDS
            "INSTALL" -> NPM_INSTALL_TIMEOUT_SECONDS
            "VIEW" -> NPM_VIEW_TIMEOUT_SECONDS
            else -> PROCESS_TIMEOUT_SECONDS
        }

    private fun resolveNpmFromLoginShell(): String? {
        if (SystemInfo.isWindows) {
            return null
        }

        val shells = buildList {
            val envShell = System.getenv("SHELL")?.trim().takeUnless { it.isNullOrEmpty() }
            if (envShell != null) add(envShell)
            add("/bin/bash")
            add("/bin/zsh")
        }.distinct()

        for (shell in shells) {
            val resolved = runCatching {
                val result = runProcess(
                    File(System.getProperty("user.home")),
                    listOf(shell, "-lc", "command -v npm")
                )
                if (result.exitCode == 0) {
                    result.output.lineSequence().firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
                } else {
                    null
                }
            }.getOrNull()

            if (resolved != null && isExecutableUsable(resolved)) {
                return resolved
            }
        }

        return null
    }

    private fun openNodeSettings(project: Project): Boolean =
        runCatching {
            val targetProject = project.takeUnless { it.isDisposed } ?: ProjectManager.getInstance().defaultProject
            ShowSettingsUtil.getInstance().showSettingsDialog(targetProject, "Node.js")
            true
        }.getOrDefault(false)

    private fun systemNpmExecutable(): String =
        if (SystemInfo.isWindows) "npm.cmd" else "npm"

    private object IdeNodeIntegration {
        private val interpreterManagerClass = loadClass("com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager")
        private val interpreterFieldClass = loadClass("com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterField")
        private val interpretersDialogClass = loadClass("com.intellij.javascript.nodejs.interpreter.NodeJsInterpretersDialog")
        private val npmManagerClass = loadClass("com.intellij.javascript.nodejs.npm.NpmManager")
        private val npmUtilClass = loadClass("com.intellij.javascript.nodejs.npm.NpmUtil")
        private val npmCommandClass = loadClass("com.intellij.lang.javascript.buildTools.npm.rc.NpmCommand")
        private val nodeInterpreterClass = loadClass("com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter")
        private val nodePackageClass = loadClass("com.intellij.javascript.nodejs.util.NodePackage")

        fun isAvailable(): Boolean =
            interpreterManagerClass != null &&
                interpreterFieldClass != null &&
                interpretersDialogClass != null &&
                npmManagerClass != null &&
                npmUtilClass != null &&
                npmCommandClass != null &&
                nodeInterpreterClass != null &&
                nodePackageClass != null

        fun describeAvailability(project: Project): String? {
            val toolchain = resolveToolchain(project) ?: return null
            val presentableName = toolchain.interpreter.javaClass.methods
                .firstOrNull { it.name == "getPresentableName" && it.parameterCount == 0 }
                ?.invoke(toolchain.interpreter)
                ?.toString()
                .orEmpty()
            val suffix = presentableName.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            return "Configured via IDE Node.js$suffix"
        }

        fun openInterpreterDialog(project: Project): Boolean {
            if (!isAvailable()) return false

            return runCatching {
                val targetProject = project.takeUnless { it.isDisposed } ?: ProjectManager.getInstance().defaultProject
                val getInstance = interpreterManagerClass!!.getMethod("getInstance", Project::class.java)
                val interpreterManager = getInstance.invoke(null, targetProject)
                val getInterpreterRef = interpreterManagerClass.getMethod("getInterpreterRef")
                val currentRef = getInterpreterRef.invoke(interpreterManager)

                val field = interpreterFieldClass!!.getConstructor(Project::class.java).newInstance(targetProject)
                interpreterFieldClass.getMethod("setInterpreterRef", currentRef.javaClass).invoke(field, currentRef)

                val dialog = interpretersDialogClass!!.getConstructor(interpreterFieldClass).newInstance(field)
                val selectedRefWrapper = interpretersDialogClass
                    .getMethod("showAndGetSelected", currentRef.javaClass)
                    .invoke(dialog, currentRef) ?: return false
                val selectedRef = selectedRefWrapper.javaClass.getMethod("get").invoke(selectedRefWrapper) ?: return false
                interpreterManagerClass.getMethod("setInterpreterRef", currentRef.javaClass).invoke(interpreterManager, selectedRef)
                true
            }.getOrDefault(false)
        }

        fun runNpmCommand(
            project: Project,
            workingDirectory: Path,
            commandName: String,
            arguments: List<String>,
            timeoutSeconds: Long
        ): ProcessResult? {
            if (!isAvailable()) return null

            return runCatching {
                val toolchain = resolveToolchain(project) ?: return null
                val npmCommand = npmCommandClass!!
                    .enumConstants
                    ?.firstOrNull { (it as? Enum<*>)?.name == commandName }
                    ?: return null
                val createCommandLine = npmUtilClass!!.getMethod(
                    "createNpmCommandLine",
                    Path::class.java,
                    nodeInterpreterClass,
                    nodePackageClass,
                    npmCommandClass,
                    List::class.java
                )
                val commandLine = createCommandLine.invoke(
                    null,
                    workingDirectory,
                    toolchain.interpreter,
                    toolchain.npmPackage,
                    npmCommand,
                    arguments
                ) as GeneralCommandLine
                runCommandLine(commandLine, timeoutSeconds)
            }.getOrNull()
        }

        private fun resolveToolchain(project: Project): IdeResolvedToolchain? {
            if (!isAvailable()) return null

            val candidates = linkedSetOf(project, ProjectManager.getInstance().defaultProject)
            for (candidateProject in candidates) {
                val interpreterManager = interpreterManagerClass!!.getMethod("getInstance", Project::class.java)
                    .invoke(null, candidateProject)
                val interpreter = interpreterManagerClass.getMethod("getInterpreter").invoke(interpreterManager) ?: continue

                val npmManager = npmManagerClass!!.getMethod("getInstance", Project::class.java).invoke(null, candidateProject)
                val npmPackage = npmManagerClass.getMethod("getPackage", nodeInterpreterClass).invoke(npmManager, interpreter)
                    ?: continue
                val valid = nodePackageClass!!.getMethod("isValid", Project::class.java, nodeInterpreterClass)
                    .invoke(npmPackage, project, interpreter) as? Boolean
                    ?: false
                if (valid) {
                    return IdeResolvedToolchain(interpreter, npmPackage)
                }
            }
            return null
        }

        private fun loadClass(fqcn: String): Class<*>? =
            runCatching { Class.forName(fqcn) }.getOrNull()

        private data class IdeResolvedToolchain(
            val interpreter: Any,
            val npmPackage: Any
        )
    }

    private fun looksLikeManagedToolchainManifest(manifest: com.google.gson.JsonObject): Boolean {
        val allowedKeys = setOf("name", "private", "devDependencies")
        if (manifest.entrySet().any { (key, _) -> key !in allowedKeys }) {
            return false
        }

        val privateValue = manifest.get("private")
        if (privateValue == null || !privateValue.isJsonPrimitive || !privateValue.asBoolean) {
            return false
        }

        val devDependencies = manifest.getAsJsonObject("devDependencies") ?: return false
        return devDependencies.entrySet().size == 1 && devDependencies.has(AIKEN_NPM_PACKAGE)
    }
}
