package com.medusalabs.aiken.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.psi.AikenPsiFile

class AikenStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is AikenPsiFile) return null

        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return StructureViewModelBase(
                    psiFile,
                    editor,
                    AikenStructureViewFileTreeElement(psiFile)
                ).withSuitableClasses(
                    PsiFile::class.java
                ).withSorters(
                    Sorter.ALPHA_SORTER
                )
            }

            override fun isRootNodeShown(): Boolean = false
        }
    }
}
