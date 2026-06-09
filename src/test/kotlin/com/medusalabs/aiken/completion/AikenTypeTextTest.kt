package com.medusalabs.aiken.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AikenTypeTextTest {
    @Test
    fun unwrapsSingleGenericTypeAfterNormalizingWhitespace() {
        assertEquals(
            "Pair<Int, Bool>",
            AikenTypeText.unwrapSingleGenericType("  List<Pair<Int, Bool> >  ", "List")
        )
    }

    @Test
    fun splitsTopLevelTypeArgumentsWithoutBreakingNestedStructures() {
        assertEquals(
            listOf("Pair<Int, Bool>", "Option<Result<List<Int>>>", "Validator(Int, List<Bool>)"),
            AikenTypeText.splitTopLevelTypeArguments("Pair<Int, Bool>, Option<Result<List<Int>>>, Validator(Int, List<Bool>)")
        )
    }

    @Test
    fun rejectsUnbalancedTypeArguments() {
        assertNull(AikenTypeText.splitTopLevelTypeArguments("Pair<Int, Bool"))
    }
}
