package com.medusalabs.aiken.project

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object AikenModulePath {
    fun fromFile(file: VirtualFile?): String? {
        val targetFile = file?.takeIf { !it.isDirectory } ?: return null
        val root = AikenProjectRoots.findRootForFile(targetFile) ?: return null
        val relativePath = VfsUtilCore.getRelativePath(targetFile, root, '/') ?: return null
        return fromRelativePath(relativePath)
    }

    fun fromRelativePath(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/')
        if (!normalized.endsWith(".ak")) return null

        val withoutExtension = normalized.removeSuffix(".ak")
        tailAfter("lib/", withoutExtension)?.let { return it }
        tailAfter("validators/", withoutExtension)?.let { return it }

        if (withoutExtension.startsWith("build/packages/")) {
            val packageAndRest = withoutExtension.removePrefix("build/packages/").substringAfter('/', "")
            tailAfter("lib/", packageAndRest)?.let { return it }
            tailAfter("validators/", packageAndRest)?.let { return it }
        }

        return null
    }

    private fun tailAfter(prefix: String, source: String): String? =
        if (source.startsWith(prefix)) source.removePrefix(prefix).takeIf { it.isNotBlank() } else null
}
