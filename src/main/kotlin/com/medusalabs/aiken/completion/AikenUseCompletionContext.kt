package com.medusalabs.aiken.completion

import com.intellij.openapi.util.TextRange
import com.medusalabs.aiken.imports.AikenUseStatement
import com.medusalabs.aiken.imports.AikenUseStatementParser

internal enum class AikenUseCompletionMode {
    MODULE,
    ENTITY
}

internal data class AikenUseCompletionContext(
    val mode: AikenUseCompletionMode,
    val statementRange: TextRange,
    val replaceRange: TextRange,
    val modulePath: String?,
    val existingItems: Set<String>
) {
    fun currentPrefix(text: String): String {
        val start = replaceRange.startOffset.coerceIn(0, text.length)
        val end = replaceRange.endOffset.coerceIn(start, text.length)
        return text.substring(start, end)
    }

    companion object {
        fun detect(text: String, offset: Int): AikenUseCompletionContext? {
            val lineContext = detectUseLineContext(text, offset)
            if (lineContext?.mode == AikenUseCompletionMode.ENTITY) {
                return lineContext
            }

            val parsedStatement =
                AikenUseStatementParser.parse(text).firstOrNull { stmt ->
                    offset >= stmt.statementRange.startOffset &&
                        offset <= (stmt.statementRange.endOffset + 1).coerceAtMost(text.length)
                }

            if (parsedStatement == null) return lineContext

            val inImportList = isInsideImportList(parsedStatement, text, offset)

            return if (inImportList) {
                AikenUseCompletionContext(
                    mode = AikenUseCompletionMode.ENTITY,
                    statementRange = parsedStatement.statementRange,
                    replaceRange = identifierRange(text, offset, parsedStatement.statementRange),
                    modulePath = parsedStatement.modulePath,
                    existingItems = parsedStatement.items.mapTo(LinkedHashSet()) { it.name }
                )
            } else {
                val bounds =
                    parsedStatement.modulePathRange
                        ?: TextRange(
                            parsedStatement.statementRange.startOffset,
                            parsedStatement.statementRange.endOffset.coerceAtLeast(offset)
                        )

                AikenUseCompletionContext(
                    mode = AikenUseCompletionMode.MODULE,
                    statementRange = parsedStatement.statementRange,
                    replaceRange = modulePathRange(text, offset, bounds),
                    modulePath = parsedStatement.modulePath,
                    existingItems = emptySet()
                )
            }
        }

        private fun detectUseLineContext(text: String, offset: Int): AikenUseCompletionContext? {
            if (text.isEmpty()) return null
            val anchor = offset.coerceIn(0, text.length)
            val lineStart = text.lastIndexOf('\n', (anchor - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', anchor).let { if (it == -1) text.length else it }
            if (lineStart >= lineEnd) return null

            val line = text.substring(lineStart, lineEnd)
            val trimmedStart = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }
            if (!line.regionMatches(trimmedStart, "use", 0, 3)) return null
            val afterUseInLine = trimmedStart + 3
            if (afterUseInLine < line.length && !line[afterUseInLine].isWhitespace()) return null

            val afterUse = lineStart + trimmedStart + 3
            if (anchor < afterUse) return null

            val statementRange = TextRange(lineStart + trimmedStart, lineEnd)
            val dotBraceInLine = line.indexOf(".{")
            val dotBraceGlobal = if (dotBraceInLine >= 0) lineStart + dotBraceInLine else -1

            if (dotBraceGlobal >= 0 && anchor >= dotBraceGlobal + 2) {
                val modulePath =
                    text.substring(afterUse, dotBraceGlobal)
                        .trim()
                        .trimEnd('.')
                        .takeIf { it.isNotBlank() }

                val itemBounds = TextRange((dotBraceGlobal + 2).coerceAtMost(lineEnd), lineEnd)

                return AikenUseCompletionContext(
                    mode = AikenUseCompletionMode.ENTITY,
                    statementRange = statementRange,
                    replaceRange = identifierRange(text, anchor, itemBounds),
                    modulePath = modulePath,
                    existingItems = extractExistingItemNames(text.substring(itemBounds.startOffset, itemBounds.endOffset))
                )
            }

            val moduleEnd = if (dotBraceGlobal >= 0) dotBraceGlobal else lineEnd
            val moduleBounds = TextRange(afterUse.coerceAtMost(moduleEnd), moduleEnd)
            val modulePath =
                if (moduleBounds.startOffset < moduleBounds.endOffset) {
                    text.substring(moduleBounds.startOffset, moduleBounds.endOffset).trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }

            return AikenUseCompletionContext(
                mode = AikenUseCompletionMode.MODULE,
                statementRange = statementRange,
                replaceRange = modulePathRange(text, anchor, moduleBounds),
                modulePath = modulePath,
                existingItems = emptySet()
            )
        }

        private fun isInsideImportList(
            statement: AikenUseStatement,
            text: String,
            offset: Int
        ): Boolean {
            val start = statement.statementRange.startOffset.coerceIn(0, text.length)
            val end = offset.coerceIn(start, text.length)
            var depth = 0
            for (i in start until end) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> depth = (depth - 1).coerceAtLeast(0)
                }
            }
            return depth > 0
        }

        private fun identifierRange(text: String, offset: Int, bounds: TextRange): TextRange {
            val startBound = bounds.startOffset.coerceAtLeast(0)
            val endBound = bounds.endOffset.coerceAtMost(text.length).coerceAtLeast(startBound)
            val caret = offset.coerceIn(startBound, endBound)

            if (caret in startBound until endBound) {
                val ch = text[caret]
                if (ch == ',' || ch == '{' || ch == '}' || ch.isWhitespace()) {
                    return TextRange(caret, caret)
                }
            }
            if (caret > startBound) {
                val prev = text[caret - 1]
                if (prev == ',' || prev == '{' || prev == '}') {
                    return TextRange(caret, caret)
                }
            }

            var start = caret
            while (start > startBound && isIdentifierChar(text[start - 1])) start--
            var end = caret
            while (end < endBound && isIdentifierChar(text[end])) end++
            return TextRange(start, end)
        }

        private fun modulePathRange(text: String, offset: Int, bounds: TextRange): TextRange {
            val startBound = bounds.startOffset.coerceAtLeast(0)
            val endBound = bounds.endOffset.coerceAtMost(text.length).coerceAtLeast(startBound)
            var start = offset.coerceIn(startBound, endBound)
            while (start > startBound && isModulePathChar(text[start - 1])) start--
            var end = offset.coerceIn(startBound, endBound)
            while (end < endBound && isModulePathChar(text[end])) end++
            return TextRange(start, end)
        }

        private fun extractExistingItemNames(rawItems: String): Set<String> {
            if (rawItems.isBlank()) return emptySet()
            val names = LinkedHashSet<String>()
            for (segment in rawItems.split(',')) {
                val trimmed = segment.trim()
                if (trimmed.isBlank()) continue
                val name = trimmed.substringBefore(" as ").trim()
                if (name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '_' }) {
                    names += name
                }
            }
            return names
        }

        private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

        private fun isModulePathChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '/'
    }
}
