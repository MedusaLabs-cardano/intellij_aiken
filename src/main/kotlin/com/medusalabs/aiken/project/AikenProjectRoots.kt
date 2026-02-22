package com.medusalabs.aiken.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore

object AikenProjectRoots {
    private const val AIKEN_TOML = "aiken.toml"

    fun findRootForFile(file: VirtualFile?): VirtualFile? {
        var current = when {
            file == null -> null
            file.isDirectory -> file
            else -> file.parent
        }

        while (current != null) {
            if (current.findChild(AIKEN_TOML)?.isValid == true) {
                return current
            }
            current = current.parent
        }

        return null
    }

    fun scopeForFile(project: Project, file: VirtualFile?): GlobalSearchScope {
        val root = findRootForFile(file)
        return if (root != null) {
            GlobalSearchScopesCore.directoryScope(project, root, true)
        } else {
            GlobalSearchScope.allScope(project)
        }
    }

    fun isInsideRoot(file: VirtualFile, root: VirtualFile?): Boolean {
        if (root == null) return true
        return VfsUtilCore.isAncestor(root, file, false)
    }
}
