package com.medusalabs.aiken.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.medusalabs.aiken.run.AikenCliCompatibility
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object AikenStdlibCatalog {
    private const val BUNDLED_CATALOG_RESOURCE = "configs/aiken-stdlib-catalog.txt"
    private const val STDLIB_TAGS_URL = "https://api.github.com/repos/aiken-lang/stdlib/tags?per_page=100"
    private const val STDLIB_README_URL = "https://raw.githubusercontent.com/aiken-lang/stdlib/main/README.md"
    private const val FETCH_TIMEOUT_SECONDS = 5L
    private val LOG = Logger.getInstance(AikenStdlibCatalog::class.java)
    @Volatile
    private var cachedCatalog: Catalog? = null

    data class Catalog(
        val tags: List<String>,
        val compatibilityRules: List<CompatibilityRule>
    ) {
        fun compatibleTagsFor(aikenVersionText: String?): List<String> {
            val aikenVersion = AikenCliCompatibility.extractVersion(aikenVersionText)
                ?: return tags
            val matchingRules =
                compatibilityRules.filter { rule ->
                    rule.aikenRequirement.matches(aikenVersion)
                }
            if (matchingRules.isEmpty()) {
                return emptyList()
            }
            val selectedRules = matchingRules.selectMostSpecificFor(aikenVersion)
            return tags.filter { tag ->
                val stdlibVersion = resolveStdlibVersion(tag) ?: return@filter false
                selectedRules.any { rule ->
                    rule.stdlibRange.matches(stdlibVersion) && rule.aikenRequirement.matches(aikenVersion)
                }
            }
        }

        fun recommendedTagFor(aikenVersionText: String?): String? {
            val compatible = compatibleTagsFor(aikenVersionText)
            return compatible.firstOrNull()
        }

        fun plutusVersionFor(tag: String): String? {
            val stdlibVersion = resolveStdlibVersion(tag) ?: return null
            return compatibilityRules.firstOrNull { it.stdlibRange.matches(stdlibVersion) }?.plutusVersion
        }

        private fun resolveStdlibVersion(tag: String): AikenCliCompatibility.SemanticVersion? {
            val exactVersions = tags.associateWith { raw ->
                AikenCliCompatibility.SemanticVersion.parse(raw.removePrefix("v"))
            }
            exactVersions[tag]?.let { return it }

            val normalized = tag.removePrefix("v")
            val exactCandidates =
                exactVersions.values
                    .filterNotNull()
                    .sortedDescending()

            val majorOnly = normalized.toIntOrNull()
            if (majorOnly != null) {
                return exactCandidates.firstOrNull { it.major == majorOnly }
            }

            val parts = normalized.split('.')
            if (parts.size == 2) {
                val major = parts[0].toIntOrNull()
                val minor = parts[1].toIntOrNull()
                if (major != null && minor != null) {
                    return exactCandidates.firstOrNull { it.major == major && it.minor == minor }
                }
            }

            return null
        }
    }

    data class CompatibilityRule(
        val stdlibRange: VersionRange,
        val aikenRequirement: VersionRequirement,
        val plutusVersion: String
    )

    data class VersionRange(
        val predicates: List<VersionPredicate>
    ) {
        fun matches(version: AikenCliCompatibility.SemanticVersion): Boolean =
            predicates.all { it.matches(version) }

        companion object {
            fun parse(raw: String): VersionRange {
                val predicates =
                    raw.split("&&")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map(VersionPredicate::parse)
                return VersionRange(predicates)
            }
        }
    }

    data class VersionRequirement(
        val alternatives: List<VersionRange>
    ) {
        fun matches(version: AikenCliCompatibility.SemanticVersion): Boolean =
            alternatives.any { it.matches(version) }

        fun specificityFor(version: AikenCliCompatibility.SemanticVersion): RequirementSpecificity? =
            alternatives
                .filter { it.matches(version) }
                .mapNotNull { it.specificity() }
                .maxOrNull()

        companion object {
            fun parse(raw: String): VersionRequirement {
                val alternatives =
                    raw.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { clause ->
                            if (clause.contains(">") || clause.contains("<")) {
                                VersionRange.parse(clause)
                            } else {
                                VersionRange(listOf(VersionPredicate(Operator.EQ, parseVersion(clause))))
                            }
                        }
                return VersionRequirement(alternatives)
            }
        }
    }

    data class RequirementSpecificity(
        val exact: Boolean,
        val lowerBound: AikenCliCompatibility.SemanticVersion?,
        val upperBound: AikenCliCompatibility.SemanticVersion?
    ) : Comparable<RequirementSpecificity> {
        override fun compareTo(other: RequirementSpecificity): Int {
            if (exact != other.exact) {
                return if (exact) 1 else -1
            }

            val lowerBoundOrder =
                when {
                    lowerBound == null && other.lowerBound == null -> 0
                    lowerBound == null -> -1
                    other.lowerBound == null -> 1
                    else -> lowerBound.compareTo(other.lowerBound)
                }
            if (lowerBoundOrder != 0) {
                return lowerBoundOrder
            }

            return when {
                upperBound == null && other.upperBound == null -> 0
                upperBound == null -> -1
                other.upperBound == null -> 1
                else -> other.upperBound.compareTo(upperBound)
            }
        }
    }

    data class VersionPredicate(
        val operator: Operator,
        val version: AikenCliCompatibility.SemanticVersion
    ) {
        fun matches(actual: AikenCliCompatibility.SemanticVersion): Boolean =
            when (operator) {
                Operator.GT -> actual > version
                Operator.GTE -> actual >= version
                Operator.LT -> actual < version
                Operator.LTE -> actual <= version
                Operator.EQ -> actual == version
            }

        companion object {
            fun parse(raw: String): VersionPredicate {
                val trimmed = raw.trim()
                val operator =
                    when {
                        trimmed.startsWith(">=") -> Operator.GTE
                        trimmed.startsWith("<=") -> Operator.LTE
                        trimmed.startsWith(">") -> Operator.GT
                        trimmed.startsWith("<") -> Operator.LT
                        else -> Operator.EQ
                    }
                val versionText =
                    when (operator) {
                        Operator.GTE, Operator.LTE -> trimmed.drop(2).trim()
                        Operator.GT, Operator.LT -> trimmed.drop(1).trim()
                        Operator.EQ -> trimmed
                    }
                return VersionPredicate(operator, parseVersion(versionText))
            }
        }
    }

    enum class Operator {
        GT,
        GTE,
        LT,
        LTE,
        EQ
    }

    private fun VersionRange.specificity(): RequirementSpecificity? {
        var exact = false
        var lowerBound: AikenCliCompatibility.SemanticVersion? = null
        var upperBound: AikenCliCompatibility.SemanticVersion? = null

        for (predicate in predicates) {
            when (predicate.operator) {
                Operator.EQ -> {
                    exact = true
                    lowerBound = predicate.version
                    upperBound = predicate.version
                }
                Operator.GT, Operator.GTE -> {
                    val current = lowerBound
                    if (current == null || predicate.version > current) {
                        lowerBound = predicate.version
                    }
                }
                Operator.LT, Operator.LTE -> {
                    val current = upperBound
                    if (current == null || predicate.version < current) {
                        upperBound = predicate.version
                    }
                }
            }
        }

        return RequirementSpecificity(exact, lowerBound, upperBound)
    }

    private fun List<CompatibilityRule>.selectMostSpecificFor(
        version: AikenCliCompatibility.SemanticVersion
    ): List<CompatibilityRule> {
        val ranked =
            mapNotNull { rule ->
                rule.aikenRequirement.specificityFor(version)?.let { specificity ->
                    rule to specificity
                }
            }
        val bestSpecificity = ranked.maxOfOrNull { it.second } ?: return emptyList()
        return ranked
            .filter { it.second == bestSpecificity }
            .map { it.first }
    }

    fun fetchCatalog(): CompletableFuture<Catalog> {
        cachedCatalog?.let { return CompletableFuture.completedFuture(it) }
        val future = CompletableFuture<Catalog>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val catalog = loadCatalogBlocking(preferRemote = true)
                cachedCatalog = catalog
                future.complete(catalog)
            } catch (t: Throwable) {
                LOG.warn("Failed to load stdlib catalog", t)
                future.completeExceptionally(t)
            }
        }
        return future.orTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    internal fun loadCatalogBlocking(stdlibRoot: Path? = null, preferRemote: Boolean = false): Catalog {
        if (preferRemote) {
            loadRemoteCatalog()?.let {
                LOG.info("Loaded stdlib catalog from aiken-lang/stdlib GitHub repository")
                return it
            }
        }
        loadBundledCatalog()?.let {
            LOG.info("Loaded stdlib catalog from bundled resource $BUNDLED_CATALOG_RESOURCE")
            return it
        }
        val resolvedStdlibRoot = stdlibRoot ?: locateStdlibRoot()
        LOG.info("Loading stdlib catalog from development extras/stdlib fallback at $resolvedStdlibRoot")
        val tags = loadTags(resolvedStdlibRoot)
        val rules = parseCompatibilityRules(Files.readString(resolvedStdlibRoot.resolve("README.md"), StandardCharsets.UTF_8))
        return Catalog(
            tags = filterSupportedTags(sortTags(tags), rules),
            compatibilityRules = rules
        )
    }

    internal fun parseGitHubTagsResponse(response: String): List<String> =
        Regex(""""name"\s*:\s*"([^"]+)"""")
            .findAll(response)
            .mapNotNull { match -> canonicalizeTag(match.groupValues[1]) }
            .distinct()
            .toList()

    internal fun buildRemoteCatalog(tagsResponse: String, readme: String): Catalog {
        val tags = parseGitHubTagsResponse(tagsResponse)
        if (tags.isEmpty()) {
            throw IllegalStateException("GitHub stdlib tag response contained no semantic tags")
        }

        val rules = parseCompatibilityRules(readme)
        return Catalog(
            tags = filterSupportedTags(sortTags(tags), rules),
            compatibilityRules = rules
        )
    }

    internal fun parseBundledCatalog(snapshot: String): Catalog {
        val tags = ArrayList<String>()
        val rules = ArrayList<CompatibilityRule>()

        snapshot.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                when {
                    line.startsWith("tag=") -> canonicalizeTag(line.removePrefix("tag=").trim())?.let(tags::add)
                    line.startsWith("rule=") -> {
                        val parts = line.removePrefix("rule=").split('|', limit = 3).map { it.trim() }
                        if (parts.size != 3) {
                            throw IllegalStateException("Invalid bundled stdlib compatibility rule: $line")
                        }
                        rules += CompatibilityRule(
                            stdlibRange = VersionRange.parse(parts[0]),
                            aikenRequirement = VersionRequirement.parse(parts[1]),
                            plutusVersion = parts[2].uppercase()
                        )
                    }
                }
            }

        if (tags.isEmpty()) {
            throw IllegalStateException("Bundled stdlib catalog contains no tags")
        }
        if (rules.isEmpty()) {
            throw IllegalStateException("Bundled stdlib catalog contains no compatibility rules")
        }

        return Catalog(
            tags = filterSupportedTags(sortTags(tags.distinct()), rules),
            compatibilityRules = rules
        )
    }

    internal fun parseCompatibilityRules(readme: String): List<CompatibilityRule> {
        val lines = readme.lineSequence().toList()
        val startIndex = lines.indexOfFirst { it.trim() == "## Compatibility" }
        if (startIndex == -1) {
            throw IllegalStateException("Couldn't find stdlib compatibility matrix in README.md")
        }

        val tableHeaderIndex =
            lines.drop(startIndex + 1).indexOfFirst { line ->
                val normalized = line.lowercase()
                '|' in line &&
                    "stdlib" in normalized &&
                    "aiken" in normalized &&
                    "plutus" in normalized
            }.takeIf { it >= 0 }?.let { startIndex + 1 + it }
                ?: throw IllegalStateException("Couldn't find stdlib compatibility table header in README.md")
        val tableStartIndex =
            (tableHeaderIndex + 1).takeIf { it < lines.size && isMarkdownTableSeparator(lines[it]) }
                ?.plus(1)
                ?: throw IllegalStateException("Couldn't find stdlib compatibility table separator in README.md")

        val rules = ArrayList<CompatibilityRule>()
        for (line in lines.drop(tableStartIndex)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) break
            if ('|' !in trimmed) break
            val columns = trimmed.split('|').map { it.trim() }
            if (columns.size < 3) continue

            val stdlibRangeText = extractCode(columns[0]).joinToString(", ")
            val aikenRequirementText = extractCode(columns[1]).joinToString(", ")
            val plutusVersion = extractCode(columns[2]).firstOrNull()?.uppercase() ?: columns[2].uppercase()
            if (stdlibRangeText.isEmpty() || aikenRequirementText.isEmpty() || plutusVersion.isEmpty()) continue
            if (plutusVersion == "V2") continue

            rules += CompatibilityRule(
                stdlibRange = VersionRange.parse(stdlibRangeText),
                aikenRequirement = VersionRequirement.parse(aikenRequirementText),
                plutusVersion = plutusVersion
            )
        }

        if (rules.isEmpty()) {
            throw IllegalStateException("Parsed zero stdlib compatibility rules from README.md")
        }

        return rules
    }

    private fun isMarkdownTableSeparator(line: String): Boolean {
        val columns = line.trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return columns.isNotEmpty() && columns.all { column ->
            column.all { it == '-' || it == ':' }
        }
    }

    internal fun detectGlobalAikenVersion(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        return runProcess(listOf(trimmed, "--version"))?.output
            ?.takeIf { AikenCliCompatibility.extractVersion(it) != null }
    }

    private fun loadBundledCatalog(): Catalog? {
        val input = AikenStdlibCatalog::class.java.getResourceAsStream("/$BUNDLED_CATALOG_RESOURCE")
            ?: return null
        return try {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                parseBundledCatalog(reader.readText())
            }
        } catch (t: Throwable) {
            LOG.warn("Failed to load bundled stdlib catalog snapshot from $BUNDLED_CATALOG_RESOURCE", t)
            null
        }
    }

    private fun loadRemoteCatalog(): Catalog? {
        return try {
            buildRemoteCatalog(
                tagsResponse = fetchText(STDLIB_TAGS_URL),
                readme = fetchText(STDLIB_README_URL)
            )
        } catch (t: Throwable) {
            LOG.warn("Failed to load stdlib catalog from GitHub; falling back to bundled snapshot", t)
            null
        }
    }

    private fun fetchText(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TimeUnit.SECONDS.toMillis(FETCH_TIMEOUT_SECONDS).toInt()
        connection.readTimeout = TimeUnit.SECONDS.toMillis(FETCH_TIMEOUT_SECONDS).toInt()
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json, text/plain;q=0.9, */*;q=0.1")
        connection.setRequestProperty("User-Agent", "Aiken-IntelliJ-Plugin")

        val status = connection.responseCode
        if (status !in 200..299) {
            val message = connection.errorStream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            throw IllegalStateException("GET $url failed with HTTP $status. $message")
        }

        return connection.inputStream.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun locateStdlibRoot(): Path {
        val candidates = LinkedHashSet<Path>()
        runCatching { candidates.add(Paths.get("").toAbsolutePath()) }
        runCatching { candidates.add(Paths.get(System.getProperty("user.dir"))) }
        resolveCodeSourcePath()?.let { path ->
            var current: Path? = path
            while (current != null) {
                candidates.add(current)
                current = current.parent
            }
        }

        for (candidate in candidates) {
            var current: Path? = candidate
            while (current != null) {
                val stdlibRoot = current.resolve("extras").resolve("stdlib")
                if (Files.isRegularFile(stdlibRoot.resolve("README.md"))) {
                    return stdlibRoot
                }
                current = current.parent
            }
        }

        throw IllegalStateException("Unable to locate extras/stdlib")
    }

    private fun resolveCodeSourcePath(): Path? =
        try {
            val location = AikenStdlibCatalog::class.java.protectionDomain?.codeSource?.location ?: return null
            Paths.get(location.toURI()).toAbsolutePath()
        } catch (_: URISyntaxException) {
            null
        }

    private fun loadTags(stdlibRoot: Path): List<String> {
        val gitDir = stdlibRoot.resolve(".git")
        val tags = LinkedHashSet<String>()
        val refsTagsDir = gitDir.resolve("refs").resolve("tags")
        if (Files.isDirectory(refsTagsDir)) {
            Files.walk(refsTagsDir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    canonicalizeTag(refsTagsDir.relativize(path).toString().replace(File.separatorChar, '/'))?.let(tags::add)
                }
            }
        }

        val packedRefs = gitDir.resolve("packed-refs")
        if (Files.isRegularFile(packedRefs)) {
            Files.readAllLines(packedRefs, StandardCharsets.UTF_8).forEach { line ->
                if (line.startsWith("#") || line.startsWith("^")) return@forEach
                val tagRef = line.substringAfter(" refs/tags/", missingDelimiterValue = "")
                if (tagRef.isNotEmpty()) {
                    canonicalizeTag(tagRef.trim())?.let(tags::add)
                }
            }
        }

        if (tags.isEmpty()) {
            throw IllegalStateException("No stdlib git tags found in ${gitDir.toAbsolutePath()}")
        }

        return tags.toList()
    }

    private fun sortTags(tags: List<String>): List<String> {
        val exactVersions = HashMap<String, AikenCliCompatibility.SemanticVersion?>()
        tags.forEach { tag ->
            exactVersions[tag] = AikenCliCompatibility.SemanticVersion.parse(tag.removePrefix("v"))
        }

        fun resolvedVersion(tag: String): AikenCliCompatibility.SemanticVersion? {
            exactVersions[tag]?.let { return it }
            val normalized = tag.removePrefix("v")
            val exactCandidates = exactVersions.values.filterNotNull().sortedDescending()

            normalized.toIntOrNull()?.let { major ->
                return exactCandidates.firstOrNull { it.major == major }
            }

            val parts = normalized.split('.')
            if (parts.size == 2) {
                val major = parts[0].toIntOrNull()
                val minor = parts[1].toIntOrNull()
                if (major != null && minor != null) {
                    return exactCandidates.firstOrNull { it.major == major && it.minor == minor }
                }
            }

            return null
        }

        fun rank(tag: String): Int {
            val normalized = tag.removePrefix("v")
            val exact = AikenCliCompatibility.SemanticVersion.parse(normalized) != null
            return when {
                exact && tag.startsWith("v") -> 0
                exact -> 1
                tag.startsWith("v") -> 2
                else -> 3
            }
        }

        return tags.sortedWith { left, right ->
            val leftVersion = resolvedVersion(left)
            val rightVersion = resolvedVersion(right)
            val versionOrder = when {
                leftVersion == null && rightVersion == null -> 0
                leftVersion == null -> 1
                rightVersion == null -> -1
                else -> rightVersion.compareTo(leftVersion)
            }
            if (versionOrder != 0) {
                versionOrder
            } else {
                val rankOrder = rank(left).compareTo(rank(right))
                if (rankOrder != 0) {
                    rankOrder
                } else {
                    left.compareTo(right)
                }
            }
        }
    }

    private fun filterSupportedTags(tags: List<String>, rules: List<CompatibilityRule>): List<String> =
        tags.filter { tag ->
            val version = parseVersion(tag)
            rules.any { it.stdlibRange.matches(version) }
        }

    private fun extractCode(column: String): List<String> =
        Regex("`([^`]*)`").findAll(column).map { it.groupValues[1].trim() }.toList()

    private fun canonicalizeTag(raw: String): String? {
        val version = AikenCliCompatibility.SemanticVersion.parse(raw.removePrefix("v")) ?: return null
        val suffix =
            if (version.preRelease.isEmpty()) {
                ""
            } else {
                "-" + version.preRelease.joinToString(".") { identifier ->
                    identifier.numeric?.toString() ?: identifier.text.orEmpty()
                }
            }
        return "v${version.major}.${version.minor}.${version.patch}$suffix"
    }

    private fun parseVersion(raw: String): AikenCliCompatibility.SemanticVersion =
        AikenCliCompatibility.SemanticVersion.parse(raw.removePrefix("v"))
            ?: throw IllegalStateException("Unable to parse semantic version from '$raw'")

    private data class ProcessResult(
        val output: String,
        val exitCode: Int
    )

    private fun runProcess(command: List<String>): ProcessResult? {
        return try {
            val processBuilder = ProcessBuilder(command)
            if (SystemInfo.isWindows) {
                processBuilder.redirectErrorStream(true)
            } else {
                processBuilder.redirectErrorStream(true)
            }
            val process = processBuilder.start()
            if (!process.waitFor(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            ProcessResult(
                output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim(),
                exitCode = process.exitValue()
            )
        } catch (_: Exception) {
            null
        }
    }
}
