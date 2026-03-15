package com.medusalabs.aiken.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AikenCliCompatibilityTest {
    @Test
    fun extractsSemanticVersionFromVersionOutput() {
        val version = AikenCliCompatibility.extractVersion("aiken 1.1.17-alpha.1+abcd123")

        requireNotNull(version)
        assertEquals(1, version.major)
        assertEquals(1, version.minor)
        assertEquals(17, version.patch)
        assertEquals("alpha", version.preRelease.first().text)
    }

    @Test
    fun prefersFlagSpellingsAdvertisedByHelp() {
        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.20",
                helpText = """
                Usage: aiken check [OPTIONS]
                  -D, --deny
                  -S, --silent
                  -f, --filter-traces <FILTER_TRACES>
                  --trace-level <TRACE_LEVEL>
                """.trimIndent()
            )

        assertEquals(
            "--filter-traces",
            support.preferredToken(AikenCliCompatibility.ManagedAikenOption.TRACE_FILTER)
        )
        assertEquals(
            "--silent",
            support.preferredToken(AikenCliCompatibility.ManagedAikenOption.SILENT)
        )
    }

    @Test
    fun versionGateDisablesPropertyCoverageBeforeItWasAdded() {
        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.16",
                helpText = null
            )

        assertFalse(support.supports(AikenCliCompatibility.ManagedAikenOption.PROPERTY_COVERAGE))
        assertNull(support.preferredToken(AikenCliCompatibility.ManagedAikenOption.PROPERTY_COVERAGE))
        assertTrue(support.supports(AikenCliCompatibility.ManagedAikenOption.TRACE_LEVEL))
    }

    @Test
    fun sanitizesUnsupportedManagedFlagsFromExtraArgs() {
        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.16",
                helpText = null
            )

        val sanitized =
            AikenCliCompatibility.sanitizeUnsupportedManagedFlags(
                listOf("--property-coverage", "relative-to-tests", "--seed", "42"),
                support
            )

        assertEquals(listOf("--seed", "42"), sanitized.args)
        assertEquals(listOf("--property-coverage"), sanitized.removedFlags)
    }

    @Test
    fun buildsRetryPlanForUnexpectedArgument() {
        val support =
            AikenCliCompatibility.buildCommandSupportForTest(
                command = AikenRunCommand.CHECK,
                versionText = "1.1.16",
                helpText = null
            )

        val retry =
            AikenCliCompatibility.buildRetryPlan(
                args = listOf("--seed", "7", "--property-coverage", "relative-to-tests"),
                support = support,
                rawOutput = """
                error: unexpected argument '--property-coverage' found
                Usage: aiken check <DIRECTORY|--deny|--skip-tests>
                """.trimIndent()
            )

        requireNotNull(retry)
        assertEquals("--property-coverage", retry.removedFlag)
        assertEquals(listOf("--seed", "7"), retry.args)
    }

    @Test
    fun extractsUnexpectedArgumentFromCliOutput() {
        val extracted =
            AikenCliCompatibility.extractUnexpectedArgument(
                "\u001B[31merror: unexpected argument '--plain-numbers' found\u001B[0m"
            )

        assertNotNull(extracted)
        assertEquals("--plain-numbers", extracted)
    }
}
