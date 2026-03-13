package com.medusalabs.aiken.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.psi.PsiElement
import com.medusalabs.aiken.imports.AikenUseStatement
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenModulePath
import java.io.DataInput
import java.io.DataOutput

/**
 * Indexes import statements for both symbol-aware and legacy name-based lookups.
 */
class AikenImportIndex : FileBasedIndexExtension<String, Int>() {
    companion object {
        val NAME: ID<String, Int> = ID.create("aiken.imports")

        fun moduleKey(modulePath: String): String = "module|$modulePath"

        fun symbolKey(modulePath: String, symbolName: String): String = "symbol|$modulePath|$symbolName"

        fun moduleAliasKey(modulePath: String, alias: String): String = "moduleAlias|$modulePath|$alias"

        fun itemAliasKey(modulePath: String, symbolName: String, alias: String): String =
            "itemAlias|$modulePath|$symbolName|$alias"

        fun lookupKeysForDeclaration(element: PsiElement): Set<String> {
            val name = element.text.trim()
            val modulePath = AikenModulePath.fromFile(element.containingFile?.virtualFile)
            if (name.isBlank() || modulePath.isNullOrBlank()) return emptySet()

            return linkedSetOf(
                symbolKey(modulePath, name),
                moduleKey(modulePath)
            )
        }

        private fun allKeys(statement: AikenUseStatement): Sequence<String> = sequence {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isBlank()) return@sequence

            yield(moduleKey(modulePath))

            val moduleAlias = statement.moduleAlias?.trim().orEmpty()
            if (moduleAlias.isNotEmpty()) {
                yield(moduleAliasKey(modulePath, moduleAlias))
                yield(moduleAlias)
            }

            for (item in statement.items) {
                val itemName = item.name.trim()
                if (itemName.isBlank()) continue

                yield(symbolKey(modulePath, itemName))
                yield(itemName)

                val alias = item.alias?.trim().orEmpty()
                if (alias.isNotEmpty()) {
                    yield(itemAliasKey(modulePath, itemName, alias))
                    yield(alias)
                }
            }
        }
    }

    override fun getName(): ID<String, Int> = NAME

    override fun getVersion(): Int = 4

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Int> = IntExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean = file.fileType == AikenFileType
        }

    override fun getIndexer(): DataIndexer<String, Int, FileContent> =
        DataIndexer { inputData ->
            val text = inputData.contentAsText
            val importModel = AikenUseStatementParser.parseModel(text)

            val result = HashMap<String, Int>()
            for (statement in importModel.statements) {
                for (key in allKeys(statement)) {
                    if (key.isNotBlank()) {
                        result[key] = 1
                    }
                }
            }

            result
        }

    private object IntExternalizer : DataExternalizer<Int> {
        override fun save(out: DataOutput, value: Int) {
            out.writeInt(value)
        }

        override fun read(input: DataInput): Int = input.readInt()
    }
}
