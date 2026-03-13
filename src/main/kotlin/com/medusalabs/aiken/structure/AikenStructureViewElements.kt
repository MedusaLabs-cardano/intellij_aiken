package com.medusalabs.aiken.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.medusalabs.aiken.index.AikenTopLevelSymbolExtractor
import com.medusalabs.aiken.index.AikenTopLevelSymbolKind
import com.medusalabs.aiken.navigation.AikenTopLevelSymbolLookup
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

private data class StructureSymbol(
    val element: PsiElement,
    val name: String,
    val kind: AikenTopLevelSymbolKind,
    val signature: String? = null,
    val children: List<StructureSymbol> = emptyList()
)

internal class AikenStructureViewFileTreeElement(
    private val psiFile: PsiFile
) : PsiTreeElementBase<PsiFile>(psiFile) {
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        buildStructureSymbols(psiFile).map(::AikenStructureViewSymbolTreeElement)

    override fun getPresentableText(): String? = psiFile.name

    override fun getLocationString(): String? = null
}

private class AikenStructureViewSymbolTreeElement(
    private val symbol: StructureSymbol
) : PsiTreeElementBase<PsiElement>(symbol.element) {
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        symbol.children.map(::AikenStructureViewSymbolTreeElement)

    override fun getPresentableText(): String = symbol.signature ?: symbol.name

    override fun getLocationString(): String? =
        when (symbol.kind) {
            AikenTopLevelSymbolKind.FUNCTION -> "function"
            AikenTopLevelSymbolKind.TYPE -> "type"
            AikenTopLevelSymbolKind.CONST -> "const"
            AikenTopLevelSymbolKind.CONSTRUCTOR -> "constructor"
        }

    override fun getPresentation(): ItemPresentation = this
}

private fun buildStructureSymbols(psiFile: PsiFile): List<StructureSymbol> {
    val entries = AikenTopLevelSymbolExtractor.extract(psiFile.text)
    if (entries.isEmpty()) return emptyList()

    val signatures = AikenFunctionSignatureExtractor.extract(psiFile.text)
    val resolvedEntries =
        entries.map { entry ->
            val element = AikenTopLevelSymbolLookup.resolveNamedElementAt(psiFile, entry.offset) ?: return@map null
            StructureSymbol(
                element = element,
                name = entry.name,
                kind = entry.kind,
                signature = if (entry.kind == AikenTopLevelSymbolKind.FUNCTION || entry.kind == AikenTopLevelSymbolKind.CONST) {
                    signatures[entry.name]
                } else {
                    null
                }
            )
        }

    val result = ArrayList<StructureSymbol>()
    var currentType: StructureSymbol? = null
    val currentTypeChildren = ArrayList<StructureSymbol>()

    fun flushCurrentType() {
        val typeSymbol = currentType ?: return
        result += typeSymbol.copy(children = currentTypeChildren.toList())
        currentType = null
        currentTypeChildren.clear()
    }

    for ((index, entry) in entries.withIndex()) {
        val symbol = resolvedEntries[index] ?: continue
        when (entry.kind) {
            AikenTopLevelSymbolKind.TYPE -> {
                flushCurrentType()
                currentType = symbol
            }
            AikenTopLevelSymbolKind.CONSTRUCTOR -> {
                if (currentType != null) {
                    currentTypeChildren += symbol
                } else {
                    result += symbol
                }
            }
            else -> {
                flushCurrentType()
                result += symbol
            }
        }
    }

    flushCurrentType()
    return result
}
