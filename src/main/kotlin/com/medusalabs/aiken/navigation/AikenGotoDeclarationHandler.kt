package com.medusalabs.aiken.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.indexing.FileBasedIndex
import com.medusalabs.aiken.imports.AikenImportedName
import com.medusalabs.aiken.imports.AikenImportedNameKind
import com.medusalabs.aiken.imports.AikenUseModel
import com.medusalabs.aiken.imports.AikenUseStatement
import com.medusalabs.aiken.imports.AikenUseStatementParser
import com.medusalabs.aiken.index.AIKEN_MODULE_INDEX_NAME
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.project.AikenModulePath
import com.medusalabs.aiken.project.AikenSearchScopes
import com.medusalabs.aiken.psi.AikenNamedElement

class AikenGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val target = AikenGotoNavigationResolver.resolve(element, offset) ?: return null
        return arrayOf(target)
    }
}

private object AikenGotoNavigationResolver {
    fun resolve(sourceElement: PsiElement, offset: Int): PsiElement? {
        val psiFile = sourceElement.containingFile ?: return null
        if (psiFile.fileType != AikenFileType) return null

        val useModel = AikenUseStatementParser.parseModel(psiFile.text)

        resolveImportDeclarationTarget(psiFile, sourceElement, offset, useModel)?.let { return it }

        val namedElement = sourceElement as? AikenNamedElement ?: sourceElement.parent as? AikenNamedElement ?: return null
        val semanticTarget = AikenDeclarationResolver.resolve(namedElement) ?: return null
        val currentFile = psiFile.virtualFile

        if (isSameLocation(namedElement, semanticTarget)) {
            return null
        }

        if (semanticTarget.containingFile?.virtualFile == currentFile) {
            return semanticTarget
        }

        resolveNearestImportPoint(psiFile, namedElement, semanticTarget, useModel)?.let { return it }
        return semanticTarget
    }

    private fun resolveImportDeclarationTarget(
        psiFile: PsiFile,
        sourceElement: PsiElement,
        offset: Int,
        useModel: AikenUseModel
    ): PsiElement? {
        val importedName = importedNameAtOffset(useModel, offset)
        if (importedName != null) {
            return when (importedName.kind) {
                AikenImportedNameKind.ITEM,
                AikenImportedNameKind.ITEM_ALIAS -> resolveImportedSymbolDeclaration(sourceElement, importedName)
                AikenImportedNameKind.MODULE_ALIAS -> resolveImportedModuleFile(sourceElement, importedName.statement.modulePath)
            }
        }

        val moduleStatement =
            useModel.statements.firstOrNull { statement ->
                val range = statement.modulePathRange ?: return@firstOrNull false
                offset >= range.startOffset && offset < range.endOffset
            }
                ?: return null

        return resolveImportedModuleFile(sourceElement, moduleStatement.modulePath)
    }

    private fun resolveNearestImportPoint(
        psiFile: PsiFile,
        namedElement: AikenNamedElement,
        semanticTarget: PsiElement,
        useModel: AikenUseModel
    ): PsiElement? {
        val text = psiFile.text
        val qualifier = findQualifier(text, namedElement.textRange.startOffset)

        if (qualifier != null) {
            val semanticModulePath = AikenModulePath.fromFile(semanticTarget.containingFile?.virtualFile)
            val statement =
                useModel.statements.firstOrNull { importedStatement ->
                    exposedModuleName(importedStatement) == qualifier &&
                        importedStatement.modulePath == semanticModulePath
                }
                    ?: return null
            val range = statement.moduleAliasRange ?: statement.modulePathRange ?: return null
            return resolveElementAt(psiFile, range.startOffset)
        }

        val project = psiFile.project
        val psiManager = PsiManager.getInstance(project)
        val matchingImport =
            useModel.importedNames().firstOrNull { importedName ->
                if (importedName.exposedName != namedElement.text) return@firstOrNull false
                if (importedName.kind == AikenImportedNameKind.MODULE_ALIAS) return@firstOrNull false
                val declaration = resolveImportedSymbolDeclaration(namedElement, importedName) ?: return@firstOrNull false
                psiManager.areElementsEquivalent(declaration, semanticTarget)
            }
                ?: return null

        return resolveElementAt(psiFile, matchingImport.range.startOffset)
    }

    private fun resolveImportedSymbolDeclaration(anchor: PsiElement, importedName: AikenImportedName): PsiElement? {
        val kinds = kindsForImportedName(importedName.sourceName)
        return AikenTopLevelSymbolLookup.findTargets(
            anchor,
            importedName.sourceName,
            kinds,
            setOf(importedName.statement.modulePath)
        ).firstOrNull()
    }

    private fun resolveImportedModuleFile(anchor: PsiElement, modulePath: String): PsiElement? {
        val project = anchor.project
        if (modulePath.isBlank() || DumbService.getInstance(project).isDumb) return null

        val scope = AikenSearchScopes.forElement(anchor)
        val files =
            try {
                FileBasedIndex.getInstance().getContainingFiles(AIKEN_MODULE_INDEX_NAME, modulePath, scope)
            } catch (_: IndexNotReadyException) {
                return null
            }

        val file = files.firstOrNull() ?: return null
        return PsiManager.getInstance(project).findFile(file)
    }

    private fun kindsForImportedName(name: String): Set<AikenTopLevelSymbolKind> =
        if (name.firstOrNull()?.isUpperCase() == true) {
            setOf(AikenTopLevelSymbolKind.TYPE, AikenTopLevelSymbolKind.CONSTRUCTOR)
        } else {
            setOf(AikenTopLevelSymbolKind.CONST, AikenTopLevelSymbolKind.FUNCTION)
        }

    private fun importedNameAtOffset(useModel: AikenUseModel, offset: Int): AikenImportedName? =
        useModel.importedNames().firstOrNull { importedName ->
            offset >= importedName.range.startOffset && offset < importedName.range.endOffset
        }

    private fun exposedModuleName(statement: AikenUseStatement): String =
        statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() } ?: statement.modulePath.substringAfterLast('/')

    private fun resolveElementAt(psiFile: PsiFile, offset: Int): PsiElement? {
        val leaf = psiFile.findElementAt(offset) ?: return null
        return leaf.parent ?: leaf
    }

    private fun isSameLocation(left: PsiElement, right: PsiElement): Boolean {
        val leftFile = left.containingFile?.virtualFile
        val rightFile = right.containingFile?.virtualFile
        return leftFile == rightFile && left.textRange == right.textRange
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
}
