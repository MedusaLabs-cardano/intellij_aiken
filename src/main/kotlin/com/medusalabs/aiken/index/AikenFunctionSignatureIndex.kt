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
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor
import java.io.DataInput
import java.io.DataOutput

val AIKEN_FUNCTION_SIGNATURE_INDEX_NAME: ID<String, String> = ID.create("aiken.functionSignatures")

fun aikenFunctionSignatureNameKey(functionName: String): String = "name|$functionName"

fun aikenFunctionSignatureModuleKey(modulePath: String, functionName: String): String =
    "module|$modulePath|$functionName"

fun aikenFunctionSignatureReturnTypeKey(returnType: String): String = "return|$returnType"

private const val AIKEN_FUNCTION_RETURN_TYPE_ENTRY_SEPARATOR = '\u001e'
private const val AIKEN_FUNCTION_RETURN_TYPE_FIELD_SEPARATOR = '\u001f'

fun encodeAikenFunctionReturnTypeIndexValue(
    modulePath: String,
    functionName: String,
    signature: String
): String = listOf(modulePath, functionName, signature).joinToString(AIKEN_FUNCTION_RETURN_TYPE_FIELD_SEPARATOR.toString())

data class AikenFunctionReturnTypeEntry(
    val modulePath: String,
    val functionName: String,
    val signature: String
)

fun encodeAikenFunctionReturnTypeIndexValues(entries: List<AikenFunctionReturnTypeEntry>): String =
    entries.joinToString(AIKEN_FUNCTION_RETURN_TYPE_ENTRY_SEPARATOR.toString()) { entry ->
        encodeAikenFunctionReturnTypeIndexValue(entry.modulePath, entry.functionName, entry.signature)
    }

fun decodeAikenFunctionReturnTypeIndexValues(value: String): List<AikenFunctionReturnTypeEntry> =
    value.split(AIKEN_FUNCTION_RETURN_TYPE_ENTRY_SEPARATOR)
        .mapNotNull { encodedEntry ->
            val parts = encodedEntry.split(AIKEN_FUNCTION_RETURN_TYPE_FIELD_SEPARATOR, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val (modulePath, functionName, signature) = parts
            if (modulePath.isBlank() || functionName.isBlank() || signature.isBlank()) return@mapNotNull null
            AikenFunctionReturnTypeEntry(modulePath, functionName, signature)
        }

fun aikenFunctionSignatureReturnType(signature: String): String? {
    val closeParenIndex = signature.lastIndexOf(')')
    if (closeParenIndex < 0 || closeParenIndex >= signature.lastIndex) return null
    val suffix = signature.substring(closeParenIndex + 1).trim()
    if (!suffix.startsWith("->")) return null
    return suffix.removePrefix("->").trim().takeIf { it.isNotEmpty() }
}

/**
 * Indexes function signatures across all `.ak` files in a project.
 *
 * This enables Ctrl+P parameter info without LSP support.
 */
class AikenFunctionSignatureIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = AIKEN_FUNCTION_SIGNATURE_INDEX_NAME

    override fun getVersion(): Int = 4

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = StringExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean = file.fileType == AikenFileType
        }

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { inputData ->
            val text = inputData.contentAsText
            val modulePath = AikenModulePath.fromFile(inputData.file)
            val result = LinkedHashMap<String, String>()
            val returnTypeEntries = LinkedHashMap<String, MutableList<AikenFunctionReturnTypeEntry>>()

            for (entry in AikenFunctionSignatureExtractor.extractEntries(text)) {
                result[aikenFunctionSignatureNameKey(entry.name)] = entry.signature
                if (!modulePath.isNullOrBlank()) {
                    result[aikenFunctionSignatureModuleKey(modulePath, entry.name)] = entry.signature
                    aikenFunctionSignatureReturnType(entry.signature)?.let { returnType ->
                        returnTypeEntries.getOrPut(returnType) { ArrayList() } +=
                            AikenFunctionReturnTypeEntry(modulePath, entry.name, entry.signature)
                    }
                }
            }

            for ((returnType, entries) in returnTypeEntries) {
                result[aikenFunctionSignatureReturnTypeKey(returnType)] =
                    encodeAikenFunctionReturnTypeIndexValues(entries)
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
