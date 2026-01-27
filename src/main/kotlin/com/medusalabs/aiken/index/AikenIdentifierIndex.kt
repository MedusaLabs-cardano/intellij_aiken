package com.medusalabs.aiken.index

import com.intellij.psi.tree.TokenSet
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor
import com.intellij.psi.TokenType
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.lang.AikenFileType
import java.io.DataInput
import java.io.DataOutput

/**
 * Indexes identifier-like tokens across all `.ak` files in a project to support primitive project-wide completion.
 */
class AikenIdentifierIndex : FileBasedIndexExtension<String, Int>() {
    companion object {
        val NAME: ID<String, Int> = ID.create("aiken.identifiers")
        private val TYPE_TOKENS: TokenSet = TokenSet.create(AikenTokenTypes.TYPE)
        private val FUNCTION_TOKENS: TokenSet = TokenSet.create(AikenTokenTypes.FUNCTION)
        private val FIELD_TOKENS: TokenSet = TokenSet.create(AikenTokenTypes.FIELD)
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
            val lexer = AikenLexing.createLexer()
            lexer.start(text)

            val result = HashMap<String, Int>()
            var collectingBindings: Boolean = false
            var expectedDeclarationKind: Int? = null
            while (lexer.tokenType != null) {
                val tokenType = lexer.tokenType
                if (collectingBindings) {
                    when {
                        tokenType == TokenType.WHITE_SPACE -> {}
                        tokenType == AikenTokenTypes.OPERATOR &&
                            text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString() == "=" -> collectingBindings = false
                        tokenType == AikenTokenTypes.IDENTIFIER -> {
                            val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                            if (word.length >= 2) {
                                result[word] = (result[word] ?: 0) or IdentifierKind.IDENTIFIER
                            }
                        }
                    }
                    lexer.advance()
                    continue
                }
                if (tokenType == AikenTokenTypes.KEYWORD) {
                    val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    expectedDeclarationKind =
                        when (word) {
                            "fn",
                            "test",
                            "bench",
                            "validator" -> IdentifierKind.FUNCTION
                            "type" -> IdentifierKind.TYPE
                            "let",
                            "const" -> null
                            else -> null
                        }
                    collectingBindings = word == "let" || word == "const"
                    lexer.advance()
                    continue
                }

                if (expectedDeclarationKind != null) {
                    when {
                        tokenType == TokenType.WHITE_SPACE -> {
                            lexer.advance()
                            continue
                        }
                        tokenType == AikenTokenTypes.IDENTIFIER ||
                            tokenType == AikenTokenTypes.FUNCTION ||
                            tokenType == AikenTokenTypes.TYPE -> {
                            val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                            if (word.length >= 2) {
                                result[word] = (result[word] ?: 0) or expectedDeclarationKind!!
                            }
                            expectedDeclarationKind = null
                            lexer.advance()
                            continue
                        }
                        else -> {
                            expectedDeclarationKind = null
                        }
                    }
                }

                if (tokenType != null &&
                    (TYPE_TOKENS.contains(tokenType) || FUNCTION_TOKENS.contains(tokenType) || FIELD_TOKENS.contains(tokenType))
                ) {
                    val word = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    if (word.length >= 2) {
                        val kind =
                            when {
                                TYPE_TOKENS.contains(tokenType) -> IdentifierKind.TYPE
                                FUNCTION_TOKENS.contains(tokenType) -> IdentifierKind.FUNCTION
                                else -> IdentifierKind.FIELD
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
