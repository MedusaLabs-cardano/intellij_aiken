package com.medusalabs.aiken.imports

import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenUseStatementParserTest : AikenPlatformTestCase() {
    @Test
    fun parsesModuleAliasAfterImportList() {
        val statements = AikenUseStatementParser.parse(
            "use aiken/collection/list.{count as my_count} as native_list\n"
        )

        assertSize(1, statements)

        val statement = statements.single()
        assertEquals("aiken/collection/list", statement.modulePath)
        assertEquals("native_list", statement.moduleAlias)
        assertNotNull(statement.moduleAliasRange)
        assertSize(1, statement.items)

        val item = statement.items.single()
        assertEquals("count", item.name)
        assertEquals("my_count", item.alias)
        assertNotNull(item.aliasRange)
    }

    @Test
    fun parsesModuleAliasWithoutImportList() {
        val statements = AikenUseStatementParser.parse(
            "use aiken/collection/list as list_mod\n"
        )

        assertSize(1, statements)

        val statement = statements.single()
        assertEquals("aiken/collection/list", statement.modulePath)
        assertEquals("list_mod", statement.moduleAlias)
        assertNotNull(statement.moduleAliasRange)
        assertTrue(statement.items.isEmpty())
    }

    @Test
    fun buildsImportModelForAliasesAndImportedItems() {
        val text = "use aiken/collection/list.{count as my_count, map} as native_list\n"
        val model = AikenUseStatementParser.parseModel(text)

        val names = model.importedNames()
        assertEquals(listOf("native_list", "count", "my_count", "map"), names.map { it.exposedName })
        assertEquals(text.indexOf("native_list"), model.findAliasDeclarationOffset("native_list"))
        assertSize(1, model.importedItemNameRanges("count"))
        assertSize(1, model.importedItemNameRanges("map"))
        assertEmpty(model.importedItemNameRanges("my_count"))
    }
}
