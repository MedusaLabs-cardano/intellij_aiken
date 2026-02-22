package com.medusalabs.aiken.navigation

import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.codeInsight.navigation.GotoTargetRendererProvider
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.psi.PsiElement
import com.intellij.util.TextWithIcon
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenGotoTargetRendererProvider : GotoTargetRendererProvider {
    override fun getRenderer(
        element: PsiElement,
        gotoData: GotoTargetHandler.GotoData
    ): PsiElementListCellRenderer<*>? {
        val fileType = element.containingFile?.fileType
        if (fileType != AikenFileType && fileType != UplcFileType) return null
        return AikenRenderer
    }

    private object AikenRenderer : PsiElementListCellRenderer<PsiElement>() {
        override fun getElementText(element: PsiElement): String {
            return AikenGotoPresentationSupport.buildPreview(element)
                ?: element.text.replace("\\s+".toRegex(), " ").trim()
        }

        override fun getContainerText(element: PsiElement, name: String?): String? {
            return AikenGotoPresentationSupport.buildShortLocation(element)
        }

        override fun getItemLocation(element: Any): TextWithIcon? {
            val psi = element as? PsiElement ?: return null
            val text = AikenGotoPresentationSupport.buildShortLocation(psi) ?: return null
            return TextWithIcon(text, null)
        }
    }
}
