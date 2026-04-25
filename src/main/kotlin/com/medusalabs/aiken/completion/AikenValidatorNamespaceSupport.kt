package com.medusalabs.aiken.completion

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.medusalabs.aiken.imports.AikenUseModel
import com.medusalabs.aiken.project.AikenModuleFiles
import com.medusalabs.aiken.signature.AikenFunctionSignatureExtractor

internal data class AikenImportedModuleTarget(
    val modulePath: String,
    val canonicalQualifier: String
)

internal data class AikenImportedValidatorNamespaceTarget(
    val modulePath: String,
    val validatorName: String,
    val canonicalQualifierChain: String
)

internal object AikenValidatorNamespaceSupport {
    fun resolveImportedModuleTargets(
        useModel: AikenUseModel,
        qualifierChain: String
    ): List<AikenImportedModuleTarget> {
        val normalized = qualifierChain.trim()
        if (normalized.isEmpty()) return emptyList()

        val result = LinkedHashSet<AikenImportedModuleTarget>()
        for (statement in useModel.statements) {
            val modulePath = statement.modulePath.trim()
            if (modulePath.isBlank()) continue
            if (normalized !in moduleQualifierForms(modulePath)) continue

            val canonicalQualifier =
                statement.moduleAlias?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: modulePath.substringAfterLast('/').trim().takeIf { it.isNotEmpty() }
                    ?: continue
            result += AikenImportedModuleTarget(modulePath = modulePath, canonicalQualifier = canonicalQualifier)
        }
        return result.toList()
    }

    fun resolveImportedValidatorNamespaceTargets(
        anchor: PsiElement,
        useModel: AikenUseModel,
        qualifierChain: String
    ): List<AikenImportedValidatorNamespaceTarget> =
        resolveImportedValidatorNamespaceTargets(anchor.containingFile?.virtualFile, useModel, qualifierChain)

    fun resolveImportedValidatorNamespaceTargets(
        anchorFile: VirtualFile?,
        useModel: AikenUseModel,
        qualifierChain: String
    ): List<AikenImportedValidatorNamespaceTarget> {
        val parts = qualifierChain.split('.').map(String::trim).filter { it.isNotBlank() }
        if (parts.size < 2) return emptyList()

        val result = LinkedHashSet<AikenImportedValidatorNamespaceTarget>()
        for (splitIndex in 1 until parts.size) {
            val moduleQualifier = parts.subList(0, splitIndex).joinToString(".")
            val validatorSegments = parts.subList(splitIndex, parts.size)
            if (validatorSegments.size != 1) continue
            val validatorName = validatorSegments.single()
            val moduleTargets = resolveImportedModuleTargets(useModel, moduleQualifier)
            for (moduleTarget in moduleTargets) {
                if (!validatorNamesInModule(anchorFile, moduleTarget.modulePath).contains(validatorName)) continue
                result +=
                    AikenImportedValidatorNamespaceTarget(
                        modulePath = moduleTarget.modulePath,
                        validatorName = validatorName,
                        canonicalQualifierChain = "${moduleTarget.canonicalQualifier}.$validatorName"
                    )
            }
        }

        return result.toList()
    }

    fun validatorNamesInModule(
        anchor: PsiElement,
        modulePath: String
    ): List<String> =
        validatorNamesInModule(anchor.containingFile?.virtualFile, modulePath)

    fun validatorNamesInModule(
        anchorFile: VirtualFile?,
        modulePath: String
    ): List<String> =
        AikenModuleFiles.findFilesForModulePath(anchorFile, modulePath)
            .asSequence()
            .map { file -> file.contentsToByteArray().toString(Charsets.UTF_8) }
            .flatMap { moduleText -> AikenFunctionSignatureExtractor.extractValidatorNames(moduleText).asSequence() }
            .distinct()
            .toList()

    fun importedValidatorHandlerSignatures(
        anchorFile: VirtualFile?,
        useModel: AikenUseModel,
        qualifierChain: String,
        handlerName: String
    ): Set<String> =
        resolveImportedValidatorNamespaceTargets(anchorFile, useModel, qualifierChain)
            .asSequence()
            .flatMap { target ->
                AikenModuleFiles.findFilesForModulePath(anchorFile, target.modulePath)
                    .asSequence()
                    .map { moduleFile -> moduleFile.contentsToByteArray().toString(Charsets.UTF_8) }
                    .flatMap { moduleText ->
                        AikenFunctionSignatureExtractor.extractValidatorHandlerEntries(moduleText)
                            .asSequence()
                            .filter { entry ->
                                entry.validatorName == target.validatorName && entry.handlerName == handlerName
                            }
                            .map { entry -> entry.signature }
                    }
            }
            .toSet()

    private fun moduleQualifierForms(modulePath: String): Set<String> {
        val segments = modulePath.split('/').map(String::trim).filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptySet()

        val result = LinkedHashSet<String>()
        for (start in segments.indices) {
            result += segments.subList(start, segments.size).joinToString(".")
        }
        return result
    }
}
