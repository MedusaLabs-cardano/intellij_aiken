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

/**
 * Indexes function signatures across all `.ak` files in a project.
 *
 * This enables Ctrl+P parameter info without LSP support.
 */
class AikenFunctionSignatureIndex : FileBasedIndexExtension<String, String>() {
    companion object {
        val NAME: ID<String, String> = ID.create("aiken.functionSignatures")

        fun nameKey(functionName: String): String = "name|$functionName"

        fun moduleKey(modulePath: String, functionName: String): String = "module|$modulePath|$functionName"
    }

    override fun getName(): ID<String, String> = NAME

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
            val text = inputData.contentAsText
            val modulePath = AikenModulePath.fromFile(inputData.file)
            val result = LinkedHashMap<String, String>()

            for (entry in AikenFunctionSignatureExtractor.extractEntries(text)) {
                result[nameKey(entry.name)] = entry.signature
                if (!modulePath.isNullOrBlank()) {
                    result[moduleKey(modulePath, entry.name)] = entry.signature
                }
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
