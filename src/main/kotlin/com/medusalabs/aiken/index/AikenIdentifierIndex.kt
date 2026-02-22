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
import com.medusalabs.aiken.imports.AikenUseStatementParser
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

    override fun getVersion(): Int = 8

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Int> = IntExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.InputFilter {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean =
                file.fileType == AikenFileType || file.name == "aiken.toml"
        }

    override fun getIndexer(): DataIndexer<String, Int, FileContent> =
        DataIndexer { inputData ->
            val file = inputData.file
            val text = inputData.contentAsText

            if (file.fileType != AikenFileType) {
                return@DataIndexer indexAikenToml(text)
            }

            val lexer = AikenLexing.createLexer()
            lexer.start(text)

            val result = HashMap<String, Int>()
            val testNames = HashSet<String>()
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
                            "bench",
                            "validator" -> IdentifierKind.FUNCTION
                            "test" -> IdentifierKind.TEST
                            "type" -> IdentifierKind.TYPE
                            "let",
                            "const",
                            "expect" -> null
                            else -> null
                        }
                    collectingBindings = word == "let" || word == "const" || word == "expect"
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
                                if (expectedDeclarationKind == IdentifierKind.TEST) {
                                    testNames.add(word)
                                }
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
                        if (kind != IdentifierKind.FUNCTION || !testNames.contains(word)) {
                            result[word] = (result[word] ?: 0) or kind
                        }
                    }
                }

                lexer.advance()
            }

            val useStatements = AikenUseStatementParser.parse(text)
            for (stmt in useStatements) {
                for (item in stmt.items) {
                    val alias = item.alias?.trim().orEmpty()
                    if (alias.length < 2) continue
                    val kind = if (alias.first().isUpperCase()) IdentifierKind.TYPE else IdentifierKind.IDENTIFIER
                    result[alias] = (result[alias] ?: 0) or kind
                }
            }

            result
        }

    private fun indexAikenToml(text: CharSequence): Map<String, Int> {
        val result = HashMap<String, Int>()

        var indexAssignments = false

        for (rawLine in text.toString().lineSequence()) {
            val line = rawLine.trimStart()
            if (line.isEmpty() || line.startsWith("#")) continue

            val statement = stripTomlComment(rawLine).trim()
            if (statement.isEmpty()) continue

            if (statement.startsWith("[")) {
                val (segments, isConfig) = parseTomlTableHeader(statement)
                if (!isConfig) {
                    indexAssignments = false
                    continue
                }

                // Index `[config.xxx.yyy]` (and deeper) as a config "name" (yyy).
                if (segments.size >= 3) {
                    addConfigIdentifier(result, segments.last())
                    indexAssignments = false
                } else {
                    indexAssignments = true
                }
                continue
            }

            if (!indexAssignments) continue

            val eqIndex = statement.indexOf('=')
            if (eqIndex <= 0) continue

            val rawKey = statement.substring(0, eqIndex).trim()
            if (rawKey.isEmpty()) continue

            // Support dotted keys by taking only the top-level name, e.g. `foo.bar = ...` => `foo`.
            val key = rawKey.substringBefore('.').trim()
            if (isValidIdentifier(key)) {
                addConfigIdentifier(result, key)
            }
        }

        return result
    }

    private fun addConfigIdentifier(result: HashMap<String, Int>, name: String) {
        if (name.length < 2) return
        result[name] = (result[name] ?: 0) or IdentifierKind.IDENTIFIER
    }

    private fun parseTomlTableHeader(statement: String): Pair<List<String>, Boolean> {
        val trimmed = statement.trim()
        val isArray = trimmed.startsWith("[[")
        val openLen = if (isArray) 2 else 1
        val closeToken = if (isArray) "]]" else "]"
        val closeIndex = trimmed.indexOf(closeToken, startIndex = openLen)
        if (closeIndex == -1) return emptyList<String>() to false

        val inner = trimmed.substring(openLen, closeIndex).trim()
        if (inner.isEmpty()) return emptyList<String>() to false

        val segments = inner.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        val isConfig = segments.firstOrNull() == "config"
        return segments to isConfig
    }

    private fun stripTomlComment(line: String): String {
        var inDouble = false
        var inSingle = false
        var escaped = false

        for (i in line.indices) {
            val c = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inDouble && c == '\\') {
                escaped = true
                continue
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble
                continue
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle
                continue
            }
            if (!inDouble && !inSingle && c == '#') {
                return line.substring(0, i)
            }
        }

        return line
    }

    private fun isValidIdentifier(key: String): Boolean {
        if (key.isEmpty()) return false
        val first = key[0]
        if (!(first.isLetter() || first == '_')) return false
        for (c in key) {
            if (!(c.isLetterOrDigit() || c == '_')) return false
        }
        return true
    }

    private object IntExternalizer : DataExternalizer<Int> {
        override fun save(out: DataOutput, value: Int) {
            out.writeInt(value)
        }

        override fun read(input: DataInput): Int = input.readInt()
    }
}
