package com.medusalabs.aiken.navigation

import com.intellij.codeInsight.navigation.GotoTargetPresentationProvider
import com.intellij.openapi.util.Iconable
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenGotoTargetPresentationProvider : GotoTargetPresentationProvider {
    override fun getTargetPresentation(element: PsiElement, hasDifferentNames: Boolean): TargetPresentation? {
        val fileType = element.containingFile?.fileType
        if (fileType != AikenFileType && fileType != UplcFileType) return null

        val presentableText =
            AikenGotoPresentationSupport.buildPreview(element)
                ?: element.text.replace("\\s+".toRegex(), " ").trim().ifEmpty { return null }
        val locationText = AikenGotoPresentationSupport.buildShortLocation(element)

        val builder = TargetPresentation.builder(presentableText)
            .icon(element.getIcon(Iconable.ICON_FLAG_VISIBILITY))

        if (!locationText.isNullOrBlank()) {
            builder.locationText(locationText)
        }

        return builder.presentation()
    }
}
