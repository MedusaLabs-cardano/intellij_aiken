package com.medusalabs.aiken.navigation

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import com.medusalabs.aiken.index.AikenTopLevelSymbolIndex
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.project.AikenSearchScopes

object AikenTopLevelSymbolLookup {
    fun findTargets(
        anchor: PsiElement,
        name: String,
        kinds: Set<AikenTopLevelSymbolKind>,
        modulePaths: Set<String> = emptySet()
    ): List<PsiElement> {
        val project = anchor.project
        if (name.isBlank() || kinds.isEmpty() || DumbService.getInstance(project).isDumb) return emptyList()

        val scope = AikenSearchScopes.forElement(anchor)
        val psiManager = PsiManager.getInstance(project)
        val index = FileBasedIndex.getInstance()
        val seen = LinkedHashSet<Triple<VirtualFile?, Int, Int>>()
        val targets = ArrayList<PsiElement>()

        val keys =
            if (modulePaths.isEmpty()) {
                kinds.map { kind -> AikenTopLevelSymbolIndex.nameKey(kind, name) }
            } else {
                modulePaths.flatMap { modulePath ->
                    kinds.map { kind -> AikenTopLevelSymbolIndex.moduleKey(kind, modulePath, name) }
                }
            }

        try {
            for (key in keys) {
                index.processValues(
                    AikenTopLevelSymbolIndex.NAME,
                    key,
                    null,
                    ValueProcessor<Int> { file, offset ->
                        val psiFile = psiManager.findFile(file) ?: return@ValueProcessor true
                        val target = resolveNamedElementAt(psiFile, offset) ?: return@ValueProcessor true
                        val identity = Triple(file, target.textRange.startOffset, target.textRange.endOffset)
                        if (seen.add(identity)) {
                            targets += target
                        }
                        true
                    },
                    scope
                )
            }
        } catch (_: IndexNotReadyException) {
            return emptyList()
        }

        return targets
    }

    fun resolveNamedElementAt(psiFile: com.intellij.psi.PsiFile, offset: Int): PsiElement? {
        val leaf = psiFile.findElementAt(offset) ?: return null
        val parent = leaf.parent
        return if (parent is PsiNamedElement) parent else leaf
    }
}
