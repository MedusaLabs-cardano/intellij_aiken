package com.medusalabs.aiken.project

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.medusalabs.aiken.tooling.AikenToolchainMode
import com.medusalabs.aiken.naming.AikenNamingRules
import com.medusalabs.aiken.tooling.AikenNodeToolchain
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal object AikenProjectScaffolder {
    private const val TEMPLATE_ROOT = "project-template"
    private val COMMON_TEMPLATE_FILES =
        listOf(
            TemplateFile("gitignore.template", ".gitignore"),
            TemplateFile("README.md", "README.md"),
            TemplateFile("aiken.toml", "aiken.toml"),
            TemplateFile(".github/workflows/continuous-integration.yml", ".github/workflows/continuous-integration.yml")
        )
    private val VALIDATOR_TEMPLATE_FILES =
        listOf(
            TemplateFile("validators/placeholder.ak", "validators/placeholder.ak"),
            TemplateFile("validators/tests/test_module.ak", "validators/tests/test_module.ak")
        )

    private data class TemplateFile(
        val resourcePath: String,
        val destinationPath: String
    )

    fun normalizeToken(raw: String): String = AikenNamingRules.normalizePackageToken(raw)

    fun requireValidToken(label: String, value: String) {
        AikenNamingRules.requireValidPackageToken(label, value)
    }

    fun resolveTargetDirectoryPath(projectPath: String, projectName: String): String {
        val parentDir = File(projectPath.trim()).absoluteFile
        return File(parentDir, projectName.trim()).absolutePath
    }

    fun createProject(
        project: Project,
        targetDirectoryPath: String,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean,
        toolchainMode: AikenToolchainMode,
        globalAikenCommand: String,
        aikenVersion: String,
        stdlibVersion: String,
        plutusVersion: String?
    ) {
        val targetDir = File(targetDirectoryPath.trim()).absoluteFile
        val parentDir = targetDir.parentFile
            ?: throw IllegalStateException("Target directory has no parent: ${targetDir.path}")

        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        if (!parentDir.isDirectory) {
            throw IllegalStateException("Project path is not a directory: ${parentDir.path}")
        }

        val targetIssue = validateTargetDirectoryPath(targetDir.path, projectName)
        if (targetIssue != null) {
            throw IOException(targetIssue)
        }
        Files.createDirectories(targetDir.toPath())
        scaffoldFromTemplate(
            targetDir = targetDir,
            vendor = vendor,
            projectName = projectName,
            libraryOnly = libraryOnly,
            aikenVersion = aikenVersion,
            stdlibVersion = stdlibVersion
        )
        applyPostInitializationDefaults(targetDir, libraryOnly, toolchainMode == AikenToolchainMode.LOCAL)
        updateAikenManifest(targetDir, stdlibVersion, plutusVersion)

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetDir)?.refresh(false, true)
    }

    fun validateTargetDirectory(projectPath: String, projectName: String): String? {
        val targetDirPath = resolveTargetDirectoryPath(projectPath, projectName)
        return validateTargetDirectoryPath(targetDirPath, projectName)
    }

    fun validateTargetDirectoryPath(targetDirectoryPath: String, projectName: String): String? {
        val trimmedPath = targetDirectoryPath.trim()
        val trimmedName = projectName.trim()
        if (trimmedPath.isBlank() || trimmedName.isBlank()) return null

        val targetDir = File(trimmedPath).absoluteFile
        val parentDir = targetDir.parentFile ?: return "Invalid target directory: ${targetDir.path}"
        if (parentDir.exists() && !parentDir.isDirectory) {
            return "Project path is not a directory: ${parentDir.path}"
        }
        if (targetDir.exists()) {
            val files = targetDir.listFiles()
            if (files != null && files.isNotEmpty()) {
                val disallowed = files.filterNot { isAllowedBootstrapEntry(it, trimmedName) }
                if (disallowed.isEmpty()) return null
                return "Target directory already exists and is not empty: ${targetDir.path}"
            }
        }
        return null
    }

    private fun scaffoldFromTemplate(
        targetDir: File,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean,
        aikenVersion: String,
        stdlibVersion: String
    ) {
        Files.createDirectories(targetDir.toPath().resolve("lib"))
        if (!libraryOnly) {
            Files.createDirectories(targetDir.toPath().resolve("env"))
        }

        val templateValues =
            mapOf(
                "%VENDOR_NAME%" to vendor,
                "%PROJECT_NAME%" to projectName,
                "%AIKEN_VERSION%" to aikenVersion,
                "%STDLIB_VERSION%" to stdlibVersion
            )

        COMMON_TEMPLATE_FILES.forEach { templateFile ->
            copyTemplateFile(
                templateRelativePath = templateFile.resourcePath,
                destination = targetDir.toPath().resolve(templateFile.destinationPath),
                replacements = templateValues
            )
        }
        if (!libraryOnly) {
            VALIDATOR_TEMPLATE_FILES.forEach { templateFile ->
                copyTemplateFile(
                    templateRelativePath = templateFile.resourcePath,
                    destination = targetDir.toPath().resolve(templateFile.destinationPath),
                    replacements = templateValues
                )
            }
        }
    }

    private fun copyTemplateFile(
        templateRelativePath: String,
        destination: Path,
        replacements: Map<String, String>
    ) {
        if (Files.exists(destination)) {
            return
        }

        val resourcePath = "/$TEMPLATE_ROOT/$templateRelativePath"
        val input = AikenProjectScaffolder::class.java.getResourceAsStream(resourcePath)
            ?: throw IOException("Missing bundled project template resource: $resourcePath")
        val content =
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                applyTemplateReplacements(reader.readText(), replacements)
            }
        destination.parent?.let(Files::createDirectories)
        Files.writeString(destination, content, StandardCharsets.UTF_8)
    }

    private fun applyTemplateReplacements(
        content: String,
        replacements: Map<String, String>
    ): String =
        replacements.entries.fold(content) { current, (placeholder, value) ->
            current.replace(placeholder, value)
        }

    private fun isAllowedBootstrapEntry(entry: File, projectName: String): Boolean {
        val name = entry.name
        return name == ".idea" ||
            name == ".git" ||
            name == ".name" ||
            name == ".DS_Store" ||
            name == "$projectName.iml"
    }

    private fun applyPostInitializationDefaults(targetDir: File, libraryOnly: Boolean, includeNodeModules: Boolean) {
        ensureArtifactsInGitignore(targetDir, includeNodeModules)
        if (!libraryOnly) {
            ensureDefaultValidatorFile(targetDir)
            ensureDefaultTestFile(targetDir)
        }
    }

    private fun updateAikenManifest(targetDir: File, stdlibVersion: String, plutusVersion: String?) {
        val manifestPath = targetDir.toPath().resolve("aiken.toml")
        if (!Files.isRegularFile(manifestPath)) {
            return
        }

        val content = Files.readString(manifestPath, StandardCharsets.UTF_8)
        val normalizedPlutusVersion = plutusVersion?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val updated = buildString {
            append(updatePlutusVersion(content, normalizedPlutusVersion))
        }.let { updateStdlibDependencyVersion(it, stdlibVersion) }

        if (updated != content) {
            Files.writeString(manifestPath, updated, StandardCharsets.UTF_8)
        }
    }

    private fun updatePlutusVersion(content: String, plutusVersion: String?): String {
        if (plutusVersion.isNullOrBlank()) return content
        val plutusRegex = Regex("""(?m)^plutus\s*=\s*["'][^"']+["']\s*$""")
        val legacyPlutusRegex = Regex("""(?m)^plutusVersion\s*=\s*["'][^"']+["']\s*$""")
        val replacement = """plutus = "$plutusVersion""""
        val legacyReplacement = """plutusVersion = "$plutusVersion""""
        if (plutusRegex.containsMatchIn(content)) {
            return plutusRegex.replace(content, replacement)
        }
        if (legacyPlutusRegex.containsMatchIn(content)) {
            return legacyPlutusRegex.replace(content, legacyReplacement)
        }

        val lines = content.lines().toMutableList()
        val versionIndex = lines.indexOfFirst { it.trimStart().startsWith("version =") }
        if (versionIndex >= 0) {
            lines.add(versionIndex + 1, replacement)
            return lines.joinToString("\n")
        }
        return "$replacement\n$content"
    }

    private fun updateStdlibDependencyVersion(content: String, stdlibVersion: String): String {
        val lines = content.lines().toMutableList()
        val stdlibNameRegex = Regex("""^name\s*=\s*["']aiken-lang/stdlib["']\s*$""")
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() != "[[dependencies]]") {
                index += 1
                continue
            }

            val blockStart = index
            var blockEnd = lines.size
            var cursor = index + 1
            while (cursor < lines.size) {
                if (lines[cursor].trim() == "[[dependencies]]") {
                    blockEnd = cursor
                    break
                }
                cursor += 1
            }

            val block = lines.subList(blockStart, blockEnd)
            if (block.none { stdlibNameRegex.matches(it.trim()) }) {
                index = blockEnd
                continue
            }

            val replacement = """version = "$stdlibVersion""""
            val versionIndex = block.indexOfFirst { it.trimStart().startsWith("version =") }
            if (versionIndex >= 0) {
                block[versionIndex] = replacement
            } else {
                val nameIndex = block.indexOfFirst { stdlibNameRegex.matches(it.trim()) }
                val insertAt = if (nameIndex >= 0) nameIndex + 1 else 1
                block.add(insertAt, replacement)
            }
            return lines.joinToString("\n")
        }

        val suffix = if (content.endsWith("\n")) "" else "\n"
        return buildString {
            append(content)
            append(suffix)
            append("\n[[dependencies]]\n")
            append("name = \"aiken-lang/stdlib\"\n")
            append("version = \"")
            append(stdlibVersion)
            append("\"\n")
            append("source = \"github\"\n")
        }
    }

    private fun ensureArtifactsInGitignore(targetDir: File, includeLocalToolchain: Boolean) {
        val gitignore = targetDir.toPath().resolve(".gitignore")
        val entries = buildList {
            add("artifacts/")
            if (includeLocalToolchain) {
                add("${AikenNodeToolchain.LOCAL_TOOLCHAIN_DIRECTORY}/")
            }
        }
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore, entries.joinToString(separator = "\n", postfix = "\n"), StandardCharsets.UTF_8)
            return
        }

        val content = Files.readString(gitignore, StandardCharsets.UTF_8)
        val lines = content.lines().map { it.trim() }
        val missingEntries = entries.filterNot { entry ->
            entry in lines || entry.removeSuffix("/") in lines
        }
        if (missingEntries.isEmpty()) {
            return
        }

        val suffix = if (content.isEmpty() || content.endsWith("\n")) "" else "\n"
        Files.writeString(
            gitignore,
            buildString {
                append(content)
                append(suffix)
                append(missingEntries.joinToString(separator = "\n"))
                append('\n')
            },
            StandardCharsets.UTF_8
        )
    }

    private fun ensureDefaultTestFile(targetDir: File) {
        val testsDir = targetDir.toPath().resolve("validators").resolve("tests")
        Files.createDirectories(testsDir)

        val testFile = testsDir.resolve("test_module.ak")
        if (Files.exists(testFile)) {
            return
        }

        val content = """
            use placeholder
            
            test example() {
              2 + 2 == 4
            }
            
        """.trimIndent()
        Files.writeString(testFile, content, StandardCharsets.UTF_8)
    }

    private fun ensureDefaultValidatorFile(targetDir: File) {
        val validatorsDir = targetDir.toPath().resolve("validators")
        Files.createDirectories(validatorsDir)

        val validatorFile = validatorsDir.resolve("placeholder.ak")
        if (Files.exists(validatorFile)) {
            return
        }

        val content = """
            use cardano/address.{Credential}
            use cardano/assets.{PolicyId}
            use cardano/certificate.{Certificate}
            use cardano/governance.{ProposalProcedure, Voter}
            use cardano/transaction.{Transaction, OutputReference}
            
            validator placeholder {
              mint(_redeemer: Data, _policy_id: PolicyId, _self: Transaction) {
                todo @"mint logic goes here"
              }
            
              spend(_datum: Option<Data>, _redeemer: Data, _utxo: OutputReference, _self: Transaction) {
                todo @"spend logic goes here"
              }
            
              withdraw(_redeemer: Data, _account: Credential, _self: Transaction) {
                todo @"withdraw logic goes here"
              }
            
              publish(_redeemer: Data, _certificate: Certificate, _self: Transaction) {
                todo @"publish logic goes here"
              }
            
              vote(_redeemer: Data, _voter: Voter, _self: Transaction) {
                todo @"vote logic goes here"
              }
            
              propose(_redeemer: Data, _proposal: ProposalProcedure, _self: Transaction) {
                todo @"propose logic goes here"
              }
            }
            
        """.trimIndent()
        Files.writeString(validatorFile, content, StandardCharsets.UTF_8)
    }

}
