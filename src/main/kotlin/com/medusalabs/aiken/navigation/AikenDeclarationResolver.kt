package com.medusalabs.aiken.navigation

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.scope.AikenLocalScopeAnalyzer

object AikenDeclarationResolver {
    fun resolve(element: PsiElement): PsiElement? {
        val psiFile = element.containingFile ?: return null
        if (psiFile.fileType != AikenFileType) return null
        val type = element.node?.elementType ?: return null
        val name = element.text
        if (name.isBlank()) return null

        val project = element.project
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        return when (type) {
            AikenTokenTypes.IDENTIFIER,
            AikenTokenTypes.FIELD -> resolveLocalOrGlobal(element, document, name)
            AikenTokenTypes.FUNCTION -> resolveLocalOrGlobal(element, document, name)
            AikenTokenTypes.TYPE ->
                resolveImportAlias(psiFile, name) ?: resolveGlobal(
                    element,
                    name,
                    setOf(AikenTopLevelSymbolKind.TYPE, AikenTopLevelSymbolKind.CONSTRUCTOR)
                )
            else -> null
        }
    }

    private fun resolveLocalOrGlobal(element: PsiElement, document: Document, name: String): PsiElement? {
        val caretOffset = element.textRange.startOffset
        val psiFile = element.containingFile ?: return null
        val qualifier = findQualifier(psiFile.text, caretOffset)
        val localOffset = findLocalDeclarationOffset(document, name, caretOffset)
        if (localOffset != null) {
            return resolveNamedElementAt(psiFile, localOffset)
        }

        val targets = findGlobalTargets(element, name, setOf(AikenTopLevelSymbolKind.CONST, AikenTopLevelSymbolKind.FUNCTION))
        if (qualifier == null) {
            preferSameFile(element, targets)?.let { return it }
            resolveImportAlias(psiFile, name)?.let { return it }
        }

        findImportedTargets(
            element,
            name,
            setOf(AikenTopLevelSymbolKind.CONST, AikenTopLevelSymbolKind.FUNCTION)
        ).firstOrNull()?.let { return it }

        return if (qualifier == null) {
            targets.firstOrNull()
        } else {
            preferSameFile(element, targets) ?: targets.firstOrNull()
        }
    }

    private fun resolveImportAlias(psiFile: com.intellij.psi.PsiFile, name: String): PsiElement? {
        if (name.isBlank()) return null
        if (psiFile.fileType != AikenFileType) return null

        val aliasOffset = AikenUseStatementParser.parseModel(psiFile.text).findAliasDeclarationOffset(name)
                ?: return null

        return resolveNamedElementAt(psiFile, aliasOffset)
    }

    private fun resolveGlobal(element: PsiElement, name: String, kinds: Set<AikenTopLevelSymbolKind>): PsiElement? {
        findImportedTargets(element, name, kinds).firstOrNull()?.let { return it }
        val targets = findGlobalTargets(element, name, kinds)
        return preferSameFile(element, targets) ?: targets.firstOrNull()
    }

    private fun preferSameFile(element: PsiElement, targets: List<PsiElement>): PsiElement? {
        val vf = element.containingFile?.virtualFile ?: return null
        return targets.firstOrNull { it.containingFile?.virtualFile == vf }
    }

    private fun findGlobalTargets(anchor: PsiElement, name: String, kinds: Set<AikenTopLevelSymbolKind>): List<PsiElement> =
        AikenTopLevelSymbolLookup.findTargets(anchor, name, kinds)

    private fun findImportedTargets(
        element: PsiElement,
        name: String,
        kinds: Set<AikenTopLevelSymbolKind>
    ): List<PsiElement> {
        val psiFile = element.containingFile ?: return emptyList()
        val useModel = AikenUseStatementParser.parseModel(psiFile.text)
        val qualifier = findQualifier(psiFile.text, element.textRange.startOffset)
        val targets = useModel.resolveSymbolTargets(name, qualifier)
        if (targets.isEmpty()) return emptyList()

        return targets.flatMap { target ->
            AikenTopLevelSymbolLookup.findTargets(
                element,
                target.symbolName,
                kinds,
                setOf(target.modulePath)
            )
        }
    }

    private fun findQualifier(text: CharSequence, symbolOffset: Int): String? {
        var index = symbolOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0 || text[index] != '.') return null

        index--
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return null

        val end = index + 1
        while (index >= 0 && (text[index].isLetterOrDigit() || text[index] == '_')) index--
        val start = index + 1

        return if (start < end) text.subSequence(start, end).toString() else null
    }

    private fun resolveNamedElementAt(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? =
        AikenTopLevelSymbolLookup.resolveNamedElementAt(psiFile, offset)

    private fun findLocalDeclarationOffset(
        document: Document,
        name: String,
        caretOffset: Int
    ): Int? = AikenLocalScopeAnalyzer.findDeclarationOffset(document, name, caretOffset)
}
