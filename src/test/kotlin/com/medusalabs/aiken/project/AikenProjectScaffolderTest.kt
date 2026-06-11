package com.medusalabs.aiken.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VfsUtil
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.tooling.AikenToolchainMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.JTextField
import javax.swing.text.AbstractDocument
import javax.swing.text.PlainDocument

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

        val manifest = readProjectText(projectDir.resolve("aiken.toml"))
        assertTrue(Files.isDirectory(projectDir.resolve("lib")))
        assertTrue(Files.isDirectory(projectDir.resolve("env")))
        assertTrue(Files.isRegularFile(projectDir.resolve("validators").resolve("contract.ak")))
        assertTrue(Files.isRegularFile(projectDir.resolve("validators").resolve("tests").resolve("test_module.ak")))
        assertTrue(Files.isRegularFile(projectDir.resolve("README.md")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".github").resolve("workflows").resolve("continuous-integration.yml")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".idea").resolve("runConfigurations").resolve("Run_checks.xml")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".idea").resolve("runConfigurations").resolve("Build_blueprint.xml")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".idea").resolve("runConfigurations").resolve("Clean_artifacts.xml")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".idea").resolve("runConfigurations").resolve("Make_artifacts.xml")))
        assertTrue(Files.isRegularFile(projectDir.resolve(".idea").resolve("runConfigurations").resolve("Parametrize_blueprint.xml")))
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

        val updated = readProjectText(manifest)
        assertTrue(updated.contains("""plutusVersion = "v3""""))
        assertTrue(updated.contains("""version = "v2.2.1""""))
    }

    @Test
    fun ensuresDefaultValidatorAndTestFilesExist() {
        val projectDir = Files.createTempDirectory("aiken-scaffold-defaults")

        applyDefaultsForTest(projectDir.toString())

        val validatorFile = projectDir.resolve("validators").resolve("contract.ak")
        val testFile = projectDir.resolve("validators").resolve("tests").resolve("test_module.ak")
        assertTrue(Files.isRegularFile(validatorFile))
        assertTrue(Files.isRegularFile(testFile))
        assertTrue(readProjectText(testFile).contains("use contract"))
    }

    @Test
    fun preservesExistingGitignoreEntriesWhenAddingScaffoldDefaults() {
        val projectDir = Files.createTempDirectory("aiken-scaffold-gitignore")
        val gitignore = projectDir.resolve(".gitignore")
        Files.writeString(gitignore, "custom/\n", StandardCharsets.UTF_8)

        applyDefaultsForTest(projectDir.toString())

        val lines = readProjectText(gitignore).lines()
        assertTrue(lines.contains("custom/"))
        assertTrue(lines.contains("artifacts/"))
        assertTrue(lines.contains("bin/"))
    }

    @Test
    fun installsMissingDefaultRunConfigurationsWithoutOverwritingExistingOnes() {
        val projectDir = Files.createTempDirectory("aiken-run-config-defaults")
        Files.writeString(projectDir.resolve("aiken.toml"), "name = \"acme/demo\"\n", StandardCharsets.UTF_8)
        val runConfigurationsDir = projectDir.resolve(".idea").resolve("runConfigurations")
        Files.createDirectories(runConfigurationsDir)
        val existingRunChecks = runConfigurationsDir.resolve("Run_checks.xml")
        Files.writeString(existingRunChecks, "<custom />\n", StandardCharsets.UTF_8)

        AikenProjectScaffolder.ensureDefaultRunConfigurations(projectDir)

        assertEquals("<custom />\n", readProjectText(existingRunChecks))
        assertTrue(Files.isRegularFile(runConfigurationsDir.resolve("Build_blueprint.xml")))
        assertTrue(Files.isRegularFile(runConfigurationsDir.resolve("Clean_artifacts.xml")))
        assertTrue(Files.isRegularFile(runConfigurationsDir.resolve("Make_artifacts.xml")))
        assertTrue(Files.isRegularFile(runConfigurationsDir.resolve("Parametrize_blueprint.xml")))
    }

    @Test
    fun defaultRunConfigurationsUseProjectDirMacroInsteadOfLocalAbsolutePaths() {
        val projectDir = Files.createTempDirectory("aiken-run-config-paths")

        AikenProjectScaffolder.createProject(
            targetDirectoryPath = projectDir.toString(),
            vendor = "acme",
            projectName = "demo",
            libraryOnly = false,
            toolchainMode = AikenToolchainMode.GLOBAL,
            aikenVersion = "1.1.21",
            stdlibVersion = "v3.0.0",
            plutusVersion = "V3"
        )

        val runConfigurationsDir = projectDir.resolve(".idea").resolve("runConfigurations")
        val runConfigurationFiles =
            listOf(
                "Build_blueprint.xml",
                "Clean_artifacts.xml",
                "Make_artifacts.xml",
                "Parametrize_blueprint.xml",
                "Run_checks.xml"
            )
        runConfigurationFiles.forEach { fileName ->
            val content = readProjectText(runConfigurationsDir.resolve(fileName))
            assertTrue("$fileName must use IntelliJ project macro", content.contains("""name="projectDirectory" value="${'$'}PROJECT_DIR${'$'}""""))
            assertFalse("$fileName must not contain a Unix home project path", Regex("""/home/[^"'\\\s]+/[^"'\s]*""").containsMatchIn(content))
            val macOsHomePathPattern = Regex("""/""" + """Users/[^"'\\\s]+/[^"'\s]*""")
            assertFalse("$fileName must not contain a macOS user project path", macOsHomePathPattern.containsMatchIn(content))
            assertFalse("$fileName must not contain a Windows drive path", Regex("""[A-Za-z]:\\[^"'\s]*""").containsMatchIn(content))
        }
    }

    @Test
    fun startupActivityInstallsDefaultRunConfigurationsForExternallyCreatedAikenProject() = runBlocking {
        val manifest = myFixture.addFileToProject("aiken.toml", "name = \"acme/demo\"\n")

        AikenRunConfigurationsStartupActivity()
            .ensureDefaultRunConfigurationsForBaseDirectory(manifest.virtualFile.parent)

        assertNotNull(myFixture.findFileInTempDir(".idea/runConfigurations/Run_checks.xml"))
        assertNotNull(myFixture.findFileInTempDir(".idea/runConfigurations/Build_blueprint.xml"))
        assertNotNull(myFixture.findFileInTempDir(".idea/runConfigurations/Clean_artifacts.xml"))
        assertNotNull(myFixture.findFileInTempDir(".idea/runConfigurations/Make_artifacts.xml"))
        assertNotNull(myFixture.findFileInTempDir(".idea/runConfigurations/Parametrize_blueprint.xml"))
        assertNotNull(myFixture.findFileInTempDir(".idea/aiken/default_run_configurations_initialized"))
    }

    @Test
    fun startupActivityDoesNotRestoreDefaultRunConfigurationsAfterMarkerExists() = runBlocking {
        val manifest = myFixture.addFileToProject("aiken.toml", "name = \"acme/demo\"\n")

        AikenRunConfigurationsStartupActivity()
            .ensureDefaultRunConfigurationsForBaseDirectory(manifest.virtualFile.parent)
        ApplicationManager.getApplication().runWriteAction {
            myFixture.findFileInTempDir(".idea/runConfigurations/Run_checks.xml").delete(this)
        }

        AikenRunConfigurationsStartupActivity()
            .ensureDefaultRunConfigurationsForBaseDirectory(manifest.virtualFile.parent)

        assertNull(myFixture.findFileInTempDir(".idea/runConfigurations/Run_checks.xml"))
        assertNotNull(myFixture.findFileInTempDir(".idea/runConfigurations/Build_blueprint.xml"))
    }

    @Test
    fun wizardTokenInputDropsWhitespaceBeforePackageNormalization() {
        assertEquals("myproject", normalizeWizardTokenInput("My Project"))
        assertEquals("acmevendor", normalizeWizardTokenInput("Acme\tVendor"))
        assertEquals("bad-name", normalizeWizardTokenInput("Bad @ Name"))
    }

    @Test
    fun wizardTokenDocumentFilterBlocksWhitespaceDuringTypingAndPaste() {
        val document = PlainDocument()
        (document as AbstractDocument).documentFilter = AikenWizardTokenDocumentFilter()

        document.insertString(0, "My ", null)
        document.insertString(document.length, "Project", null)
        assertEquals("myproject", document.getText(0, document.length))

        document.remove(0, document.length)
        document.insertString(0, "Acme Vendor", null)
        assertEquals("acmevendor", document.getText(0, document.length))
    }

    @Test
    fun wizardTokenDocumentFilterPreservesMiddleEditCaretPosition() {
        val field = JTextField("myproject")
        (field.document as AbstractDocument).documentFilter = AikenWizardTokenDocumentFilter()
        field.caretPosition = 2

        field.replaceSelection("New ")

        assertEquals("mynewproject", field.text)
        assertEquals(5, field.caretPosition)
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

    private fun readProjectText(path: java.nio.file.Path): String {
        val virtualFile = VfsUtil.findFile(path, true) ?: error("Missing file: $path")
        return VfsUtil.loadText(virtualFile)
    }
}
