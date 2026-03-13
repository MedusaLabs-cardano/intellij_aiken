package com.medusalabs.aiken.imports

import com.intellij.openapi.util.TextRange

data class AikenUseItem(
    val name: String,
    val nameRange: TextRange,
    val alias: String? = null,
    val aliasRange: TextRange? = null
)

data class AikenUseStatement(
    val modulePath: String,
    val modulePathRange: TextRange?,
    val moduleAlias: String? = null,
    val moduleAliasRange: TextRange? = null,
    val items: List<AikenUseItem>,
    val statementRange: TextRange
)

enum class AikenImportedNameKind {
    MODULE_ALIAS,
    ITEM,
    ITEM_ALIAS
}

data class AikenImportedName(
    val exposedName: String,
    val range: TextRange,
    val kind: AikenImportedNameKind,
    val sourceName: String,
    val statement: AikenUseStatement
)

data class AikenImportedSymbolTarget(
    val modulePath: String,
    val symbolName: String
)

data class AikenImportedModuleTarget(
    val modulePath: String
)

data class AikenUseModel(
    val statements: List<AikenUseStatement>
) {
    fun isModulePathOffset(offset: Int): Boolean =
        statements.any { statement ->
            val range = statement.modulePathRange ?: return@any false
            offset >= range.startOffset && offset < range.endOffset
        }

    fun importedNames(): List<AikenImportedName> {
        val result = ArrayList<AikenImportedName>()
        for (statement in statements) {
            val moduleAlias = statement.moduleAlias?.trim().orEmpty()
            if (moduleAlias.isNotEmpty()) {
                val range = statement.moduleAliasRange ?: continue
                result.add(
                    AikenImportedName(
                        exposedName = moduleAlias,
                        range = range,
                        kind = AikenImportedNameKind.MODULE_ALIAS,
                        sourceName = statement.modulePath,
                        statement = statement
                    )
                )
            }

            for (item in statement.items) {
                if (item.name.isNotBlank()) {
                    result.add(
                        AikenImportedName(
                            exposedName = item.name,
                            range = item.nameRange,
                            kind = AikenImportedNameKind.ITEM,
                            sourceName = item.name,
                            statement = statement
                        )
                    )
                }

                val alias = item.alias?.trim().orEmpty()
                if (alias.isEmpty()) continue
                val aliasRange = item.aliasRange ?: continue
                result.add(
                    AikenImportedName(
                        exposedName = alias,
                        range = aliasRange,
                        kind = AikenImportedNameKind.ITEM_ALIAS,
                        sourceName = item.name,
                        statement = statement
                    )
                )
            }
        }
        return result
    }

    fun findAliasDeclarationOffset(name: String): Int? =
        importedNames()
            .firstOrNull { entry ->
                entry.exposedName == name &&
                    entry.kind != AikenImportedNameKind.ITEM
            }
            ?.range
            ?.startOffset

    fun importedItemNameRanges(name: String): List<TextRange> =
        importedNames()
            .asSequence()
            .filter { entry ->
                entry.kind == AikenImportedNameKind.ITEM &&
                    entry.sourceName == name
            }
            .map { it.range }
            .toList()

    fun resolveSymbolTargets(symbolName: String, qualifier: String?): List<AikenImportedSymbolTarget> {
        val normalizedName = symbolName.trim()
        if (normalizedName.isEmpty()) return emptyList()

        val normalizedQualifier = qualifier?.trim().orEmpty().ifEmpty { null }
        val results = LinkedHashSet<AikenImportedSymbolTarget>()

        for (statement in statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isEmpty()) continue

            if (normalizedQualifier != null) {
                val exposedModuleName = statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: modulePath.substringAfterLast('/')
                if (exposedModuleName == normalizedQualifier) {
                    results += AikenImportedSymbolTarget(modulePath, normalizedName)
                }
                continue
            }

            for (item in statement.items) {
                val itemName = item.name.trim()
                if (itemName.isEmpty()) continue

                val exposedName = item.alias?.trim().takeUnless { it.isNullOrEmpty() } ?: itemName
                if (exposedName == normalizedName) {
                    results += AikenImportedSymbolTarget(modulePath, itemName)
                }
            }
        }

        return results.toList()
    }

    fun resolveCallableTargets(callName: String, qualifier: String?): List<AikenImportedSymbolTarget> =
        resolveSymbolTargets(callName, qualifier)

    fun resolveModuleTargets(qualifier: String): List<AikenImportedModuleTarget> {
        val normalizedQualifier = qualifier.trim()
        if (normalizedQualifier.isEmpty()) return emptyList()

        val results = LinkedHashSet<AikenImportedModuleTarget>()
        for (statement in statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isEmpty()) continue

            val exposedModuleName =
                statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: modulePath.substringAfterLast('/')

            if (exposedModuleName == normalizedQualifier) {
                results += AikenImportedModuleTarget(modulePath)
            }
        }

        return results.toList()
    }
}
