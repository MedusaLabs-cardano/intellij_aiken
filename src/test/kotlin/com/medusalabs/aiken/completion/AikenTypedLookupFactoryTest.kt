package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AikenTypedLookupFactoryTest {
    @Test
    fun buildsFunctionCallTemplateWithoutNamedParameters() {
        val template =
            AikenTypedLookupFactory.functionCallTemplate(
                name = "keep",
                signature = "fn(items: List<Pair<Int, String>>, limit: Int) -> List<Int>"
            )

        assertEquals("keep()", template.text)
        assertEquals("keep(".length, template.caretOffset)
        assertTrue(template.shouldTriggerAutoPopup)
    }

    @Test
    fun buildsPipeFunctionCallTemplateWithoutFirstParameter() {
        val template =
            AikenTypedLookupFactory.pipeFunctionCallTemplate(
                lookupText = "ops.filter",
                signature = "fn(items: List<Int>, predicate: fn(Int) -> Bool) -> List<Int>"
            )

        assertEquals("ops.filter()", template.text)
        assertEquals("ops.filter(".length, template.caretOffset)
        assertTrue(template.shouldTriggerAutoPopup)
    }

    @Test
    fun suppressesPipeFunctionArgumentAutoPopupForUnconstrainedGenericParameter() {
        val template =
            AikenTypedLookupFactory.pipeFunctionCallTemplate(
                lookupText = "reduce",
                signature = "fn(self: List<a>, zero: b, with: fn(b, a) -> b) -> b"
            )

        assertEquals("reduce()", template.text)
        assertEquals("reduce(".length, template.caretOffset)
        assertFalse(template.shouldTriggerAutoPopup)
    }

    @Test
    fun suppressesFunctionArgumentAutoPopupForUnconstrainedGenericParameter() {
        val template =
            AikenTypedLookupFactory.functionCallTemplate(
                name = "identity",
                signature = "fn(value: a) -> a"
            )

        assertEquals("identity()", template.text)
        assertEquals("identity(".length, template.caretOffset)
        assertFalse(template.shouldTriggerAutoPopup)
    }

    @Test
    fun keepsBarePipeLookupWhenNoRemainingParameters() {
        val template =
            AikenTypedLookupFactory.pipeFunctionCallTemplate(
                lookupText = "length",
                signature = "fn(items: List<Int>) -> Int"
            )

        assertEquals("length", template.text)
        assertEquals(null, template.caretOffset)
        assertFalse(template.shouldTriggerAutoPopup)
    }

    @Test
    fun rendersAutoImportedIdentifierCandidatesWithModuleTail() {
        val lookup =
            AikenTypedLookupFactory.createExpectedTypeLookup(
                AikenTypedExpectedTypeCandidate.Identifier(
                    name = "zero",
                    type = "Value",
                    kind = CompletionSymbolKind.IDENTIFIER,
                    origin = AikenTypedCandidateOrigin.UNIMPORTED,
                    source = AikenTypedCandidateSource.CONST,
                    modulePath = "cardano/assets",
                    autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL
                ),
                expectedType = "Value"
            )

        val presentation = LookupElementPresentation()
        lookup.renderElement(presentation)

        assertEquals("zero", presentation.itemText)
        assertEquals(" from cardano/assets", presentation.tailText)
    }

    @Test
    fun mapsUnimportedIdentifierToReplaceIdentifierInsertionFamily() {
        val family =
            AikenTypedLookupFactory.expectedTypeInsertionFamily(
                AikenTypedExpectedTypeCandidate.Identifier(
                    name = "zero",
                    type = "Value",
                    kind = CompletionSymbolKind.IDENTIFIER,
                    origin = AikenTypedCandidateOrigin.UNIMPORTED,
                    source = AikenTypedCandidateSource.CONST,
                    modulePath = "cardano/assets",
                    autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL
                )
            )

        assertEquals(
            AikenTypedInsertionFamily.ReplaceIdentifier(
                text = "zero",
                autoImportTarget = AikenTypedAutoImportTarget("cardano/assets", "zero")
            ),
            family
        )
    }

    @Test
    fun mapsLocalBindingToLocalBindingTypedRankingCategory() {
        val category =
            AikenTypedLookupFactory.expectedTypeRankingCategory(
                AikenTypedExpectedTypeCandidate.Identifier(
                    name = "remembered",
                    type = "Value",
                    kind = CompletionSymbolKind.IDENTIFIER,
                    origin = AikenTypedCandidateOrigin.LOCAL,
                    source = AikenTypedCandidateSource.BINDING
                )
            )

        assertEquals(AikenTypedCompletionCategory.LOCAL_BINDING, category)
    }

    @Test
    fun mapsQualifiedPipeCandidateToPipeCallInsertionFamilyWithoutAutoImport() {
        val family =
            AikenTypedLookupFactory.pipeInsertionFamily(
                AikenTypedExpectedTypeCandidate.PipeFunction(
                    lookupText = "ops.filter",
                    matchName = "filter",
                    signature = "fn(items: List<Int>, predicate: fn(Int) -> Bool) -> List<Int>",
                    origin = AikenTypedCandidateOrigin.QUALIFIED,
                    modulePath = "demo/ops"
                )
            )

        assertEquals(
            AikenTypedInsertionFamily.PipeCall(
                lookupText = "ops.filter",
                signature = "fn(items: List<Int>, predicate: fn(Int) -> Bool) -> List<Int>",
                autoImportTarget = null
            ),
            family
        )
    }

    @Test
    fun mapsQualifiedPipeCandidateToQualifiedTypedRankingCategory() {
        val category =
            AikenTypedLookupFactory.pipeRankingCategory(
                AikenTypedExpectedTypeCandidate.PipeFunction(
                    lookupText = "ops.filter",
                    matchName = "filter",
                    signature = "fn(items: List<Int>, predicate: fn(Int) -> Bool) -> List<Int>",
                    origin = AikenTypedCandidateOrigin.QUALIFIED,
                    modulePath = "demo/ops"
                )
            )

        assertEquals(AikenTypedCompletionCategory.QUALIFIED_FUNCTION, category)
    }

    @Test
    fun rendersGenericFunctionReturnTypeUsingDisplayPlaceholders() {
        val lookup =
            AikenTypedLookupFactory.createExpectedTypeLookup(
                AikenTypedExpectedTypeCandidate.Function(
                    name = "reduce",
                    signature = "fn(items: List<a>, zero: b, f: fn(b, a) -> b) -> b",
                    origin = AikenTypedCandidateOrigin.UNIMPORTED,
                    modulePath = "collections",
                    autoImportMode = AikenTypedCandidateAutoImportMode.SYMBOL
                ),
                expectedType = "List<Bool>"
            )

        val presentation = LookupElementPresentation()
        lookup.renderElement(presentation)

        assertEquals("reduce", presentation.itemText)
        assertEquals("T", presentation.typeText)
        assertEquals(" from collections", presentation.tailText)
    }

    @Test
    fun normalizesNestedGenericTypeVariablesForDisplay() {
        assertEquals("List<T>", AikenTypeText.normalizeGenericVariablesForDisplay("List<a>"))
        assertEquals("Result<List<T>, Option<U>>", AikenTypeText.normalizeGenericVariablesForDisplay("Result<List<a>, Option<b>>"))
        assertEquals("fn(T, U) -> T", AikenTypeText.normalizeGenericVariablesForDisplay("fn(b, a) -> b"))
    }
}
