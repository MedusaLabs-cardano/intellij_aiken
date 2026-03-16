package com.medusalabs.aiken.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenModulePath
import java.io.DataInput
import java.io.DataOutput

val AIKEN_EXPORT_INDEX_NAME: ID<String, String> = ID.create("aiken.exports")
private const val AIKEN_EXPORT_INDEX_SEPARATOR: Char = '\u001F'

fun decodeAikenExportIndexValue(value: String): List<String> =
    value
        .split(AIKEN_EXPORT_INDEX_SEPARATOR)
        .filter { it.isNotBlank() }

class AikenExportIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = AIKEN_EXPORT_INDEX_NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = StringExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean = file.fileType == AikenFileType
        }

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { inputData ->
            val modulePath = AikenModulePath.fromFile(inputData.file) ?: return@DataIndexer emptyMap()
            val exportedSymbols = AikenPublicExportExtractor.extract(inputData.contentAsText)
            if (exportedSymbols.isEmpty()) {
                return@DataIndexer emptyMap()
            }

            mapOf(modulePath to exportedSymbols.joinToString(AIKEN_EXPORT_INDEX_SEPARATOR.toString()))
        }

    private object StringExternalizer : DataExternalizer<String> {
        override fun save(out: DataOutput, value: String) {
            out.writeUTF(value)
        }

        override fun read(input: DataInput): String = input.readUTF()
    }
}
