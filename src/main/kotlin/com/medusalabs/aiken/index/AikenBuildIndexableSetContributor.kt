package com.medusalabs.aiken.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.IndexableSetContributor

/**
 * IntelliJ typically excludes `<project>/build` (e.g. Gradle output), but Aiken projects may keep their stdlib there.
 *
 * We explicitly ask the IDE to index that directory so project-wide completion can see it.
 */
class AikenBuildIndexableSetContributor : IndexableSetContributor() {
    override fun getAdditionalRootsToIndex(): Set<VirtualFile> = emptySet()

    override fun getAdditionalProjectRootsToIndex(project: Project): Set<VirtualFile> {
        val basePath = project.basePath ?: return emptySet()
        val buildDir = LocalFileSystem.getInstance().findFileByPath("$basePath/build") ?: return emptySet()
        return if (buildDir.isDirectory) setOf(buildDir) else emptySet()
    }
}
