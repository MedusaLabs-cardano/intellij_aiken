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

val aikenTopLevelSymbolIndexName: ID<String, Int> = ID.create("aiken.topLevelSymbols")

fun aikenTopLevelSymbolNameKey(kind: AikenTopLevelSymbolKind, symbolName: String): String =
    "name|${kind.name.lowercase()}|$symbolName"

fun aikenTopLevelSymbolModuleKey(kind: AikenTopLevelSymbolKind, modulePath: String, symbolName: String): String =
    "module|${kind.name.lowercase()}|$modulePath|$symbolName"

class AikenTopLevelSymbolIndex : FileBasedIndexExtension<String, Int>() {
    override fun getName(): ID<String, Int> = aikenTopLevelSymbolIndexName

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Int> = IntExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean = file.fileType == AikenFileType
        }

    override fun getIndexer(): DataIndexer<String, Int, FileContent> =
        DataIndexer { inputData ->
            val modulePath = AikenModulePath.fromFile(inputData.file)
            val result = LinkedHashMap<String, Int>()

            for (entry in AikenTopLevelSymbolExtractor.extract(inputData.contentAsText)) {
                result[aikenTopLevelSymbolNameKey(entry.kind, entry.name)] = entry.offset
                if (!modulePath.isNullOrBlank()) {
                    result[aikenTopLevelSymbolModuleKey(entry.kind, modulePath, entry.name)] = entry.offset
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
