package com.medusalabs.aiken.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor
import com.intellij.psi.tree.TokenSet
import com.medusalabs.aiken.highlight.lexer.UplcLexing
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.lang.UplcFileType
import java.io.DataInput
import java.io.DataOutput

val UPLC_IDENTIFIER_INDEX_NAME: ID<String, Int> = ID.create("uplc.identifiers")
private val UPLC_IDENTIFIER_INDEX_TOKENS: TokenSet =
    TokenSet.create(
        UplcTokenTypes.IDENTIFIER,
        UplcTokenTypes.TYPE,
        UplcTokenTypes.FUNCTION,
        UplcTokenTypes.FIELD
    )

/**
 * Indexes identifier-like tokens across all `.uplc` files in a project.
 */
class UplcIdentifierIndex : FileBasedIndexExtension<String, Int>() {
    override fun getName(): ID<String, Int> = UPLC_IDENTIFIER_INDEX_NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Int> = IntExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean = file.fileType == UplcFileType
        }

    override fun getIndexer(): DataIndexer<String, Int, FileContent> =
        DataIndexer { inputData ->
            val text = inputData.contentAsText
            val lexer = UplcLexing.createLexer()
            lexer.start(text)

            val result = HashMap<String, Int>()
            while (lexer.tokenType != null) {
                val tokenType = lexer.tokenType
                if (tokenType != null && UPLC_IDENTIFIER_INDEX_TOKENS.contains(tokenType)) {
                    val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    if (word.length >= 2) {
                        val kind =
                            when (tokenType) {
                                UplcTokenTypes.TYPE -> IdentifierKind.TYPE
                                UplcTokenTypes.FUNCTION -> IdentifierKind.FUNCTION
                                UplcTokenTypes.FIELD -> IdentifierKind.FIELD
                                else -> IdentifierKind.IDENTIFIER
                            }
                        result[word] = (result[word] ?: 0) or kind
                    }
                }
                lexer.advance()
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
