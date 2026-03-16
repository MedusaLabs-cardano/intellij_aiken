package com.medusalabs.aiken.project

import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.tooling.AikenToolchainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AikenProjectScaffolderTest : AikenPlatformTestCase() {
    @Test
    fun createsProjectFromBundledTemplate() {
        val root = Files.createTempDirectory("aiken-template-project")
        val projectDir = root.resolve("demo")

        AikenProjectScaffolder.createProject(
            targetDirectoryPath = projectDir.toString(),
            vendor = "acme",
            projectName = "demo",
            libraryOnly = false,
            toolchainMode = AikenToolchainMode.LOCAL,
            aikenVersion = "1.1.21",
            stdlibVersion = "v3.0.0",
            plutusVersion = "V3"
        )

        val manifest = Files.readString(projectDir.resolve("aiken.toml"))
        assertTrue(Files.isDirectory(projectDir.resolve("lib")))
        assertTrue(Files.isDirectory(projectDir.resolve("env")))
        assertTrue(Files.isRegularFile(projectDir.resolve("validators").resolve("placeholder.ak")))
        assertTrue(Files.isRegularFile(projectDir.resolve("validators").resolve("tests").resolve("test_module.ak")))
        assertTrue(Files.isRegularFile(projectDir.resolve("README.md")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".github").resolve("workflows").resolve("continuous-integration.yml")))
        assertEquals(false, Files.isRegularFile(projectDir.resolve("package.json")))
        assertTrue(manifest.contains("""name = "acme/demo""""))
        assertTrue(manifest.contains("""compiler = "1.1.21""""))
        assertTrue(manifest.contains("""version = "v3.0.0""""))
        assertTrue(manifest.contains("""plutus = "v3""""))
    }

    @Test
    fun updatesScaffoldedManifestWithSelectedStdlibAndPlutusVersion() {
        val projectDir = Files.createTempDirectory("aiken-scaffold")
        val manifest =
            projectDir.resolve("aiken.toml")
        Files.writeString(
            manifest,
            """
            name = "demo/project"
            version = "0.0.0"
            plutusVersion = "v3"
            
            [[dependencies]]
            name = "aiken-lang/stdlib"
            version = "v2"
            source = "github"
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8
        )

        updateManifestForTest(projectDir.toString(), "v2.2.1", "V3")

        val updated = Files.readString(manifest, StandardCharsets.UTF_8)
        assertTrue(updated.contains("""plutusVersion = "v3""""))
        assertTrue(updated.contains("""version = "v2.2.1""""))
    }

    @Test
    fun ensuresDefaultValidatorAndTestFilesExist() {
        val projectDir = Files.createTempDirectory("aiken-scaffold-defaults")

        applyDefaultsForTest(projectDir.toString())

        val validatorFile = projectDir.resolve("validators").resolve("placeholder.ak")
        val testFile = projectDir.resolve("validators").resolve("tests").resolve("test_module.ak")
        assertTrue(Files.isRegularFile(validatorFile))
        assertTrue(Files.isRegularFile(testFile))
        assertTrue(Files.readString(testFile).contains("use placeholder"))
    }

    @Suppress("SameParameterValue")
    private fun updateManifestForTest(projectDirectory: String, stdlibVersion: String, plutusVersion: String?) {
        val method =
            AikenProjectScaffolder::class.java.getDeclaredMethod(
                "updateAikenManifest",
                java.io.File::class.java,
                String::class.java,
                String::class.java
            )
        method.isAccessible = true
        method.invoke(AikenProjectScaffolder, java.io.File(projectDirectory), stdlibVersion, plutusVersion)
    }

    private fun applyDefaultsForTest(projectDirectory: String) {
        val method =
            AikenProjectScaffolder::class.java.getDeclaredMethod(
                "applyPostInitializationDefaults",
                java.io.File::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        method.isAccessible = true
        method.invoke(AikenProjectScaffolder, java.io.File(projectDirectory), false, true)
    }
}
