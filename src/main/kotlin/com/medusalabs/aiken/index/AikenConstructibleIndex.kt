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

val aikenConstructibleIndexName: ID<String, String> = ID.create("aiken.constructibles")
fun aikenConstructibleResultTypeKey(resultType: String): String = "result|$resultType"
private const val aikenConstructibleEntrySeparator: Char = '\u001C'
private const val aikenConstructibleFieldSeparator: Char = '\u001D'
private const val aikenConstructiblePartSeparator: Char = '\u001E'
private const val aikenConstructibleFieldPartSeparator: Char = '\u001F'
private const val aikenConstructibleReturnTypeEntrySeparator: Char = '\u001A'
private const val aikenConstructibleReturnTypeFieldSeparator: Char = '\u001B'

data class AikenConstructibleReturnTypeEntry(
    val modulePath: String,
    val ownerName: String,
    val resultTypeName: String
)

fun encodeAikenConstructibleReturnTypeIndexValues(entries: List<AikenConstructibleReturnTypeEntry>): String =
    entries.joinToString(aikenConstructibleReturnTypeEntrySeparator.toString()) { entry ->
        listOf(entry.modulePath, entry.ownerName, entry.resultTypeName).joinToString(aikenConstructibleReturnTypeFieldSeparator.toString())
    }

fun decodeAikenConstructibleReturnTypeIndexValues(value: String): List<AikenConstructibleReturnTypeEntry> =
    value
        .split(aikenConstructibleReturnTypeEntrySeparator)
        .mapNotNull { encodedEntry ->
            val parts = encodedEntry.split(aikenConstructibleReturnTypeFieldSeparator, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val (modulePath, ownerName, resultTypeName) = parts
            if (modulePath.isBlank() || ownerName.isBlank() || resultTypeName.isBlank()) return@mapNotNull null
            AikenConstructibleReturnTypeEntry(modulePath, ownerName, resultTypeName)
        }

fun decodeAikenConstructibleIndexValue(value: String): List<AikenConstructibleEntry> =
    value
        .split(aikenConstructibleEntrySeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .mapNotNull(::decodeConstructibleEntry)
        .toList()

class AikenConstructibleIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = aikenConstructibleIndexName

    override fun getVersion(): Int = 3

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
            val entries = AikenConstructibleExtractor.extract(inputData.contentAsText)
            if (entries.isEmpty()) {
                return@DataIndexer emptyMap()
            }

            val result = LinkedHashMap<String, String>()
            result[modulePath] = encodeConstructibleEntries(entries)

            val returnTypeEntries = LinkedHashMap<String, MutableList<AikenConstructibleReturnTypeEntry>>()
            for (entry in entries) {
                if (!entry.exported) continue
                returnTypeEntries.getOrPut(entry.resultTypeName) { ArrayList() } +=
                    AikenConstructibleReturnTypeEntry(
                        modulePath = modulePath,
                        ownerName = entry.ownerName,
                        resultTypeName = entry.resultTypeName
                    )
            }

            for ((resultType, typedEntries) in returnTypeEntries) {
                result[aikenConstructibleResultTypeKey(resultType)] = encodeAikenConstructibleReturnTypeIndexValues(typedEntries)
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

private fun encodeConstructibleEntries(entries: List<AikenConstructibleEntry>): String =
    entries.joinToString(aikenConstructibleEntrySeparator.toString()) { entry ->
        listOf(
            entry.ownerName,
            entry.resultTypeName,
            entry.kind.name,
            entry.offset.toString(),
            entry.supportsNamedSyntax.toString(),
            entry.exported.toString(),
            entry.fields.joinToString(aikenConstructibleFieldSeparator.toString()) { field ->
                listOf(field.name, field.type, field.offset.toString()).joinToString(aikenConstructibleFieldPartSeparator.toString())
            }
        ).joinToString(aikenConstructiblePartSeparator.toString())
    }

private fun decodeConstructibleEntry(raw: String): AikenConstructibleEntry? {
    val parts = raw.split(aikenConstructiblePartSeparator)
    if (parts.size < 7) return null

    val kind = runCatching { AikenConstructibleKind.valueOf(parts[2]) }.getOrNull() ?: return null
    val offset = parts[3].toIntOrNull() ?: return null
    val supportsNamedSyntax = parts[4].toBooleanStrictOrNull() ?: return null
    val exported = parts[5].toBooleanStrictOrNull() ?: return null
    val fields =
        parts[6]
            .split(aikenConstructibleFieldSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { fieldRaw ->
                val fieldParts = fieldRaw.split(aikenConstructibleFieldPartSeparator)
                if (fieldParts.size < 3) return@mapNotNull null
                val fieldOffset = fieldParts[2].toIntOrNull() ?: return@mapNotNull null
                AikenConstructibleFieldEntry(
                    name = fieldParts[0],
                    type = fieldParts[1],
                    offset = fieldOffset
                )
            }
            .toList()

    return AikenConstructibleEntry(
        ownerName = parts[0],
        resultTypeName = parts[1],
        kind = kind,
        offset = offset,
        fields = fields,
        supportsNamedSyntax = supportsNamedSyntax,
        exported = exported
    )
}
