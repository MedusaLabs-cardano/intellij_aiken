package com.medusalabs.aiken.run

import com.intellij.build.BuildTextConsoleView
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.openapi.util.Disposer
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test
import java.io.File

class AikenBuildMessagesRunStateTest : AikenPlatformTestCase() {
    @Test
    fun extractsBuildFilePositionFromDiagnosticBlock() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "validators/placeholder.ak",
                """
                validator placeholder(
                  n: Int,
                ) {
                  n
                }
                """.trimIndent()
            )

        val configuration = buildConfiguration()
        val workDir = source.virtualFile.parent.parent.path
        val filePosition =
            configuration.extractDiagnosticFilePositionForTest(
                """
                ⚠ I came across an unused variable: n
                  ╭─[validators/placeholder.ak:10:3]
                  help: No big deal.
                """.trimIndent(),
                workDir
            )

        requireNotNull(filePosition)
        val targetFile = requireNotNull(filePosition.file)
        assertEquals(File(source.virtualFile.path).canonicalFile, targetFile.canonicalFile)
        assertEquals(9, filePosition.startLine)
        assertEquals(2, filePosition.startColumn)
    }

    @Test
    fun buildTextConsoleDecodesAnsiOutputText() {
        val console = BuildTextConsoleView(project, false, emptyList())
        Disposer.register(testRootDisposable, console)

        console.component
        console.append("\u001B[33mwarning\u001B[0m\nhelp: No big deal.", true)
        console.flushDeferredText()

        val visibleText = requireNotNull(console.editor).document.text
        assertTrue(visibleText, visibleText.contains("warning"))
        assertTrue(visibleText, visibleText.contains("help: No big deal."))
        assertFalse(visibleText, visibleText.contains("\u001B["))
    }

    @Test
    fun buildDiagnosticMessageFilterLinksLocationPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "validators/placeholder.ak",
                """
                validator placeholder(
                  n: Int,
                ) {
                  n
                }
                """.trimIndent()
            )

        val configuration = buildConfiguration()
        val workDir = source.virtualFile.parent.parent.path
        val filter = configuration.createBuildDiagnosticMessageFilterForTest(workDir)
        val line = "${source.virtualFile.path}:10:3: warning body"
        val result = filter.applyFilter(line, line.length)

        requireNotNull(result)
        val resultItem = result.resultItems.single()
        assertEquals(0, resultItem.highlightStartOffset)
        assertEquals(source.virtualFile.path.length + ":10:3:".length, resultItem.highlightEndOffset)
        assertNotNull(resultItem.hyperlinkInfo)
    }

    @Test
    fun buildDiagnosticMessageEventCreatesFileNavigatableForLocatedMessage() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "validators/placeholder.ak",
                """
                validator placeholder(
                  n: Int,
                ) {
                  n
                }
                """.trimIndent()
            )

        val configuration = buildConfiguration()
        val workDir = source.virtualFile.parent.parent.path
        configuration.projectDirectory = workDir
        val event =
            configuration.createBuildMessageEventForTest(
                buildRootId = "aiken-build",
                sectionName = "warnings",
                index = 0,
                block =
                    """
                    ⚠ I came across an unused variable: n
                      ╭─[validators/placeholder.ak:10:3]
                      help: No big deal.
                    """.trimIndent(),
                kind = com.intellij.build.events.MessageEvent.Kind.WARNING
            )

        val fileEvent = event as? FileMessageEvent
        requireNotNull(fileEvent)
        assertNotNull(fileEvent.getNavigatable(project))
        assertEquals("aiken-build", event.parentId)
    }

    private fun buildConfiguration(): AikenRunConfiguration {
        val type = AikenRunConfigurationType()
        val factory =
            type.configurationFactories
                .filterIsInstance<AikenRunConfigurationFactory>()
                .first { it.presetCommand == AikenRunCommand.BUILD }
        return factory.createTemplateConfiguration(project)
    }

    private fun AikenRunConfiguration.extractDiagnosticFilePositionForTest(
        block: String,
        workDir: String?
    ): FilePosition? {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "extractDiagnosticFilePosition",
                String::class.java,
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(this, block, workDir) as? FilePosition
    }

    private fun AikenRunConfiguration.createBuildDiagnosticMessageFilterForTest(
        workDir: String?
    ): com.intellij.execution.filters.Filter {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "createBuildDiagnosticMessageFilter",
                String::class.java
            )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(this, workDir) as com.intellij.execution.filters.Filter
    }

    private fun AikenRunConfiguration.createBuildMessageEventForTest(
        buildRootId: Any,
        sectionName: String,
        index: Int,
        block: String,
        kind: com.intellij.build.events.MessageEvent.Kind
    ): BuildEvent {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "createBuildMessageEvent",
                Any::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                com.intellij.build.events.MessageEvent.Kind::class.java
            )
        method.isAccessible = true
        return method.invoke(this, buildRootId, sectionName, index, block, kind) as BuildEvent
    }
}
