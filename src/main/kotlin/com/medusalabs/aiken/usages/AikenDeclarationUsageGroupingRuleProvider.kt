package com.medusalabs.aiken.usages

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.Usage
import com.intellij.usages.UsageGroup
import com.intellij.usages.UsageTarget
import com.intellij.usages.rules.PsiElementUsage
import com.intellij.usages.rules.UsageGroupingRule
import com.intellij.usages.rules.UsageGroupingRuleProvider
import com.intellij.util.PlatformIcons
import com.intellij.ui.SimpleTextAttributes
import com.medusalabs.aiken.highlight.lexer.AikenTokenTypes
import com.medusalabs.aiken.highlight.lexer.UplcTokenTypes
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType

class AikenDeclarationUsageGroupingRuleProvider : UsageGroupingRuleProvider {
    override fun getActiveRules(project: Project): Array<UsageGroupingRule> = arrayOf(AikenDeclarationUsageGroupingRule(project))
}

private class AikenDeclarationUsageGroupingRule(
    private val project: Project
) : UsageGroupingRule {
    override fun getRank(): Int = AIKEN_DECLARATION_GROUPING_RANK

    override fun getParentGroupsFor(usage: Usage, targets: Array<UsageTarget>): List<UsageGroup> {
        val usageElement = (usage as? PsiElementUsage)?.element ?: return emptyList()
        val targetElement =
            targets.asSequence()
                .filterIsInstance<PsiElementUsageTarget>()
                .mapNotNull(PsiElementUsageTarget::getElement)
                .firstOrNull(::isAikenFamilyElement)
                ?: return emptyList()

        if (!isDeclarationUsage(usageElement, targetElement)) return emptyList()
        return listOf(AikenDeclarationUsageGroup(targetElement))
    }

    private fun isDeclarationUsage(usageElement: PsiElement, targetElement: PsiElement): Boolean {
        val psiManager = PsiManager.getInstance(project)
        val usageCandidates = declarationCandidates(usageElement)
        val targetCandidates = declarationCandidates(targetElement)
        return usageCandidates.any { usageCandidate ->
            targetCandidates.any { targetCandidate ->
                psiManager.areElementsEquivalent(usageCandidate, targetCandidate)
            }
        }
    }

    private fun declarationCandidates(element: PsiElement): List<PsiElement> {
        val result = ArrayList<PsiElement>(3)
        var current: PsiElement? = element
        repeat(3) {
            val candidate = current ?: return@repeat
            result += candidate
            val parent = candidate.parent
            current = if (parent == null || parent is PsiFile || parent == candidate) null else parent
        }
        return result
    }

    private fun isAikenFamilyElement(element: PsiElement): Boolean = isAikenFamilyFileType(element.containingFile?.fileType)

    private fun isAikenFamilyFileType(fileType: FileType?): Boolean = fileType == AikenFileType || fileType == UplcFileType
}

private const val AIKEN_DECLARATION_GROUPING_RANK = 10_000

private class AikenDeclarationUsageGroup(
    private val targetElement: PsiElement
) : UsageGroup {
    private val presentableText = "${declarationKind(targetElement)} declaration"

    override fun getPresentableGroupText(): String = presentableText

    override fun getIcon() = PlatformIcons.FUNCTION_ICON

    override fun getTextAttributes(isSelected: Boolean): SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES

    override fun isValid(): Boolean = targetElement.isValid

    override fun update() {}

    override fun navigate(requestFocus: Boolean) {
        if (targetElement is Navigatable) targetElement.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = targetElement is Navigatable && targetElement.canNavigate()

    override fun canNavigateToSource(): Boolean =
        targetElement is Navigatable && targetElement.canNavigateToSource()

    override fun compareTo(other: UsageGroup): Int = when (other) {
        is AikenDeclarationUsageGroup -> presentableText.compareTo(other.presentableText)
        else -> -1
    }
}

private fun declarationKind(element: PsiElement): String =
    when (element.node?.elementType) {
        AikenTokenTypes.TYPE,
        UplcTokenTypes.TYPE -> "Type"
        AikenTokenTypes.FUNCTION,
        UplcTokenTypes.FUNCTION -> "Function"
        AikenTokenTypes.FIELD,
        UplcTokenTypes.FIELD -> "Field"
        else -> "Declaration"
    }
