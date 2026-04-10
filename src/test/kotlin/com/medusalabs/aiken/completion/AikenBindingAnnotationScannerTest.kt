package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AikenBindingAnnotationScannerTest {
    @Test
    fun findsBindingNameBeforeInitializerWithGenericListType() {
        val source = "let inputs: List<Input> = [input]"
        val initializerOffset = source.indexOf('[')

        val site = AikenBindingAnnotationScanner.findBindingNameBeforeInitializer(source, initializerOffset)

        assertNotNull(site)
        assertEquals("inputs", site!!.name)
        assertEquals(source.indexOf("inputs"), site.nameStart)
    }

    @Test
    fun extractsDeclaredTypeWithNestedFunctionAndGenericArguments() {
        val source = "let predicate: fn(Int, Option<Result<Int, String>>) -> Bool = todo"

        val declaredType = AikenBindingAnnotationScanner.declaredTypeAt(source, source.indexOf("predicate"), "predicate")

        assertEquals("fn(Int, Option<Result<Int, String>>) -> Bool", declaredType)
    }

    @Test
    fun returnsNullForBindingsWithoutAnnotation() {
        val source = "let values = [input]"

        assertNull(AikenBindingAnnotationScanner.declaredTypeAt(source, source.indexOf("values"), "values"))
    }
}
