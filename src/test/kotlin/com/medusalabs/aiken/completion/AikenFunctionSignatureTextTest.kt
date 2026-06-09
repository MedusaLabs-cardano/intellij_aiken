package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AikenFunctionSignatureTextTest {
    @Test
    fun parsesParametersWithNestedFunctionTypes() {
        val signature = "filter(items: List<a>, predicate: fn(a, Option<Result<Int, String>>) -> Bool) -> List<a>"

        assertEquals(
            listOf(
                AikenFunctionSignatureParameter("items", "List<a>"),
                AikenFunctionSignatureParameter("predicate", "fn(a, Option<Result<Int, String>>) -> Bool")
            ),
            AikenFunctionSignatureText.parameters(signature)
        )
    }

    @Test
    fun returnsIndexedParameterTypeFromNestedSignature() {
        val signature = "map(items: List<a>, mapper: fn(a) -> b, fallback: Option<b>) -> List<b>"

        assertEquals("fn(a) -> b", AikenFunctionSignatureText.parameterTypeAt(signature, 1))
        assertEquals("Option<b>", AikenFunctionSignatureText.parameterTypeAt(signature, 2))
    }

    @Test
    fun returnsNullForOutOfRangeParameterIndex() {
        assertNull(AikenFunctionSignatureText.parameterTypeAt("length(items: List<a>) -> Int", 3))
    }

    @Test
    fun buildsFunctionTypeFromSignature() {
        val signature = "map(items: List<a>, mapper: fn(a) -> b) -> List<b>"

        assertEquals(
            "fn(List<a>, fn(a) -> b) -> List<b>",
            AikenFunctionSignatureText.functionType(signature)
        )
    }
}
