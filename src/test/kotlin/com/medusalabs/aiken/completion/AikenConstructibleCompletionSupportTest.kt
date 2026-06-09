package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Test

class AikenConstructibleCompletionSupportTest {
    @Test
    fun mapsVisibleConstructibleToRootInsertionFamilyWithoutAutoImport() {
        val constructible =
            AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo(
                name = "Input",
                resultType = "Input",
                fields = emptyList(),
                supportsNamedSyntax = true,
                modulePath = null,
                needsImport = false
            )

        assertEquals(
            AikenConstructibleInsertionFamily.Root(
                lookupText = "Input",
                constructible = constructible,
                autoImportTarget = null
            ),
            AikenConstructibleCompletionSupport.rootInsertionFamily(constructible)
        )
    }

    @Test
    fun mapsAutoImportedConstructibleToRootInsertionFamilyWithAutoImportTarget() {
        val constructible =
            AikenConstructibleCompletionSupport.AikenConstructibleCompletionInfo(
                name = "Input",
                resultType = "Input",
                fields = emptyList(),
                supportsNamedSyntax = true,
                modulePath = "demo/input",
                needsImport = true
            )

        assertEquals(
            AikenConstructibleInsertionFamily.Root(
                lookupText = "AliasInput",
                constructible = constructible,
                autoImportTarget = AikenConstructibleAutoImportTarget("demo/input", "Input")
            ),
            AikenConstructibleCompletionSupport.rootInsertionFamily(constructible, lookupName = "AliasInput")
        )
    }

    @Test
    fun exposesNamedAndPositionalConstructibleFormFamilies() {
        assertEquals(
            AikenConstructibleInsertionFamily.NamedForm,
            AikenConstructibleCompletionSupport.namedFormInsertionFamily()
        )
        assertEquals(
            AikenConstructibleInsertionFamily.PositionalForm,
            AikenConstructibleCompletionSupport.positionalFormInsertionFamily()
        )
    }
}
