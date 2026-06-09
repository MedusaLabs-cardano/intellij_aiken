package com.medusalabs.aiken.run

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object AikenCliCompatibility {
    private const val PROCESS_TIMEOUT_SECONDS = 5L
    private val VERSION_REGEX = Regex("""\b(v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\b""")
    private val FLAG_REGEX = Regex("""--[A-Za-z0-9][A-Za-z0-9_-]*|-[A-Za-z]""")
    private val UNEXPECTED_ARGUMENT_REGEX = Regex("""unexpected argument '([^']+)' found""", RegexOption.IGNORE_CASE)
    private val supportCache = ConcurrentHashMap<String, CommandSupport>()

    data class CommandSupport(
        val command: AikenRunCommand,
        val versionText: String?,
        val version: SemanticVersion?,
        val helpFlags: Set<String>?
    ) {
        fun preferredToken(option: ManagedAikenOption): String? {
            if (!option.commands.contains(command)) return null

            val flags = helpFlags
            if (flags != null) {
                return option.preferredTokens.firstOrNull { flags.contains(it) }
            }

            if (!option.isSupportedIn(version)) {
                return null
            }

            return option.preferredTokens.first()
        }

        fun supports(option: ManagedAikenOption): Boolean = preferredToken(option) != null

        fun supportsStructuredCheckJson(): Boolean {
            if (command != AikenRunCommand.CHECK) return false
            val minimum = SemanticVersion.parse("1.1.6") ?: return false
            val actual = version ?: return false
            return actual >= minimum
        }
    }

    data class RetryPlan(
        val args: List<String>,
        val removedFlag: String
    )

    data class SanitizedArgs(
        val args: List<String>,
        val removedFlags: List<String>
    )

    internal enum class ManagedAikenOption(
        val valueArity: Int,
        val commands: Set<AikenRunCommand>,
        val preferredTokens: List<String>,
        val introducedIn: SemanticVersion? = null
    ) {
        DENY(
            valueArity = 0,
            commands = setOf(AikenRunCommand.BUILD, AikenRunCommand.CHECK),
            preferredTokens = listOf("--deny", "-D")
        ),
        SILENT(
            valueArity = 0,
            commands = setOf(AikenRunCommand.BUILD, AikenRunCommand.CHECK),
            preferredTokens = listOf("--silent", "-S"),
            introducedIn = SemanticVersion.parse("1.1.14")
        ),
        WATCH(
            valueArity = 0,
            commands = setOf(AikenRunCommand.BUILD, AikenRunCommand.CHECK),
            preferredTokens = listOf("--watch"),
            introducedIn = SemanticVersion.parse("1.0.21-alpha")
        ),
        UPLC(
            valueArity = 0,
            commands = setOf(AikenRunCommand.BUILD),
            preferredTokens = listOf("--uplc", "-u")
        ),
        ENV(
            valueArity = 1,
            commands = setOf(AikenRunCommand.BUILD, AikenRunCommand.CHECK),
            preferredTokens = listOf("--env"),
            introducedIn = SemanticVersion.parse("1.1.0")
        ),
        OUT(
            valueArity = 1,
            commands = setOf(AikenRunCommand.BUILD),
            preferredTokens = listOf("--out", "-o")
        ),
        TRACE_FILTER(
            valueArity = 1,
            commands = setOf(AikenRunCommand.BUILD, AikenRunCommand.CHECK),
            // Prefer the legacy spelling when help probing is unavailable. It is supported by
            // older Aiken releases and kept as an alias by newer ones.
            preferredTokens = listOf("--filter-traces", "--trace-filter", "--filter_traces", "--trace_filter", "-f"),
            introducedIn = SemanticVersion.parse("1.0.22-alpha")
        ),
        TRACE_LEVEL(
            valueArity = 1,
            commands = setOf(AikenRunCommand.BUILD, AikenRunCommand.CHECK),
            preferredTokens = listOf("--trace-level", "--trace_level", "-t"),
            introducedIn = SemanticVersion.parse("1.0.22-alpha")
        ),
        SKIP_TESTS(
            valueArity = 0,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--skip-tests", "-s"),
            introducedIn = SemanticVersion.parse("0.0.27")
        ),
        DEBUG(
            valueArity = 0,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--debug")
        ),
        SEED(
            valueArity = 1,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--seed"),
            introducedIn = SemanticVersion.parse("1.0.25-alpha")
        ),
        MAX_SUCCESS(
            valueArity = 1,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--max-success"),
            introducedIn = SemanticVersion.parse("1.0.25-alpha")
        ),
        PROPERTY_COVERAGE(
            valueArity = 1,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--property-coverage", "-P"),
            introducedIn = SemanticVersion.parse("1.1.17")
        ),
        MATCH_TESTS(
            valueArity = 1,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--match-tests", "-m")
        ),
        EXACT_MATCH(
            valueArity = 0,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--exact-match", "-e")
        ),
        PLAIN_NUMBERS(
            valueArity = 0,
            commands = setOf(AikenRunCommand.CHECK),
            preferredTokens = listOf("--plain-numbers"),
            introducedIn = SemanticVersion.parse("1.1.19")
        );

        fun matches(token: String): Boolean {
            val normalized = normalizeFlagToken(token)
            return preferredTokens.any { it == normalized }
        }

        fun isSupportedIn(version: SemanticVersion?): Boolean {
            val minimum = introducedIn ?: return true
            val actual = version ?: return true
            return actual >= minimum
        }

        companion object {
            fun find(command: AikenRunCommand, token: String): ManagedAikenOption? =
                entries.firstOrNull { option ->
                    option.commands.contains(command) && option.matches(token)
                }
        }
    }

    data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<PreReleaseIdentifier> = emptyList()
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
            compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

            if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
            if (preRelease.isEmpty()) return 1
            if (other.preRelease.isEmpty()) return -1

            val max = maxOf(preRelease.size, other.preRelease.size)
            for (index in 0 until max) {
                val left = preRelease.getOrNull(index)
                val right = other.preRelease.getOrNull(index)
                if (left == null) return -1
                if (right == null) return 1
                val compared = left.compareTo(right)
                if (compared != 0) return compared
            }
            return 0
        }

        companion object {
            fun parse(raw: String?): SemanticVersion? {
                val normalized = raw?.trim()?.removePrefix("v").orEmpty()
                if (normalized.isEmpty()) return null
                val coreAndBuild = normalized.substringBefore('+')
                val core = coreAndBuild.substringBefore('-')
                val preReleaseRaw =
                    if ('-' in coreAndBuild) {
                        coreAndBuild.substringAfter('-', "")
                    } else {
                        ""
                    }
                val coreParts = core.split('.')
                if (coreParts.size != 3) return null
                val major = coreParts[0].toIntOrNull() ?: return null
                val minor = coreParts[1].toIntOrNull() ?: return null
                val patch = coreParts[2].toIntOrNull() ?: return null
                val preRelease =
                    if (preReleaseRaw.isBlank()) {
                        emptyList()
                    } else {
                        preReleaseRaw.split('.').map(PreReleaseIdentifier::fromRaw)
                    }
                return SemanticVersion(major, minor, patch, preRelease)
            }
        }
    }

    data class PreReleaseIdentifier(
        val numeric: Int?,
        val text: String?
    ) : Comparable<PreReleaseIdentifier> {
        override fun compareTo(other: PreReleaseIdentifier): Int {
            if (numeric != null && other.numeric != null) {
                return compareValues(numeric, other.numeric)
            }
            if (numeric != null) return -1
            if (other.numeric != null) return 1
            return text.orEmpty().compareTo(other.text.orEmpty())
        }

        companion object {
            fun fromRaw(raw: String): PreReleaseIdentifier {
                val numeric = raw.toIntOrNull()
                return if (numeric != null) {
                    PreReleaseIdentifier(numeric = numeric, text = null)
                } else {
                    PreReleaseIdentifier(numeric = null, text = raw.lowercase())
                }
            }
        }
    }

    fun resolveCommandSupport(
        executable: String,
        workDir: String?,
        command: AikenRunCommand,
        commandTokens: List<String>
    ): CommandSupport {
        val versionText = runProcess(workDir, listOf(executable, "--version"))?.takeIf { it.exitCode == 0 }?.output
        val parsedVersion = extractVersion(versionText)
        val cacheKey = buildString {
            append(executable)
            append('|')
            append(command.name)
            append('|')
            append(parsedVersion?.let { "${it.major}.${it.minor}.${it.patch}-${it.preRelease}" } ?: versionText.orEmpty())
        }

        return supportCache.computeIfAbsent(cacheKey) {
            val helpFlags =
                runProcess(workDir, buildList {
                    add(executable)
                    addAll(commandTokens)
                    add("--help")
                })?.takeIf { it.exitCode == 0 }?.output?.let(::parseSupportedFlags)
            CommandSupport(
                command = command,
                versionText = versionText,
                version = parsedVersion,
                helpFlags = helpFlags
            )
        }
    }

    fun buildCommandSupportForTest(
        command: AikenRunCommand,
        versionText: String?,
        helpText: String?
    ): CommandSupport =
        CommandSupport(
            command = command,
            versionText = versionText,
            version = extractVersion(versionText),
            helpFlags = helpText?.let(::parseSupportedFlags)
        )

    fun parseSupportedFlags(helpText: String): Set<String> =
        FLAG_REGEX.findAll(helpText).map { it.value }.toCollection(LinkedHashSet())

    fun extractVersion(versionText: String?): SemanticVersion? {
        val match = VERSION_REGEX.find(versionText.orEmpty()) ?: return null
        return SemanticVersion.parse(match.groupValues[1])
    }

    fun sanitizeUnsupportedManagedFlags(args: List<String>, support: CommandSupport): SanitizedArgs {
        val sanitized = ArrayList<String>(args.size)
        val removed = ArrayList<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val option = ManagedAikenOption.find(support.command, token)
            if (option != null && !support.supports(option)) {
                removed += normalizeFlagToken(token)
                index += 1
                if (!token.contains('=') && option.valueArity > 0 && index < args.size) {
                    index += option.valueArity
                }
                continue
            }
            sanitized += token
            index += 1
        }
        return SanitizedArgs(
            args = sanitized,
            removedFlags = removed
        )
    }

    fun buildRetryPlan(args: List<String>, support: CommandSupport, rawOutput: String): RetryPlan? {
        val problematicFlag = extractUnexpectedArgument(rawOutput) ?: return null
        val option = ManagedAikenOption.find(support.command, problematicFlag)
        val updated =
            when {
                option != null -> removeOptionTokens(args, problematicFlag, option)
                else -> removeRawFlagToken(args, problematicFlag)
            }
        if (updated == null || updated == args) return null
        return RetryPlan(
            args = updated,
            removedFlag = problematicFlag
        )
    }

    fun extractUnexpectedArgument(rawOutput: String): String? {
        val normalized = rawOutput.replace("\u001B\\[[;\\d]*m".toRegex(), "")
        return UNEXPECTED_ARGUMENT_REGEX.find(normalized)?.groupValues?.getOrNull(1)
    }

    private fun removeOptionTokens(
        args: List<String>,
        unexpectedFlag: String,
        option: ManagedAikenOption
    ): List<String>? {
        val result = ArrayList<String>(args.size)
        var removed = false
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val shouldRemove =
                if (!removed) {
                    val normalized = normalizeFlagToken(token)
                    normalized == normalizeFlagToken(unexpectedFlag) ||
                        option.preferredTokens.any { it == normalized }
                } else {
                    false
                }

            if (shouldRemove) {
                removed = true
                index += 1
                if (!token.contains('=') && option.valueArity > 0 && index < args.size) {
                    index += option.valueArity
                }
                continue
            }

            result += token
            index += 1
        }

        return if (removed) result else null
    }

    private fun removeRawFlagToken(args: List<String>, unexpectedFlag: String): List<String>? {
        val normalizedUnexpected = normalizeFlagToken(unexpectedFlag)
        var removed = false
        val result =
            args.filterNot { token ->
                if (!removed && normalizeFlagToken(token) == normalizedUnexpected) {
                    removed = true
                    true
                } else {
                    false
                }
            }
        return if (removed) result else null
    }

    private fun normalizeFlagToken(token: String): String = token.substringBefore('=')

    private fun runProcess(workDir: String?, commandLine: List<String>): ProcessResult? {
        return try {
            val processBuilder = ProcessBuilder(commandLine)
            if (!workDir.isNullOrBlank()) {
                processBuilder.directory(File(workDir))
            }
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
            ProcessResult(process.exitValue(), output)
        } catch (_: Exception) {
            null
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String
    )
}
