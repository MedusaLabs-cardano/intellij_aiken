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

val AIKEN_CONST_TYPE_INDEX_NAME: ID<String, String> = ID.create("aiken.constTypes")

fun aikenConstTypeModuleKey(modulePath: String, constName: String): String = "module|$modulePath|$constName"

fun aikenConstTypeTypeKey(type: String): String = "type|$type"

private const val AIKEN_CONST_TYPE_ENTRY_SEPARATOR = '\u001e'
private const val AIKEN_CONST_TYPE_FIELD_SEPARATOR = '\u001f'

data class AikenConstTypeIndexEntry(
    val modulePath: String,
    val constName: String,
    val type: String,
    val exported: Boolean
)

fun encodeAikenConstTypeIndexValues(entries: List<AikenConstTypeIndexEntry>): String =
    entries.joinToString(AIKEN_CONST_TYPE_ENTRY_SEPARATOR.toString()) { entry ->
        listOf(entry.modulePath, entry.constName, entry.type, entry.exported.toString()).joinToString(AIKEN_CONST_TYPE_FIELD_SEPARATOR.toString())
    }

fun decodeAikenConstTypeIndexValues(value: String): List<AikenConstTypeIndexEntry> =
    value.split(AIKEN_CONST_TYPE_ENTRY_SEPARATOR)
        .mapNotNull { encoded ->
            val parts = encoded.split(AIKEN_CONST_TYPE_FIELD_SEPARATOR, limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val (modulePath, constName, type, exportedRaw) = parts
            if (modulePath.isBlank() || constName.isBlank() || type.isBlank()) return@mapNotNull null
            val exported = exportedRaw.toBooleanStrictOrNull() ?: return@mapNotNull null
            AikenConstTypeIndexEntry(modulePath, constName, type, exported)
        }

class AikenConstTypeIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = AIKEN_CONST_TYPE_INDEX_NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = StringExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean = file.fileType == AikenFileType
        }

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { inputData ->
            val modulePath = AikenModulePath.fromFile(inputData.file)
            val result = LinkedHashMap<String, String>()
            val byType = LinkedHashMap<String, MutableList<AikenConstTypeIndexEntry>>()

            for (entry in AikenConstTypeExtractor.extract(inputData.contentAsText)) {
                if (modulePath.isNullOrBlank()) continue
                if (!entry.exported) continue
                result[aikenConstTypeModuleKey(modulePath, entry.name)] = entry.type
                byType.getOrPut(entry.type) { ArrayList() } += AikenConstTypeIndexEntry(modulePath, entry.name, entry.type, exported = true)
            }

            for ((type, entries) in byType) {
                result[aikenConstTypeTypeKey(type)] = encodeAikenConstTypeIndexValues(entries)
            }

            result
        }

    private object StringExternalizer : DataExternalizer<String> {
        override fun save(out: DataOutput, value: String) {
            out.writeUTF(value)
        }

        override fun read(input: DataInput): String = input.readUTF()
    }
}
