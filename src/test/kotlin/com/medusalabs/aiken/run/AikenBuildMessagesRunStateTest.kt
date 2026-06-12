package com.medusalabs.aiken.run

import com.intellij.build.BuildTextConsoleView
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.EventResult
import com.intellij.build.events.MessageEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class AikenBuildMessagesRunStateTest : AikenPlatformTestCase() {
    @Test
    fun extractsDiagnosticLocationFromDiagnosticBlock() {
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
        val location =
            configuration.extractDiagnosticLocationForTest(
                """
                ⚠ I came across an unused variable: n
                  ╭─[validators/placeholder.ak:10:3]
                  help: No big deal.
                """.trimIndent(),
                workDir
            )

        requireNotNull(location)
        val targetFile = File(location.stringField("absolutePath"))
        assertEquals(File(source.virtualFile.path).canonicalFile, targetFile.canonicalFile)
        assertEquals(10, location.intField("line"))
        assertEquals(3, location.nullableIntField("column"))
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
    fun buildRunnerAnsiPrinterDecodesAnsiOutputText() {
        val console = BuildTextConsoleView(project, false, emptyList())
        Disposer.register(testRootDisposable, console)

        console.component
        buildConfiguration().printAnsiConsoleOutputForTest(
            console,
            "\u001B[33mwarning\u001B[0m\n",
            ProcessOutputTypes.STDOUT
        )
        console.flushDeferredText()

        val visibleText = requireNotNull(console.editor).document.text
        assertTrue(visibleText, visibleText.contains("warning"))
        assertFalse(visibleText, visibleText.contains("\u001B["))
    }

    @Test
    fun buildTextConsoleDecodesAnsiDiagnosticMessageDetails() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val source =
            myFixture.addFileToProject(
                "validators/placeholder.ak",
                """
                validator placeholder {
                  mint(_redeemer: Data, _policy_id: Data, _self: Data) {
                    todo
                  }
                }
                """.trimIndent()
            )
        val console = BuildTextConsoleView(project, false, emptyList())
        Disposer.register(testRootDisposable, console)
        val event = createDiagnosticMessageEvent(sourcePosition(File(source.virtualFile.path)))

        console.component
        console.onEvent("build-root", event)
        console.flushDeferredText()

        val visibleText = requireNotNull(console.editor).document.text
        assertTrue(visibleText, visibleText.contains("I found a todo left in the code."))
        assertTrue(visibleText, visibleText.contains("An expression of type Bool is expected here."))
        assertFalse(visibleText, visibleText.contains("\u001B["))
    }

    @Test
    fun buildFinishEventClosesRootBuildNode() {
        val event = createFinishEvent()

        assertEquals("build-root", event.id)
        assertNull(event.parentId)
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
    fun buildDiagnosticSourcePositionUsesResolvedDiagnosticLocation() {
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

        val sourcePosition =
            configuration.extractDiagnosticSourcePositionForTest(
                """
                ⚠ I came across an unused variable: n
                  ╭─[validators/placeholder.ak:10:3]
                  help: No big deal.
                """.trimIndent(),
                workDir
            )

        requireNotNull(sourcePosition)
        assertEquals(File(source.virtualFile.path).canonicalFile, sourcePosition.fileField("file").canonicalFile)
        assertEquals(9, sourcePosition.intField("startLine"))
        assertEquals(2, sourcePosition.intField("startColumn"))
    }

    @Test
    fun buildDiagnosticsAreGroupedByModule() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val validator =
            myFixture.addFileToProject(
                "validators/placeholder.ak",
                """
                validator placeholder {
                  mint(_redeemer: Data, _policy_id: Data, _self: Data) {
                    todo
                  }
                }
                """.trimIndent()
            )
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            pub fn helper() {
              todo
            }
            """.trimIndent()
        )
        val workDir = validator.virtualFile.parent.parent.path
        val diagnostics =
            diagnosticsSectionsForTest(
                warnings = listOf(
                    """
                    ⚠ I came across an unused variable: n
                      ╭─[validators/placeholder.ak:2:3]
                    """.trimIndent()
                ),
                errors = listOf(
                    """
                    × I found a todo left in the code.
                      ╭─[lib/helpers.ak:2:3]
                    """.trimIndent(),
                    """
                    × Another validator issue.
                      ╭─[validators/placeholder.ak:3:5]
                    """.trimIndent()
                )
            )

        val groups = buildConfiguration().buildDiagnosticGroupsForTest(diagnostics, workDir)

        assertEquals(2, groups.size)
        assertEquals("placeholder", groups[0].stringField("moduleName"))
        assertEquals(2, groups[0].entriesForTest().size)
        assertEquals("helpers", groups[1].stringField("moduleName"))
        assertEquals(1, groups[1].entriesForTest().size)
    }

    private fun buildConfiguration(): AikenRunConfiguration {
        val type = AikenRunConfigurationType()
        val factory =
            type.configurationFactories
                .filterIsInstance<AikenRunConfigurationFactory>()
                .first { it.presetCommand == AikenRunCommand.BUILD }
        return factory.createTemplateConfiguration(project)
    }

    private fun createFinishEvent(): BuildEvent {
        val result =
            Proxy.newProxyInstance(
                EventResult::class.java.classLoader,
                arrayOf(EventResult::class.java)
            ) { _, _, _ -> null } as EventResult
        val eventClass = Class.forName("com.medusalabs.aiken.run.AikenFinishBuildEvent")
        val constructor = eventClass.getDeclaredConstructor(Any::class.java, String::class.java, EventResult::class.java)
        constructor.isAccessible = true
        return constructor.newInstance("build-root", "Build Succeeded", result) as BuildEvent
    }

    private fun createDiagnosticMessageEvent(position: Any): BuildEvent {
        val diagnosticDescription =
            "\u001B[33mI found a todo left in the code.\u001B[0m\n" +
                "\u001B[35mAn expression of type Bool is expected here.\u001B[0m"
        val eventClass = Class.forName("com.medusalabs.aiken.run.AikenFileMessageBuildEvent")
        val positionClass = Class.forName("com.medusalabs.aiken.run.AikenSourcePosition")
        val constructor =
            eventClass.getDeclaredConstructor(
                Any::class.java,
                Any::class.java,
                MessageEvent.Kind::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                positionClass
            )
        constructor.isAccessible = true
        return constructor.newInstance(
            "event-id",
            "build-root",
            MessageEvent.Kind.WARNING,
            "warnings",
            "[warning 1] I found a todo left in the code.",
            diagnosticDescription,
            position
        ) as BuildEvent
    }

    private fun AikenRunConfiguration.printAnsiConsoleOutputForTest(
        console: ConsoleView,
        text: String,
        outputType: Key<*>
    ) {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "printAnsiConsoleOutput",
                ConsoleView::class.java,
                String::class.java,
                Key::class.java
            )
        method.isAccessible = true
        method.invoke(this, console, text, outputType)
    }

    private fun AikenRunConfiguration.extractDiagnosticLocationForTest(
        block: String,
        workDir: String?
    ): Any? {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "extractDiagnosticLocation",
                String::class.java,
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(this, block, workDir)
    }

    private fun diagnosticsSectionsForTest(
        warnings: List<String>,
        errors: List<String>
    ): Any {
        val diagnosticsClass = AikenRunConfiguration::class.java.declaredClasses.first { it.simpleName == "DiagnosticsSections" }
        val constructor = diagnosticsClass.getDeclaredConstructor(List::class.java, List::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(warnings, errors)
    }

    private fun AikenRunConfiguration.buildDiagnosticGroupsForTest(
        diagnostics: Any,
        workDir: String?
    ): List<Any> {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "buildDiagnosticGroups",
                diagnostics.javaClass,
                String::class.java
            )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(this, diagnostics, workDir) as List<Any>
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

    private fun AikenRunConfiguration.extractDiagnosticSourcePositionForTest(
        block: String,
        workDir: String?
    ): Any? {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "extractDiagnosticSourcePosition",
                String::class.java,
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(this, block, workDir)
    }

    private fun Any.stringField(name: String): String =
        declaredFieldValue(name) as String

    private fun Any.intField(name: String): Int =
        declaredFieldValue(name) as Int

    private fun Any.fileField(name: String): File =
        declaredFieldValue(name) as File

    @Suppress("UNCHECKED_CAST")
    private fun Any.entriesForTest(): List<Any> =
        declaredFieldValue("entries") as List<Any>

    private fun Any.nullableIntField(name: String): Int? =
        declaredFieldValue(name) as Int?

    private fun Any.declaredFieldValue(name: String): Any? {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    private fun sourcePosition(file: File): Any {
        val positionClass = Class.forName("com.medusalabs.aiken.run.AikenSourcePosition")
        val constructor = positionClass.getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, 2, 4)
    }
}
