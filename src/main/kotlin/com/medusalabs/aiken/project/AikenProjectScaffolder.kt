package com.medusalabs.aiken.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.progress.ProgressManager
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ExecutionException
import kotlin.io.path.deleteIfExists

internal object AikenProjectScaffolder {
    private val tokenRegex = Regex("^[a-z0-9_-]+$")
    private data class CommandResult(val exitCode: Int, val output: String)

    fun normalizeToken(raw: String): String {
        val lowered = raw.lowercase(Locale.US)
        val mapped = lowered.map { ch ->
            when {
                ch in 'a'..'z' -> ch
                ch in '0'..'9' -> ch
                ch == '_' || ch == '-' -> ch
                else -> '-'
            }
        }.joinToString("")
        return mapped.replace(Regex("-+"), "-").trim('-')
    }

    fun requireValidToken(label: String, value: String) {
        if (value.isBlank()) {
            throw IllegalStateException("$label is required")
        }
        if (!tokenRegex.matches(value)) {
            throw IllegalStateException("$label must match [a-z0-9_-]+ (lowercase, no spaces)")
        }
    }

    fun resolveTargetDirectoryPath(projectPath: String, projectName: String): String {
        val parentDir = File(projectPath.trim()).absoluteFile
        return File(parentDir, projectName.trim()).absolutePath
    }

    fun createProject(
        targetDirectoryPath: String,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean
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
        val targetExists = targetDir.exists()
        val targetEmpty = targetExists && (targetDir.listFiles()?.isEmpty() == true)
        if (!targetExists || targetEmpty) {
            if (targetEmpty) {
                targetDir.delete()
            }
            runAikenNew(parentDir, vendor, projectName, libraryOnly)
        } else {
            scaffoldIntoExistingTarget(parentDir, targetDir, vendor, projectName, libraryOnly)
        }
        applyPostInitializationDefaults(targetDir, libraryOnly)

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

    private fun runAikenNew(
        workDirectory: File,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean
    ) {
        val result = runWithProgress("Initializing Aiken project") {
            runAikenNewBlocking(workDirectory, vendor, projectName, libraryOnly)
        }
        if (result.exitCode != 0) {
            val body = result.output.ifBlank { "(no output)" }
            throw IOException("`aiken new` failed with exit code ${result.exitCode}.\n$body")
        }
    }

    private fun runWithProgress(title: String, action: () -> CommandResult): CommandResult {
        var result: CommandResult? = null
        var failure: Throwable? = null
        val finished = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    val future = ApplicationManager.getApplication().executeOnPooledThread<CommandResult> {
                        action()
                    }
                    result = future.get()
                } catch (e: ExecutionException) {
                    failure = e.cause ?: e
                } catch (t: Throwable) {
                    failure = t
                }
            },
            title,
            false,
            null
        )
        if (!finished) {
            throw IOException("$title was cancelled")
        }
        failure?.let { throw it }
        return result ?: throw IOException("$title failed with no result")
    }

    private fun runAikenNewBlocking(
        workDirectory: File,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean
    ): CommandResult {
        val args = mutableListOf("aiken", "new")
        if (libraryOnly) {
            args += "-l"
        }
        args += "$vendor/$projectName"

        val process = ProcessBuilder(args)
            .directory(workDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
        val exitCode = process.waitFor()
        return CommandResult(exitCode, output)
    }

    private fun scaffoldIntoExistingTarget(
        parentDir: File,
        targetDir: File,
        vendor: String,
        projectName: String,
        libraryOnly: Boolean
    ) {
        val tempRoot = Files.createTempDirectory(parentDir.toPath(), ".aiken-new-").toFile()
        try {
            runAikenNew(tempRoot, vendor, projectName, libraryOnly)
            val generatedDir = File(tempRoot, projectName)
            if (!generatedDir.isDirectory) {
                throw IOException("`aiken new` did not produce expected directory: ${generatedDir.path}")
            }
            copyTree(generatedDir.toPath(), targetDir.toPath())
        } finally {
            deleteTree(tempRoot.toPath())
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).forEach { src ->
            val relative = from.relativize(src)
            if (relative.toString().isEmpty()) return@forEach
            val topLevel = relative.nameCount > 0 && relative.getName(0).toString() == ".git"
            if (topLevel) return@forEach
            val dst = to.resolve(relative)
            when {
                Files.isDirectory(src) -> Files.createDirectories(dst)
                Files.exists(dst) -> throw IOException("Target file already exists: $dst")
                else -> {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst)
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { it.deleteIfExists() }
    }

    private fun isAllowedBootstrapEntry(entry: File, projectName: String): Boolean {
        val name = entry.name
        return name == ".idea" ||
            name == ".git" ||
            name == ".name" ||
            name == ".DS_Store" ||
            name == "$projectName.iml"
    }

    private fun applyPostInitializationDefaults(targetDir: File, libraryOnly: Boolean) {
        ensureArtifactsInGitignore(targetDir)
        if (!libraryOnly) {
            ensureDefaultTestFile(targetDir)
        }
    }

    private fun ensureArtifactsInGitignore(targetDir: File) {
        val gitignore = targetDir.toPath().resolve(".gitignore")
        val entry = "artifacts/"
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore, "$entry\n", StandardCharsets.UTF_8)
            return
        }

        val content = Files.readString(gitignore, StandardCharsets.UTF_8)
        val lines = content.lines().map { it.trim() }
        if (entry in lines || "artifacts" in lines) {
            return
        }

        val suffix = if (content.isEmpty() || content.endsWith("\n")) "" else "\n"
        Files.writeString(gitignore, "$content$suffix$entry\n", StandardCharsets.UTF_8)
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

}
