package com.medusalabs.aiken.run

import com.medusalabs.aiken.AikenPlatformTestCase
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import org.junit.Test
import java.io.File
import javax.swing.tree.DefaultMutableTreeNode

class AikenRunConfigurationCompatibilityTest : AikenPlatformTestCase() {
    private companion object {
        const val APPLY_DATA_CONSTRUCTOR_INDEX = 7L
    }

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
    fun structuredCheckFailureDetailsDoNotDuplicateTracesOrOnFailure() {
        val configuration = buildConfiguration(AikenRunCommand.CHECK)
        val parseMethod =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "parseCheckReport",
                String::class.java,
                String::class.java
            )
        parseMethod.isAccessible = true

        val output =
            """
            {
              "seed": 4106286813,
              "summary": {
                "total": 1,
                "passed": 0,
                "failed": 1,
                "kind": {
                  "unit": 1,
                  "property": 0
                }
              },
              "modules": [
                {
                  "name": "tests/test_module",
                  "summary": {
                    "total": 1,
                    "passed": 0,
                    "failed": 1,
                    "kind": {
                      "unit": 1,
                      "property": 0
                    }
                  },
                  "tests": [
                    {
                      "title": "success",
                      "status": "fail",
                      "on_failure": "fail_immediately",
                      "execution_units": {
                        "mem": 26107,
                        "cpu": 8271759
                      },
                      "traces": [
                        "when redeemer is {\n  Mint -> True\n  Burn -> False\n} ? False",
                        "and {\n  (signature == expected_pubkey)?,\n  when redeemer is {\n    Mint -> True\n    Burn -> False\n  }?,\n} ? False"
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val report = parseMethod.invoke(configuration, output, null)
        check(report != null)

        val reportClass = report.javaClass
        @Suppress("UNCHECKED_CAST")
        val modules = reportClass.getMethod("getModules").invoke(report) as List<Any>
        val module = modules.single()
        @Suppress("UNCHECKED_CAST")
        val tests = module.javaClass.getMethod("getTests").invoke(module) as List<Any>
        val test = tests.single()

        val details = test.javaClass.getMethod("getDetails").invoke(test) as String?
        val traces = test.javaClass.getMethod("getTraces").invoke(test) as List<*>

        assertNull(details)
        assertEquals(2, traces.size)

        val stdOutMethod =
            AikenRunConfiguration::class.java.getDeclaredMethod(
                "buildTestLeafStdOut",
                test.javaClass
            )
        stdOutMethod.isAccessible = true
        val stdOut = stdOutMethod.invoke(configuration, test) as String

        assertTrue(stdOut.contains(". with traces"))
        assertTrue(stdOut.contains("when redeemer is"))
        assertFalse(stdOut.contains("on_failure:"))
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

    @Test
    fun applyGuiContextKeepsMultipleMatchingValidatorsAsIndependentTargets() {
        val configuration = buildConfiguration(AikenRunCommand.APPLY)
        val blueprint =
            File.createTempFile("aiken-apply-multi-validator-", ".json").apply {
                deleteOnExit()
                writeText(
                    """
                    {
                      "validators": [
                        {
                          "title": "forever_false.forever_false",
                          "parameters": [
                            {
                              "title": "first",
                              "schema": { "dataType": "integer" }
                            }
                          ]
                        },
                        {
                          "title": "nse_housing.exchange",
                          "parameters": [
                            {
                              "title": "second",
                              "schema": { "dataType": "bytes" }
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                )
            }

        val stateClass =
            AikenRunConfiguration::class.java.declaredClasses.first { it.simpleName == "AikenApplyGuiRunState" }
        val constructor = stateClass.getDeclaredConstructor(AikenRunConfiguration::class.java)
        constructor.isAccessible = true
        val state = constructor.newInstance(configuration)
        val method = stateClass.getDeclaredMethod("loadApplyContext", File::class.java)
        method.isAccessible = true

        val context = method.invoke(state, blueprint)
        val targets = context.readPrivateField<List<Any>>("targets")

        assertEquals(2, targets.size)
        assertEquals("forever_false", targets[0].readPrivateField<String>("module"))
        assertEquals("forever_false", targets[0].readPrivateField<String>("validator"))
        assertEquals("nse_housing", targets[1].readPrivateField<String>("module"))
        assertEquals("exchange", targets[1].readPrivateField<String>("validator"))
        assertEquals(2, context.readPrivateField<Int>("totalParameterCount"))
        assertEquals("2 validators", context.readPrivateField<String>("displayTarget"))
    }

    @Test
    fun applyDataByteLeavesUseContentEquality() {
        val bytes = newApplyDataBytes(byteArrayOf(1, 2, 3))
        val sameBytes = newApplyDataBytes(byteArrayOf(1, 2, 3))
        val differentBytes = newApplyDataBytes(byteArrayOf(1, 2, 4))
        val raw = newApplyDataRawCbor(byteArrayOf(0xd8.toByte(), 0x79, 0x80.toByte()))
        val sameRaw = newApplyDataRawCbor(byteArrayOf(0xd8.toByte(), 0x79, 0x80.toByte()))
        val differentRaw = newApplyDataRawCbor(byteArrayOf(0xd8.toByte(), 0x7a, 0x80.toByte()))

        assertEquals(bytes, sameBytes)
        assertEquals(bytes.hashCode(), sameBytes.hashCode())
        assertFalse(bytes == differentBytes)
        assertEquals(raw, sameRaw)
        assertEquals(raw.hashCode(), sameRaw.hashCode())
        assertFalse(raw == differentRaw)
    }

    @Test
    fun applyDataNestedStructuresUseByteContentEquality() {
        val bytes = newApplyDataBytes(byteArrayOf(1, 2, 3))
        val sameBytes = newApplyDataBytes(byteArrayOf(1, 2, 3))
        val raw = newApplyDataRawCbor(byteArrayOf(0x41, 0xff.toByte()))
        val sameRaw = newApplyDataRawCbor(byteArrayOf(0x41, 0xff.toByte()))

        val list = newApplyDataList(listOf(bytes, raw))
        val sameList = newApplyDataList(listOf(sameBytes, sameRaw))
        val map = newApplyDataMap(listOf(bytes to raw))
        val sameMap = newApplyDataMap(listOf(sameBytes to sameRaw))
        val constr = newApplyDataConstr(listOf(list, map))
        val sameConstr = newApplyDataConstr(listOf(sameList, sameMap))

        assertEquals(list, sameList)
        assertEquals(map, sameMap)
        assertEquals(constr, sameConstr)
        assertEquals(constr.hashCode(), sameConstr.hashCode())
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

    private inline fun <reified T> Any.readPrivateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as T
    }

    private fun newApplyDataBytes(value: ByteArray): Any =
        newApplyData("Bytes", ByteArray::class.java, value)

    private fun newApplyDataRawCbor(bytes: ByteArray): Any =
        newApplyData("RawCbor", ByteArray::class.java, bytes)

    private fun newApplyDataList(items: List<Any>): Any =
        newApplyData("List", List::class.java, items)

    private fun newApplyDataMap(items: List<Pair<Any, Any>>): Any =
        newApplyData("Map", List::class.java, items)

    private fun newApplyDataConstr(fields: List<Any>): Any {
        val constructor =
            applyDataSubclass("Constr").getDeclaredConstructor(Long::class.javaPrimitiveType, List::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(APPLY_DATA_CONSTRUCTOR_INDEX, fields)
    }

    private fun newApplyData(simpleName: String, parameterType: Class<*>, value: Any): Any {
        val constructor = applyDataSubclass(simpleName).getDeclaredConstructor(parameterType)
        constructor.isAccessible = true
        return constructor.newInstance(value)
    }

    private fun applyDataSubclass(simpleName: String): Class<*> {
        val applyStateClass =
            AikenRunConfiguration::class.java.declaredClasses.first { it.simpleName == "AikenApplyGuiRunState" }
        val applyDataClass = applyStateClass.declaredClasses.first { it.simpleName == "ApplyData" }
        return applyDataClass.declaredClasses.first { it.simpleName == simpleName }
    }
}
