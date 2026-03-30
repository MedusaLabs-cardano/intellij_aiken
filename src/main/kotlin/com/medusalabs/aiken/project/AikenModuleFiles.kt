package com.medusalabs.aiken.project

import com.intellij.openapi.vfs.VirtualFile

object AikenModuleFiles {
    fun findFilesForModulePath(anchorFile: VirtualFile?, modulePath: String): List<VirtualFile> {
        val normalizedModulePath = modulePath.trim().removeSuffix(".ak")
        if (normalizedModulePath.isEmpty()) return emptyList()

        val root = AikenProjectRoots.findRootForFile(anchorFile) ?: return emptyList()
        val result = LinkedHashSet<VirtualFile>()

        fun addIfPresent(relativePath: String) {
            root.findFileByRelativePath(relativePath)
                ?.takeIf { !it.isDirectory }
                ?.let(result::add)
        }

        addIfPresent("lib/$normalizedModulePath.ak")
        addIfPresent("validators/$normalizedModulePath.ak")

        root.findFileByRelativePath("build/packages")
            ?.children
            ?.filter { it.isDirectory }
            ?.forEach { packageDir ->
                packageDir.findFileByRelativePath("lib/$normalizedModulePath.ak")
                    ?.takeIf { !it.isDirectory }
                    ?.let(result::add)
                packageDir.findFileByRelativePath("validators/$normalizedModulePath.ak")
                    ?.takeIf { !it.isDirectory }
                    ?.let(result::add)
            }

        return result.toList()
    }
}
