package com.medusalabs.aiken.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import com.medusalabs.aiken.index.AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.index.aikenTopLevelSymbolNameKey

class AikenGotoSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    ) {
        val project = scope.project ?: return
        if (DumbService.getInstance(project).isDumb) return

        val index = FileBasedIndex.getInstance()
        val seen = LinkedHashSet<String>()

        try {
            index.processAllKeys(
                AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME,
                Processor { key ->
                    if (!key.startsWith("name|")) return@Processor true
                    if (index.getContainingFiles(AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME, key, scope).isEmpty()) {
                        return@Processor true
                    }
                    val symbolName = key.substringAfterLast('|')
                    if (symbolName.isBlank() || !seen.add(symbolName)) return@Processor true
                    processor.process(symbolName)
                },
                scope,
                filter
            )
        } catch (_: IndexNotReadyException) {
            return
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    ) {
        if (name.isBlank()) return

        val project = parameters.project
        if (DumbService.getInstance(project).isDumb) return

        val psiManager = PsiManager.getInstance(project)
        val index = FileBasedIndex.getInstance()
        val seen = LinkedHashSet<Triple<VirtualFile, Int, Int>>()
        val keys = AikenTopLevelSymbolKind.entries.map { kind -> aikenTopLevelSymbolNameKey(kind, name) }

        try {
            for (key in keys) {
                val completed =
                    index.processValues(
                        AIKEN_TOP_LEVEL_SYMBOL_INDEX_NAME,
                        key,
                        null,
                        ValueProcessor<Int> { file, offset ->
                            val psiFile = psiManager.findFile(file) ?: return@ValueProcessor true
                            val target = AikenTopLevelSymbolLookup.resolveNamedElementAt(psiFile, offset) ?: return@ValueProcessor true
                            val navigationItem = target as? NavigationItem ?: return@ValueProcessor true
                            val range = target.textRange ?: TextRange.EMPTY_RANGE
                            val identity = Triple(file, range.startOffset, range.endOffset)
                            if (!seen.add(identity)) return@ValueProcessor true
                            processor.process(navigationItem)
                        },
                        parameters.searchScope,
                        parameters.idFilter
                    )
                if (!completed) return
            }
        } catch (_: IndexNotReadyException) {
            return
        }
    }
}
