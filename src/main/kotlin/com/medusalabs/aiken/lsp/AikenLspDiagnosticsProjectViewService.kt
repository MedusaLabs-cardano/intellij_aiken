package com.medusalabs.aiken.lsp

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.Problem
import com.intellij.problems.WolfTheProblemSolver
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AikenLspDiagnosticsProjectViewService(private val project: Project) {
    enum class FileSeverity(val rank: Int) {
        ERROR(3),
        WARNING(2),
        INFO(1)
    }

    private val fileSeverities = ConcurrentHashMap<VirtualFile, FileSeverity>()
    private val diagnosticsByFile = ConcurrentHashMap<VirtualFile, List<Diagnostic>>()
    private val refreshQueued = AtomicBoolean(false)

    fun onPublishDiagnostics(file: VirtualFile, diagnostics: List<Diagnostic>) {
        if (!isSupportedFile(file)) return
        if (!file.isValid) return
        diagnosticsByFile[file] = diagnostics.toList()
        scheduleUpdate(file)
    }

    fun getDiagnostics(file: VirtualFile): List<Diagnostic> = diagnosticsByFile[file] ?: emptyList()

    private fun isSupportedFile(file: VirtualFile): Boolean =
        file.fileType == AikenFileType || file.fileType == UplcFileType

    private fun scheduleUpdate(file: VirtualFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            updateProblems(file)
        }
    }

    private fun updateProblems(file: VirtualFile) {
        if (!file.isValid) return
        val diagnostics = diagnosticsByFile[file] ?: return
        updateSeverity(file, diagnostics)
        val wolf = WolfTheProblemSolver.getInstance(project)
        if (diagnostics.isEmpty()) {
            wolf.clearProblems(file)
            return
        }

        val problems = buildProblems(file, diagnosticsForWolfProblems(diagnostics), wolf)

        if (problems.isEmpty()) {
            wolf.clearProblems(file)
        } else {
            wolf.reportProblems(file, problems)
        }
    }

    fun getSeverity(file: VirtualFile): FileSeverity? = fileSeverities[file]

    private fun updateSeverity(file: VirtualFile, diagnostics: List<Diagnostic>) {
        val newSeverity = computeSeverity(diagnostics)
        val oldSeverity = fileSeverities[file]
        if (newSeverity == null) {
            if (oldSeverity != null) {
                fileSeverities.remove(file)
                scheduleProjectViewRefresh()
            }
            return
        }
        if (oldSeverity != newSeverity) {
            fileSeverities[file] = newSeverity
            scheduleProjectViewRefresh()
        }
    }

    private fun computeSeverity(diagnostics: List<Diagnostic>): FileSeverity? {
        var current: FileSeverity? = null
        for (item in diagnostics) {
            val mapped = mapSeverity(item.severity)
            if (mapped != null && (current == null || mapped.rank > current.rank)) {
                current = mapped
            }
        }
        return current
    }

    private fun mapSeverity(severity: DiagnosticSeverity?): FileSeverity? =
        when (severity ?: DiagnosticSeverity.Warning) {
            DiagnosticSeverity.Error -> FileSeverity.ERROR
            DiagnosticSeverity.Warning -> FileSeverity.WARNING
            DiagnosticSeverity.Information -> FileSeverity.INFO
            DiagnosticSeverity.Hint -> FileSeverity.INFO
        }

    private fun diagnosticsForWolfProblems(diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.filter { it.severity == DiagnosticSeverity.Error }

    private fun scheduleProjectViewRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            refreshQueued.set(false)
            ProjectView.getInstance(project).refresh()
        }
    }

    private fun buildProblems(
        file: VirtualFile,
        diagnostics: List<Diagnostic>,
        wolf: WolfTheProblemSolver
    ): List<Problem> {
        val problems = ArrayList<Problem>(diagnostics.size)
        for (diagnostic in diagnostics) {
            val range = diagnostic.range ?: continue
            val message = diagnostic.message ?: "LSP diagnostic"
            val problem =
                wolf.convertToProblem(
                    file,
                    range.start.line,
                    range.start.character,
                    arrayOf(message)
                )
                    ?: continue
            problems.add(problem)
        }
        return problems
    }
}
