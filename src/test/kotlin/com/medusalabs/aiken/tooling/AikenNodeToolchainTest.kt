package com.medusalabs.aiken.tooling

import com.intellij.openapi.util.SystemInfo
import com.medusalabs.aiken.run.AikenCliCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AikenNodeToolchainTest {
    @Test
    fun buildScaffoldExecArgumentsUsesSelectedVersion() {
        val args = AikenNodeToolchain.buildScaffoldExecArguments(
            version = "1.1.21",
            vendor = "acme",
            projectName = "demo",
            libraryOnly = true
        )

        assertEquals(
            listOf(
                "--yes",
                "--package=@aiken-lang/aiken@1.1.21",
                "--",
                "aiken",
                "new",
                "-l",
                "acme/demo"
            ),
            args
        )
    }

    @Test
    fun buildLocalInstallArgumentsUseDirectNoSaveInstall() {
        val args = AikenNodeToolchain.buildLocalInstallArguments("1.1.21")

        assertEquals(
            listOf(
                "--prefix",
                "bin",
                "--no-save",
                "--no-package-lock",
                "@aiken-lang/aiken@1.1.21",
                "--no-fund",
                "--no-audit"
            ),
            args
        )
    }

    @Test
    fun resolveProjectLocalAikenExecutablePrefersPackagedBinary() {
        val projectDir = Files.createTempDirectory("aiken-toolchain-test")
        val packagedBinary = createPackagedBinary(projectDir)

        assertEquals(packagedBinary.toString(), AikenNodeToolchain.resolveProjectLocalAikenExecutable(projectDir.toString()))
    }

    @Test
    fun resolveProjectLocalAikenExecutableIgnoresLegacyRootNodeModulesLayout() {
        val projectDir = Files.createTempDirectory("aiken-toolchain-legacy-root")
        createPackagedBinary(projectDir, toolchainRoot = projectDir)

        assertNull(AikenNodeToolchain.resolveProjectLocalAikenExecutable(projectDir.toString()))
    }

    @Test
    fun inspectProjectLocalAikenReportsBrokenWhenOnlyWrapperExists() {
        val projectDir = Files.createTempDirectory("aiken-toolchain-wrapper-only")
        val binDir = projectDir.resolve("bin").resolve("node_modules").resolve(".bin")
        Files.createDirectories(binDir)
        val wrapperName = if (SystemInfo.isWindows) "aiken.cmd" else "aiken"
        Files.writeString(binDir.resolve(wrapperName), "echo wrapper")

        val probe = AikenNodeToolchain.inspectProjectLocalAiken(projectDir.toString())

        assertEquals(AikenNodeToolchain.LocalAikenState.BROKEN, probe.state)
        assertTrue(probe.details!!.contains("wrapper exists", ignoreCase = true))
    }

    @Test
    fun resolveProjectLocalAikenExecutableReturnsNullWhenMissing() {
        val projectDir = Files.createTempDirectory("aiken-toolchain-missing")

        assertNull(AikenNodeToolchain.resolveProjectLocalAikenExecutable(projectDir.toString()))
    }

    @Test
    fun parseRegistryVersionCatalogReadsLatestAndVersionsObject() {
        val catalog = AikenNodeToolchain.parseRegistryVersionCatalog(
            """
            {
              "dist-tags": { "latest": "1.1.21" },
              "versions": {
                "1.0.29-alpha": {},
                "1.1.0": {},
                "1.1.1": {},
                "1.1.2": {},
                "1.1.19": {},
                "1.1.20": {},
                "1.1.21": {}
              }
            }
            """.trimIndent()
        )

        assertEquals("1.1.21", catalog.latest)
        assertEquals(listOf("1.1.21", "1.1.20", "1.1.19", "1.1.2", "1.1.1"), catalog.versions)
    }

    @Test
    fun parseVersionCatalogReadsSeparateNpmViewPayloads() {
        val catalog = AikenNodeToolchain.parseVersionCatalog(
            latestJson = "\"1.1.21\"",
            versionsJson = "[\"1.0.29-alpha\", \"1.1.0\", \"1.1.1\", \"1.1.2\", \"1.1.19\", \"1.1.20\", \"1.1.21\"]"
        )

        assertEquals("1.1.21", catalog.latest)
        assertEquals(listOf("1.1.21", "1.1.20", "1.1.19", "1.1.2", "1.1.1"), catalog.versions)
    }

    @Test
    fun sortVersionsDescendingUsesSemanticOrdering() {
        assertEquals(
            listOf("1.1.21", "1.1.20", "1.1.19", "1.1.2", "1.0.29-alpha"),
            AikenNodeToolchain.sortVersionsDescending(
                listOf("1.1.2", "1.1.19", "1.0.29-alpha", "1.1.21", "1.1.20")
            )
        )
    }

    @Test
    fun supportedAikenVersionPolicyRejectsPre111() {
        assertFalse(AikenNodeToolchain.isSupportedAikenVersion("1.1.0"))
        assertFalse(AikenNodeToolchain.isSupportedAikenVersion("1.0.29-alpha"))
        assertTrue(AikenNodeToolchain.isSupportedAikenVersion("1.1.1"))
        assertTrue(AikenNodeToolchain.isSupportedAikenVersion("1.1.21"))
    }

    @Test
    fun cleanupLegacyToolchainManifestDeletesManagedFiles() {
        val projectDir = Files.createTempDirectory("aiken-package-json")
        val toolchainRoot = projectDir.resolve("bin")
        Files.createDirectories(toolchainRoot)
        Files.writeString(
            toolchainRoot.resolve("package.json"),
            """
            {
              "name": "demo",
              "private": true,
              "devDependencies": {
                "@aiken-lang/aiken": "1.1.21"
              }
            }
            """.trimIndent()
        )
        Files.writeString(toolchainRoot.resolve("package-lock.json"), "{}")

        val changed = AikenNodeToolchain.cleanupLegacyToolchainManifest(projectDir)

        assertTrue(changed)
        assertFalse(Files.exists(toolchainRoot.resolve("package.json")))
        assertFalse(Files.exists(toolchainRoot.resolve("package-lock.json")))
    }

    @Test
    fun readCompilerVersionFromManifestUsesAikenTomlCompilerField() {
        val projectDir = Files.createTempDirectory("aiken-compiler-version")
        Files.writeString(
            projectDir.resolve("aiken.toml"),
            """
            name = "acme/demo"
            compiler = "v1.1.5"
            """.trimIndent()
        )

        assertEquals("1.1.5", AikenNodeToolchain.readCompilerVersionFromManifest(projectDir))
    }

    @Test
    fun installedVersionMatchesRequestedUsesResolvedBinaryVersion() {
        val probe =
            AikenNodeToolchain.LocalAikenProbe(
                state = AikenNodeToolchain.LocalAikenState.HEALTHY,
                executable = "/tmp/aiken",
                resolvedVersion = AikenCliCompatibility.SemanticVersion.parse("1.1.5")
            )

        assertTrue(AikenNodeToolchain.installedVersionMatchesRequested(probe, "v1.1.5"))
        assertFalse(AikenNodeToolchain.installedVersionMatchesRequested(probe, "1.1.6"))
        assertTrue(AikenNodeToolchain.installedVersionMatchesRequested(probe, "latest"))
    }

    @Test
    fun buildRepairInstallArgumentsForcesExactAikenReinstall() {
        assertEquals(
            listOf(
                "--prefix",
                "bin",
                "--no-save",
                "--no-package-lock",
                "--force",
                "@aiken-lang/aiken@1.1.21",
                "--no-fund",
                "--no-audit"
            ),
            AikenNodeToolchain.buildRepairInstallArguments("1.1.21")
        )
    }

    private fun createPackagedBinary(
        projectDir: java.nio.file.Path,
        toolchainRoot: java.nio.file.Path = projectDir.resolve("bin")
    ): java.nio.file.Path {
        val realBinDir = toolchainRoot
            .resolve("node_modules")
            .resolve("@aiken-lang")
            .resolve("aiken")
            .resolve("node_modules")
            .resolve(".bin_real")
        Files.createDirectories(realBinDir)
        val binaryName = if (SystemInfo.isWindows) "aiken.cmd" else "aiken"
        val binaryPath = realBinDir.resolve(binaryName)
        val content = if (SystemInfo.isWindows) {
            "@echo off\r\necho aiken v1.1.21\r\n"
        } else {
            "#!/bin/sh\necho aiken v1.1.21\n"
        }
        Files.writeString(binaryPath, content)
        binaryPath.toFile().setExecutable(true)
        return binaryPath
    }
}
