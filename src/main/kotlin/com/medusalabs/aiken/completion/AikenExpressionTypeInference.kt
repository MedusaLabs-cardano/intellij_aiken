package com.medusalabs.aiken.completion

import com.intellij.psi.PsiElement

internal object AikenExpressionTypeInference {
    interface Resolver {
        fun inferListLiteralType(
            text: String,
            listStart: Int,
            anchor: PsiElement,
            referenceOffset: Int,
            visitedDeclarationOffsets: MutableSet<Int>
        ): String?

        fun resolveVisibleBindingType(
            anchor: PsiElement,
            symbolText: String,
            beforeOffset: Int,
            visitedDeclarationOffsets: MutableSet<Int>
        ): String?

        fun resolveConstType(anchor: PsiElement, symbolText: String): String?

        fun resolveFunctionReturnType(anchor: PsiElement, symbolText: String): String?

        fun resolveFunctionValueType(anchor: PsiElement, symbolText: String): String?

        fun resolveConstructibleResultType(anchor: PsiElement, symbolText: String): String?

        fun resolveFieldAccessType(anchor: PsiElement, containerType: String, fieldName: String): String?

        fun normalizeTypeText(text: String): String

        fun typePatternsAreCompatible(leftType: String, rightType: String): Boolean
    }

    fun inferExpressionType(
        anchor: PsiElement,
        expressionText: String,
        beforeOffset: Int = anchor.textRange.startOffset,
        resolver: Resolver
    ): String? =
        inferExpressionType(anchor, expressionText, beforeOffset, linkedSetOf(), resolver)

    fun inferExpressionType(
        anchor: PsiElement,
        expressionText: String,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? = inferExpressionTypeInternal(expressionText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)

    fun consumeExpressionEnd(text: String, startIndex: Int): Int {
        val start = skipWhitespace(text, startIndex)
        if (start >= text.length) return start

        if (startsWithKeyword(text, "if", start)) {
            return start + consumeIfExpressionLength(text.substring(start))
        }

        if (startsWithKeyword(text, "when", start)) {
            return start + consumeWhenExpressionLength(text.substring(start))
        }

        if (text[start] == '{') {
            return (AikenSyntaxText.findMatchingDelimiter(text, start, '{', '}') ?: (text.length - 1)) + 1
        }

        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = start

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') return index
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
            }
            index++
        }

        return text.length
    }

    private fun inferExpressionTypeInternal(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        var trimmedExpression = normalizeExpressionForInference(expressionText)
        if (trimmedExpression.isBlank()) return null

        while (true) {
            val unwrapped = unwrapSingleParenthesizedExpression(trimmedExpression)
            if (unwrapped == null || unwrapped == trimmedExpression) break
            trimmedExpression = normalizeExpressionForInference(unwrapped)
        }

        inferPipeExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }

        if (startsWithKeyword(trimmedExpression, "if")) {
            inferIfExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        }

        if (startsWithKeyword(trimmedExpression, "when")) {
            inferWhenExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        }

        if (trimmedExpression.startsWith("{")) {
            inferBlockExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        }

        return inferSimpleExpressionType(trimmedExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
    }

    private fun inferPipeExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val pipeOffset = AikenSyntaxText.findLastTopLevelPipeOffset(expressionText) ?: return null
        val rightExpression = expressionText.substring((pipeOffset + 2).coerceAtMost(expressionText.length)).trim()
        if (rightExpression.isEmpty()) return null

        inferPipeTargetType(rightExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        return inferExpressionTypeInternal(rightExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
    }

    private fun inferPipeTargetType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val effectiveExpression = expressionText.removePrefix("..").trimStart()
        val headRange = AikenSyntaxText.leadingQualifiedIdentifierRange(effectiveExpression, 0) ?: return null
        val head = effectiveExpression.substring(headRange.first, headRange.last + 1)
        val nextIndex = skipWhitespace(effectiveExpression, headRange.last + 1)
        val nextChar = effectiveExpression.getOrNull(nextIndex)

        return when (nextChar) {
            '{' -> resolver.resolveConstructibleResultType(anchor, head) ?: head.substringAfterLast('.')
            '(' ->
                inferExpressionTypeInternal(
                    effectiveExpression,
                    anchor,
                    beforeOffset,
                    visitedDeclarationOffsets,
                    resolver
                )
            else ->
                resolver.resolveConstructibleResultType(anchor, head)
                    ?: resolver.resolveFunctionReturnType(anchor, head)
                    ?: resolver.resolveConstType(anchor, head)?.let(AikenFunctionSignatureText::returnType)
                    ?: resolver
                        .resolveVisibleBindingType(anchor, head, beforeOffset, visitedDeclarationOffsets)
                        ?.let(AikenFunctionSignatureText::returnType)
        }
    }

    private fun inferSimpleExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        if (expressionText.isBlank()) return null
        if (startsWithKeyword(expressionText, "expect")) return "Void"
        inferTraceExpressionType(expressionText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        if (startsWithKeyword(expressionText, "Void")) return "Void"
        if (expressionText.startsWith("True") && isTokenBoundary(expressionText, "True".length)) return "Bool"
        if (expressionText.startsWith("False") && isTokenBoundary(expressionText, "False".length)) return "Bool"
        if (isIntLiteral(expressionText)) return "Int"
        if (isStringLiteral(expressionText)) return "String"
        if (isByteArrayLiteral(expressionText)) return "ByteArray"
        inferTupleLiteralType(expressionText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        inferPairLiteralType(expressionText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        inferTupleIndexType(expressionText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        inferFieldAccessType(expressionText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)?.let { return it }
        inferBinaryOperatorType(expressionText)?.let { return it }
        inferUnaryOperatorType(expressionText)?.let { return it }
        if (isTraceIfFalseExpression(expressionText)) return "Bool"
        if (expressionText.startsWith("[")) {
            return resolver.inferListLiteralType(expressionText, 0, anchor, beforeOffset, visitedDeclarationOffsets)
        }

        val effectiveExpression = expressionText.removePrefix("..").trimStart()
        val headRange = AikenSyntaxText.leadingQualifiedIdentifierRange(effectiveExpression, 0) ?: return null
        val head = effectiveExpression.substring(headRange.first, headRange.last + 1)
        val nextIndex = skipWhitespace(effectiveExpression, headRange.last + 1)
        val nextChar = effectiveExpression.getOrNull(nextIndex)

        return when (nextChar) {
            '{' -> resolver.resolveConstructibleResultType(anchor, head) ?: head.substringAfterLast('.')
            '(' -> {
                resolver.resolveConstructibleResultType(anchor, head)
                    ?: resolver.resolveFunctionReturnType(anchor, head)
                    ?: resolver.resolveConstType(anchor, head)
                    ?: resolver.resolveVisibleBindingType(anchor, head, beforeOffset, visitedDeclarationOffsets)
            }
            else -> {
                resolver.resolveConstType(anchor, head)
                    ?: resolver.resolveVisibleBindingType(anchor, head, beforeOffset, visitedDeclarationOffsets)
                    ?: resolver.resolveConstructibleResultType(anchor, head)
                    ?: resolver.resolveFunctionValueType(anchor, head)
            }
        }
    }

    private fun inferTraceExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        if (!startsWithKeyword(expressionText, "trace")) return null

        val continuationText = traceContinuationText(expressionText) ?: return null
        return inferExpressionTypeInternal(continuationText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
    }

    private fun traceContinuationText(expressionText: String): String? {
        var index = skipWhitespace(expressionText, "trace".length)
        if (index >= expressionText.length) return null

        index = consumeExpressionEnd(expressionText, index)
        index = skipWhitespace(expressionText, index)

        if (index < expressionText.length && (expressionText[index] == ':' || expressionText[index] == ',')) {
            index++
            while (true) {
                index = skipWhitespace(expressionText, index)
                if (index >= expressionText.length) return null
                index = consumeExpressionEnd(expressionText, index)
                index = skipWhitespace(expressionText, index)
                if (index >= expressionText.length || expressionText[index] != ',') break
                index++
            }
        }

        val continuationStart = skipWhitespace(expressionText, index)
        if (continuationStart >= expressionText.length) return null
        return normalizeExpressionForInference(expressionText.substring(continuationStart))
    }

    private fun inferTupleLiteralType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        if (!expressionText.startsWith('(')) return null

        val closeParen = AikenSyntaxText.findMatchingDelimiter(expressionText, 0, '(', ')') ?: return null
        if (closeParen != expressionText.lastIndex) return null

        val inner = expressionText.substring(1, closeParen)
        val elementTexts = splitTopLevelDelimitedExpressions(inner, ',')
        if (elementTexts.size < 2) return null

        val elementTypes =
            elementTexts.map { elementText ->
                inferExpressionTypeInternal(elementText, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
                    ?: return null
            }

        return elementTypes.joinToString(prefix = "(", postfix = ")")
    }

    private fun inferPairLiteralType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val headRange = AikenSyntaxText.leadingQualifiedIdentifierRange(expressionText, 0) ?: return null
        val head = expressionText.substring(headRange.first, headRange.last + 1)
        if (head != "Pair" && head != "aiken.Pair") return null

        val openParen = skipWhitespace(expressionText, headRange.last + 1)
        if (openParen >= expressionText.length || expressionText[openParen] != '(') return null

        val closeParen = AikenSyntaxText.findMatchingDelimiter(expressionText, openParen, '(', ')') ?: return null
        if (closeParen != expressionText.lastIndex) return null

        val argumentTexts = splitTopLevelDelimitedExpressions(expressionText.substring(openParen + 1, closeParen), ',')
        if (argumentTexts.size != 2) return null

        val firstType =
            inferExpressionTypeInternal(argumentTexts[0], anchor, beforeOffset, visitedDeclarationOffsets, resolver)
                ?: return null
        val secondType =
            inferExpressionTypeInternal(argumentTexts[1], anchor, beforeOffset, visitedDeclarationOffsets, resolver)
                ?: return null

        return "Pair<$firstType, $secondType>"
    }

    private fun inferTupleIndexType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val access = trailingMemberAccess(expressionText) ?: return null
        val ordinalIndex = parseOrdinalIndex(access.memberText) ?: return null
        val baseType =
            inferExpressionTypeInternal(access.baseExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
                ?: return null

        return tupleIndexedType(baseType, ordinalIndex, resolver)
    }

    private fun inferFieldAccessType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val access = trailingMemberAccess(expressionText) ?: return null
        if (parseOrdinalIndex(access.memberText) != null) return null
        if (access.memberText.firstOrNull()?.isLowerCase() != true) return null

        val baseType =
            inferExpressionTypeInternal(access.baseExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
                ?: return null

        return resolver.resolveFieldAccessType(anchor, baseType, access.memberText)
    }

    private fun tupleIndexedType(
        baseType: String,
        index: Int,
        resolver: Resolver
    ): String? {
        val normalizedType = resolver.normalizeTypeText(baseType)
        if (normalizedType.startsWith('(') && normalizedType.endsWith(')')) {
            val elements = AikenTypeText.splitTopLevelTypeArguments(normalizedType.substring(1, normalizedType.length - 1)) ?: return null
            return elements.getOrNull(index)
        }

        val pairInner = AikenTypeText.unwrapSingleGenericType(normalizedType, "Pair") { text -> resolver.normalizeTypeText(text) }
        if (pairInner != null) {
            val elements = AikenTypeText.splitTopLevelTypeArguments(pairInner) ?: return null
            return elements.getOrNull(index)
        }

        return null
    }

    private fun inferBinaryOperatorType(expressionText: String): String? {
        if (findTopLevelBinaryOperator(expressionText, listOf("||")) != null) return "Bool"
        if (findTopLevelBinaryOperator(expressionText, listOf("&&")) != null) return "Bool"
        if (findTopLevelBinaryOperator(expressionText, listOf("==", "!=", "<=", ">=", "<", ">")) != null) return "Bool"
        if (findTopLevelBinaryOperator(expressionText, listOf("+", "-")) != null) return "Int"
        if (findTopLevelBinaryOperator(expressionText, listOf("*", "/", "%")) != null) return "Int"
        return null
    }

    private fun inferUnaryOperatorType(expressionText: String): String? {
        val start = skipWhitespace(expressionText, 0)
        if (start >= expressionText.length) return null

        return when (expressionText[start]) {
            '!' -> "Bool"
            '-' -> if (isIntLiteral(expressionText)) null else "Int"
            else -> null
        }
    }

    private fun isTraceIfFalseExpression(expressionText: String): Boolean =
        expressionText.endsWith('?') && findTopLevelChar(expressionText, '?') == expressionText.lastIndex

    private fun inferIfExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val thenOpenBrace = findTopLevelChar(expressionText, '{', "if".length) ?: return null
        val thenCloseBrace = AikenSyntaxText.findMatchingDelimiter(expressionText, thenOpenBrace, '{', '}') ?: return null
        val thenBranchType =
            inferBlockBodyType(
                expressionText.substring(thenOpenBrace + 1, thenCloseBrace),
                anchor,
                beforeOffset,
                visitedDeclarationOffsets,
                resolver
            )

        val afterThen = skipWhitespace(expressionText, thenCloseBrace + 1)
        if (!startsWithKeyword(expressionText, "else", afterThen)) return thenBranchType

        val elseStart = skipWhitespace(expressionText, afterThen + "else".length)
        if (elseStart >= expressionText.length) return thenBranchType
        val elseExpression = expressionText.substring(elseStart).trim()
        val elseBranchType = inferExpressionTypeInternal(elseExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)

        return mergeBranchTypes(
            listOf(
                InferredBranch(thenBranchType, expressionText.substring(thenOpenBrace + 1, thenCloseBrace)),
                InferredBranch(elseBranchType, elseExpression)
            ),
            resolver
        )
    }

    private fun inferWhenExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val bodyOpenBrace = findTopLevelChar(expressionText, '{', "when".length) ?: return null
        val bodyCloseBrace = AikenSyntaxText.findMatchingDelimiter(expressionText, bodyOpenBrace, '{', '}') ?: return null
        val bodyText = expressionText.substring(bodyOpenBrace + 1, bodyCloseBrace)
        val branchExpressions = extractWhenBranchExpressions(bodyText)
        if (branchExpressions.isEmpty()) return null

        return mergeBranchTypes(
            branchExpressions.map { branchExpression ->
                InferredBranch(
                    inferExpressionTypeInternal(branchExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver),
                    branchExpression
                )
            },
            resolver
        )
    }

    private fun inferBlockExpressionType(
        expressionText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val closeBrace = AikenSyntaxText.findMatchingDelimiter(expressionText, 0, '{', '}') ?: return null
        if (closeBrace != expressionText.lastIndex) return null
        return inferBlockBodyType(expressionText.substring(1, closeBrace), anchor, beforeOffset, visitedDeclarationOffsets, resolver)
    }

    private fun inferBlockBodyType(
        blockBodyText: String,
        anchor: PsiElement,
        beforeOffset: Int,
        visitedDeclarationOffsets: MutableSet<Int>,
        resolver: Resolver
    ): String? {
        val lastExpression = lastTopLevelBlockExpression(blockBodyText) ?: return null
        return inferExpressionTypeInternal(lastExpression, anchor, beforeOffset, visitedDeclarationOffsets, resolver)
    }

    private fun lastTopLevelBlockExpression(blockBodyText: String): String? =
        topLevelSegments(blockBodyText).lastOrNull { it.isNotBlank() }

    private fun topLevelSegments(text: String): List<String> {
        val segments = ArrayList<String>()
        var segmentStart = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false
                    addTopLevelSegment(text, segmentStart, index, segments)
                    segmentStart = index + 1
                }
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '\n', '\r' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        addTopLevelSegment(text, segmentStart, index, segments)
                        segmentStart = index + 1
                    }
                }
            }
            index++
        }

        addTopLevelSegment(text, segmentStart, text.length, segments)
        return segments
    }

    private fun addTopLevelSegment(
        text: String,
        start: Int,
        endExclusive: Int,
        segments: MutableList<String>
    ) {
        val segment = normalizeExpressionForInference(text.substring(start, endExclusive))
        if (segment.isNotBlank()) {
            segments += segment
        }
    }

    private fun extractWhenBranchExpressions(bodyText: String): List<String> {
        val branches = ArrayList<String>()
        var cursor = 0

        while (cursor < bodyText.length) {
            val arrowOffset = findTopLevelArrow(bodyText, cursor) ?: break
            val expressionStart = skipWhitespace(bodyText, arrowOffset + 2)
            if (expressionStart >= bodyText.length) break
            val expressionEnd = consumeExpressionEnd(bodyText, expressionStart)
            val branchExpression = normalizeExpressionForInference(bodyText.substring(expressionStart, expressionEnd))
            if (branchExpression.isNotBlank()) {
                branches += branchExpression
            }
            cursor = expressionEnd
        }

        return branches
    }

    private fun consumeIfExpressionLength(text: String): Int {
        val thenOpenBrace = findTopLevelChar(text, '{', "if".length) ?: return consumeExpressionEnd(text, 0)
        val thenCloseBrace = AikenSyntaxText.findMatchingDelimiter(text, thenOpenBrace, '{', '}') ?: return text.length
        val afterThen = skipWhitespace(text, thenCloseBrace + 1)
        if (!startsWithKeyword(text, "else", afterThen)) return thenCloseBrace + 1
        val elseStart = skipWhitespace(text, afterThen + "else".length)
        return if (elseStart >= text.length) thenCloseBrace + 1 else consumeExpressionEnd(text, elseStart)
    }

    private fun consumeWhenExpressionLength(text: String): Int {
        val bodyOpenBrace = findTopLevelChar(text, '{', "when".length) ?: return text.length
        val bodyCloseBrace = AikenSyntaxText.findMatchingDelimiter(text, bodyOpenBrace, '{', '}') ?: return text.length
        return bodyCloseBrace + 1
    }

    private fun mergeBranchTypes(
        branches: List<InferredBranch>,
        resolver: Resolver
    ): String? {
        val concreteBranchTypes =
            branches.mapNotNull { branch ->
                branch.type?.let(resolver::normalizeTypeText)?.takeIf { it.isNotEmpty() }
                    ?: branch.expression.takeIf(::isBottomLikeExpression)?.let { null }
            }
        if (concreteBranchTypes.isEmpty()) return null

        val primaryType = concreteBranchTypes.first()
        return if (concreteBranchTypes.all { type -> resolver.typePatternsAreCompatible(type, primaryType) || resolver.typePatternsAreCompatible(primaryType, type) }) {
            primaryType
        } else {
            null
        }
    }

    private fun isBottomLikeExpression(expressionText: String): Boolean {
        val normalized = normalizeExpressionForInference(expressionText)
        return startsWithKeyword(normalized, "fail") || startsWithKeyword(normalized, "todo")
    }

    private fun normalizeExpressionForInference(expressionText: String): String {
        var normalized = expressionText.trim()
        while (normalized.endsWith(",")) {
            normalized = normalized.dropLast(1).trimEnd()
        }
        return normalized
    }

    private fun unwrapSingleParenthesizedExpression(expressionText: String): String? {
        if (!expressionText.startsWith('(')) return null
        val closeParen = AikenSyntaxText.findMatchingDelimiter(expressionText, 0, '(', ')') ?: return null
        if (closeParen != expressionText.lastIndex) return null
        val inner = expressionText.substring(1, closeParen)
        if (containsTopLevelComma(inner)) return null
        return inner
    }

    private fun containsTopLevelComma(text: String): Boolean {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                ',' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return true
            }
            index++
        }

        return false
    }

    private fun findTopLevelBinaryOperator(
        text: String,
        operators: List<String>
    ): BinaryOperatorMatch? {
        val sortedOperators = operators.sortedByDescending(String::length)
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var lastMatch: BinaryOperatorMatch? = null
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                val operator =
                    sortedOperators.firstOrNull { candidate ->
                        text.regionMatches(index, candidate, 0, candidate.length) &&
                            isBinaryOperatorContext(text, index, candidate.length)
                    }
                if (operator != null) {
                    lastMatch = BinaryOperatorMatch(index, operator)
                    index += operator.length
                    continue
                }
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            index++
        }

        return lastMatch
    }

    private fun trailingMemberAccess(text: String): MemberAccess? {
        val dotOffset = findLastTopLevelDot(text) ?: return null
        val baseExpression = normalizeExpressionForInference(text.substring(0, dotOffset))
        val memberText = normalizeExpressionForInference(text.substring(dotOffset + 1))
        if (baseExpression.isBlank() || memberText.isBlank()) return null
        return MemberAccess(baseExpression = baseExpression, memberText = memberText)
    }

    private fun findLastTopLevelDot(text: String): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var lastDot: Int? = null
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && ch == '.') {
                lastDot = index
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            index++
        }

        return lastDot
    }

    private fun parseOrdinalIndex(text: String): Int? {
        val match = Regex("""^([1-9][0-9]*)(st|nd|rd|th)$""").matchEntire(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.minus(1)
    }

    private fun isBinaryOperatorContext(text: String, operatorStart: Int, operatorLength: Int): Boolean {
        val previousIndex = previousNonWhitespaceIndex(text, operatorStart - 1)
        val nextIndex = nextNonWhitespaceIndex(text, operatorStart + operatorLength)
        if (previousIndex < 0 || nextIndex !in text.indices) return false

        return canEndExpression(text[previousIndex]) && canStartExpression(text[nextIndex])
    }

    private fun previousNonWhitespaceIndex(text: String, start: Int): Int {
        var index = start
        while (index >= 0 && text[index].isWhitespace()) index--
        return index
    }

    private fun nextNonWhitespaceIndex(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun canEndExpression(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '_' || ch == '"' || ch == ')' || ch == ']' || ch == '}' || ch == '?'

    private fun canStartExpression(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '_' || ch == '"' || ch == '#' || ch == '@' || ch == '(' || ch == '[' || ch == '{' || ch == '!' || ch == '-'

    private fun splitTopLevelDelimitedExpressions(text: String, delimiter: Char): List<String> {
        val segments = ArrayList<String>()
        var segmentStart = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                delimiter -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    val segment = normalizeExpressionForInference(text.substring(segmentStart, index))
                    if (segment.isNotBlank()) {
                        segments += segment
                    }
                    segmentStart = index + 1
                }
            }
            index++
        }

        val tail = normalizeExpressionForInference(text.substring(segmentStart))
        if (tail.isNotBlank()) {
            segments += tail
        }
        return segments
    }

    private fun findTopLevelChar(text: String, target: Char, fromIndex: Int = 0): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = fromIndex.coerceAtLeast(0)

        while (index < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> {
                    if (target == '{' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
                    braceDepth++
                }
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                else -> if (ch == target && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
            }
            index++
        }

        return null
    }

    private fun findTopLevelArrow(text: String, fromIndex: Int = 0): Int? {
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inLineComment = false
        var index = fromIndex.coerceAtLeast(0)

        while (index + 1 < text.length) {
            val ch = text[index]

            if (inLineComment) {
                if (ch == '\n' || ch == '\r') inLineComment = false
                index++
                continue
            }

            if (inString) {
                if (ch == '\\' && index + 1 < text.length) {
                    index += 2
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }

            if (ch == '/' && index + 1 < text.length && text[index + 1] == '/') {
                inLineComment = true
                index += 2
                continue
            }

            if (ch == '"') {
                inString = true
                index++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '-' -> if (text[index + 1] == '>' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return index
                }
            }
            index++
        }

        return null
    }

    private fun startsWithKeyword(text: String, keyword: String, startIndex: Int = 0): Boolean {
        val start = startIndex.coerceAtLeast(0)
        if (start >= text.length) return false
        if (!text.regionMatches(start, keyword, 0, keyword.length)) return false
        val beforeOk = start == 0 || !isIdentifierChar(text[start - 1])
        val afterIndex = start + keyword.length
        val afterOk = afterIndex >= text.length || !isIdentifierChar(text[afterIndex])
        return beforeOk && afterOk
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun isTokenBoundary(text: String, index: Int): Boolean =
        index >= text.length || (!text[index].isLetterOrDigit() && text[index] != '_')

    private fun isIdentifierChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun isIntLiteral(expressionText: String): Boolean {
        val normalized = expressionText.removePrefix("-")
        return normalized.isNotEmpty() && normalized.all { ch -> ch.isDigit() || ch == '_' }
    }

    private fun isStringLiteral(expressionText: String): Boolean =
        isQuotedLiteral(expressionText, "@\"")

    private fun isByteArrayLiteral(expressionText: String): Boolean =
        isQuotedLiteral(expressionText, "\"") || isQuotedLiteral(expressionText, "#\"")

    private fun isQuotedLiteral(expressionText: String, prefix: String): Boolean {
        if (!expressionText.startsWith(prefix)) return false

        var index = prefix.length
        while (index < expressionText.length) {
            val ch = expressionText[index]
            if (ch == '\\' && index + 1 < expressionText.length) {
                index += 2
                continue
            }
            if (ch == '"') {
                return index == expressionText.lastIndex
            }
            index++
        }

        return false
    }

    private data class InferredBranch(
        val type: String?,
        val expression: String
    )

    private data class BinaryOperatorMatch(
        val offset: Int,
        val operator: String
    )

    private data class MemberAccess(
        val baseExpression: String,
        val memberText: String
    )
}
