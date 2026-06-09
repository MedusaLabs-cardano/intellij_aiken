package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Test

class AikenReferenceVariantsTest {
    @Test
    fun mapsUnimportedExportToAutoImportedSymbolInsertionFamily() {
        assertEquals(
            AikenReferenceInsertionFamily.AutoImportedSymbol(
                modulePath = "time",
                symbolName = "before"
            ),
            AikenReferenceVariants.autoImportedExportInsertionFamily(
                modulePath = "time",
                symbolName = "before"
            )
        )
    }

    @Test
    fun mapsUnimportedModuleToAutoImportedModuleQualifierInsertionFamily() {
        assertEquals(
            AikenReferenceInsertionFamily.AutoImportedModuleQualifier(
                modulePath = "aiken/list",
                exposedModuleName = "list"
            ),
            AikenReferenceVariants.autoImportedModuleInsertionFamily(
                modulePath = "aiken/list",
                exposedModuleName = "list"
            )
        )
    }

    @Test
    fun mapsUnimportedQualifiedMemberToBareModuleAutoImportInsertionFamily() {
        assertEquals(
            AikenReferenceInsertionFamily.AutoImportedQualifiedMember(
                modulePath = "aiken/list",
                symbolName = "length",
                canonicalQualifier = "list",
                triggerMemberAutoPopup = false
            ),
            AikenReferenceVariants.autoImportedQualifiedMemberInsertionFamily(
                modulePath = "aiken/list",
                symbolName = "length",
                canonicalQualifier = "list"
            )
        )
    }
}
