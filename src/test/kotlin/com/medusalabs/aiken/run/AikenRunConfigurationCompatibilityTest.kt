package com.medusalabs.aiken.run

import com.medusalabs.aiken.AikenPlatformTestCase
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import org.junit.Test
import javax.swing.tree.DefaultMutableTreeNode

class AikenRunConfigurationCompatibilityTest : AikenPlatformTestCase() {
    @Test
    fun omitsDefaultCheckFlagsFromCommandLine() {
        val configuration = buildConfiguration(AikenRunCommand.CHECK)
        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.20",
                helpText = """
                Usage: aiken check [OPTIONS]
                  --deny
                  --silent
                  --skip-tests
                  --debug
                  --seed <SEED>
                  --max-success <MAX_SUCCESS>
                  --property-coverage <COVERAGE_MODE>
                  --match-tests <MATCH_TESTS>
                  --exact-match
                  --env <ENV>
                  --filter-traces <FILTER_TRACES>
                  --trace-level <TRACE_LEVEL>
                """.trimIndent()
            )

        val args = configuration.buildCommandParametersForTest(support = support)

        assertFalse(args.contains("--max-success"))
        assertFalse(args.contains("--property-coverage"))
        assertFalse(args.contains("--trace-level"))
        assertFalse(args.contains("--filter-traces"))
        assertEquals(emptyList<String>(), args)
    }

    @Test
    fun omitsUnsupportedCheckFlagsForOlderVersionsAndSanitizesExtraArgs() {
        val configuration = buildConfiguration(AikenRunCommand.CHECK)
        configuration.silentWarnings = true
        configuration.checkPropertyCoverage = AikenPropertyCoverage.RELATIVE_TO_TESTS
        configuration.checkTraceLevel = AikenTraceLevel.COMPACT
        configuration.extraArgs = "--property-coverage relative-to-tests --seed 7"

        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.13",
                helpText = null
            )

        val args = configuration.buildCommandParametersForTest(support = support)

        assertFalse(args.contains("--silent"))
        assertFalse(args.contains("--property-coverage"))
        assertTrue(args.contains("--trace-level"))
        assertTrue(args.contains("compact"))
        assertTrue(args.contains("--seed"))
        assertTrue(args.contains("7"))
    }

    @Test
    fun omitsTraceFlagsBeforeTheyWereIntroduced() {
        val configuration = buildConfiguration(AikenRunCommand.BUILD)
        configuration.buildTraceFilter = AikenTraceFilter.USER_DEFINED
        configuration.buildTraceLevel = AikenTraceLevel.COMPACT
        configuration.watch = true
        configuration.buildOutputMode = AikenBuildOutputMode.TTY

        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.BUILD,
                versionText = "1.0.21-alpha",
                helpText = null
            )

        val args = configuration.buildCommandParametersForTest(support = support)

        assertTrue(args.contains("--watch"))
        assertFalse(args.contains("--filter-traces"))
        assertFalse(args.contains("--trace-level"))
    }

    @Test
    fun mergeDiagnosticsSectionsDeduplicatesAnsiAndPlainWarnings() {
        val configuration = buildConfiguration(AikenRunCommand.CHECK)
        val diagnosticsClass =
            AikenRunConfiguration::class.java.declaredClasses.first { it.simpleName == "DiagnosticsSections" }
        val constructor = diagnosticsClass.getDeclaredConstructor(List::class.java, List::class.java)
        constructor.isAccessible = true

        val ansiWarning = "\u001B[33m⚠ I found a todo left in the code.\u001B[0m"
        val plainWarning = "⚠ I found a todo left in the code."

        val first = constructor.newInstance(listOf(ansiWarning), emptyList<String>())
        val second = constructor.newInstance(listOf(plainWarning), emptyList<String>())
        val sectionsArray = java.lang.reflect.Array.newInstance(diagnosticsClass, 2)
        java.lang.reflect.Array.set(sectionsArray, 0, first)
        java.lang.reflect.Array.set(sectionsArray, 1, second)

        val method = AikenRunConfiguration::class.java.getDeclaredMethod("mergeDiagnosticsSections", sectionsArray.javaClass)
        method.isAccessible = true
        val merged = method.invoke(configuration, sectionsArray)
        @Suppress("UNCHECKED_CAST")
        val warnings = diagnosticsClass.getMethod("getWarnings").invoke(merged) as List<String>

        assertEquals(listOf(ansiWarning), warnings)
    }

    @Test
    fun legacyCheckOutputParsesTestsForPreJsonAikenVersions() {
        val configuration = buildConfiguration(AikenRunCommand.CHECK)
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "parseLegacyCheckReport",
                String::class.java,
                String::class.java
            )
        method.isAccessible = true

        val output =
            """
            Testing started at 20:46 ...
               Compiling demo/project 0.0.0 (/tmp/demo)
                  Testing ...

                ┍━ tests/test_module ━━━━━━━━━━━━━━━━━━━━━━━
                │ PASS [mem: 200, cpu: 16100] example
                ┕━━━━━━━━━━━━━━━━━━━━ 1 tests | 1 passed | 0 failed

            Summary 1 check, 0 errors, 1 warnings
            """.trimIndent()

        val report = method.invoke(configuration, output, null)
        check(report != null)

        val reportClass = report.javaClass
        @Suppress("UNCHECKED_CAST")
        val modules = reportClass.getMethod("getModules").invoke(report) as List<Any>
        assertEquals(1, modules.size)

        val moduleClass = modules.first().javaClass
        assertEquals("tests/test_module", moduleClass.getMethod("getName").invoke(modules.first()))

        @Suppress("UNCHECKED_CAST")
        val tests = moduleClass.getMethod("getTests").invoke(modules.first()) as List<Any>
        assertEquals(1, tests.size)

        val testClass = tests.first().javaClass
        assertEquals("example", testClass.getMethod("getTitle").invoke(tests.first()))
        assertEquals(true, testClass.getMethod("getPassed").invoke(tests.first()))
        assertEquals(200L, testClass.getMethod("getMemUnits").invoke(tests.first()))
        assertEquals(16100L, testClass.getMethod("getCpuUnits").invoke(tests.first()))
    }

    @Test
    fun diagnosticTreeSeverityBubblesWarningsToParentNodes() {
        val configuration = buildConfiguration(AikenRunCommand.CHECK)
        val warningLeaf = SMTestProxy("[warning 1] I found a todo left in the code.", false, null)
        val warningsSuite = SMTestProxy("warnings (1)", true, null)
        warningsSuite.addChild(warningLeaf)
        val rootSuite = SMTestProxy("aiken check (1)", true, null)
        rootSuite.addChild(warningsSuite)

        val treeRoot = DefaultMutableTreeNode("Test Results")
        treeRoot.add(DefaultMutableTreeNode(rootSuite))

        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "diagnosticTreeSeverityForTreeValue",
                Any::class.java
            )
        method.isAccessible = true

        val severity = method.invoke(configuration, treeRoot)
        assertEquals("WARNING", severity.toString())
    }

    @Test
    fun commandSupportKnowsStructuredCheckJsonStartsAt116() {
        val oldSupport =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.5",
                helpText = null
            )
        val newSupport =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.6",
                helpText = null
            )

        assertFalse(oldSupport.supportsStructuredCheckJson())
        assertTrue(newSupport.supportsStructuredCheckJson())
    }

    private fun buildConfiguration(command: AikenRunCommand): AikenRunConfiguration {
        val type = AikenRunConfigurationType()
        val factory =
            type.configurationFactories
                .filterIsInstance<AikenRunConfigurationFactory>()
                .first { it.presetCommand == command }
        return factory.createTemplateConfiguration(project)
    }

    private fun AikenRunConfiguration.buildCommandParametersForTest(
        forceSkipTests: Boolean = false,
        support: AikenCliCompatibility.CommandSupport? = null
    ): List<String> {
        val method =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "buildCommandParameters",
                Boolean::class.javaPrimitiveType,
                AikenCliCompatibility.CommandSupport::class.java
            )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(this, forceSkipTests, support) as List<String>
    }
}
