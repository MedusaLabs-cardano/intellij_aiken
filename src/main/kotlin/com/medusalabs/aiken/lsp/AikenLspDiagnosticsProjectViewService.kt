package com.medusalabs.aiken.lsp

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.problems.ProblemImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.impl.LspServerImpl
import com.intellij.platform.lsp.impl.highlighting.DiagnosticAndQuickFixes
import com.intellij.problems.Problem
import com.intellij.problems.WolfTheProblemSolver
import com.medusalabs.aiken.lang.AikenFileType
import com.medusalabs.aiken.lang.UplcFileType
import org.eclipse.lsp4j.DiagnosticSeverity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AikenLspDiagnosticsProjectViewService(private val project: Project) {
    enum class FileSeverity(val rank: Int) {
        ERROR(3),
        WARNING(2),
        INFO(1)
    }

    private val listener = object : LspServerManagerListener {
        override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
            if (!isSupportedFile(file)) return
            if (lspServer.providerClass != AikenLspServerSupportProvider::class.java) return
            val serverImpl = lspServer as? LspServerImpl ?: return
            scheduleUpdate(serverImpl, file)
        }

    }

    private val fileSeverities = ConcurrentHashMap<VirtualFile, FileSeverity>()
    private val refreshQueued = AtomicBoolean(false)

    init {
        val manager = LspServerManager.getInstance(project)
        manager.addLspServerManagerListener(listener, project, false)
    }

    fun refreshOpenFiles() {
        val manager = LspServerManager.getInstance(project)
        val server =
            manager.getServersForProvider(AikenLspServerSupportProvider::class.java)
                .firstOrNull() as? LspServerImpl ?: return
        val openFiles = FileEditorManager.getInstance(project).openFiles
        for (file in openFiles) {
            if (!isSupportedFile(file)) continue
            scheduleUpdate(server, file)
        }
    }

    private fun isSupportedFile(file: VirtualFile): Boolean =
        file.fileType == AikenFileType || file.fileType == UplcFileType

    private fun scheduleUpdate(server: LspServerImpl, file: VirtualFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            updateProblems(server, file)
        }
    }

    private fun updateProblems(server: LspServerImpl, file: VirtualFile) {
        val diagnostics = server.getDiagnosticsAndQuickFixes(file)
        updateSeverity(file, diagnostics)
        val wolf = WolfTheProblemSolver.getInstance(project)
        if (diagnostics.isEmpty()) {
            wolf.clearProblems(file)
            return
        }

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val problems =
            ReadAction.compute<List<Problem>, RuntimeException> {
                buildProblems(file, document, diagnostics)
            }

        if (problems.isEmpty()) {
            wolf.clearProblems(file)
        } else {
            wolf.reportProblems(file, problems)
        }
    }

    fun getSeverity(file: VirtualFile): FileSeverity? = fileSeverities[file]

    private fun updateSeverity(file: VirtualFile, diagnostics: List<DiagnosticAndQuickFixes>) {
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

    private fun computeSeverity(diagnostics: List<DiagnosticAndQuickFixes>): FileSeverity? {
        var current: FileSeverity? = null
        for (item in diagnostics) {
            val mapped = mapSeverity(item.diagnostic.severity)
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

    private fun scheduleProjectViewRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            refreshQueued.set(false)
            ProjectView.getInstance(project).refresh()
        }
    }

    private fun buildProblems(
        file: VirtualFile,
        document: com.intellij.openapi.editor.Document,
        diagnostics: List<DiagnosticAndQuickFixes>
    ): List<Problem> {
        val problems = ArrayList<Problem>(diagnostics.size)
        val textLength = document.textLength

        for (item in diagnostics) {
            val diagnostic = item.diagnostic
            val range = diagnostic.range ?: continue

            val startOffset = toOffset(document, range.start.line, range.start.character) ?: continue
            var endOffset = toOffset(document, range.end.line, range.end.character) ?: startOffset
            if (endOffset <= startOffset) {
                endOffset = (startOffset + 1).coerceAtMost(textLength)
            }

            val severity = diagnostic.severity ?: DiagnosticSeverity.Warning
            val highlightType = highlightTypeForSeverity(severity)
            val builder =
                HighlightInfo.newHighlightInfo(highlightType)
                    .range(startOffset, endOffset)
                    .description(diagnostic.message ?: "LSP diagnostic")

            val mappedSeverity = highlightSeverityFor(severity)
            if (mappedSeverity != null) {
                builder.severity(mappedSeverity)
            }

            val info = builder.create()
            if (info != null) {
                problems.add(ProblemImpl(file, info, false))
            }
        }

        return problems
    }

    private fun highlightTypeForSeverity(severity: DiagnosticSeverity): HighlightInfoType =
        when (severity) {
            DiagnosticSeverity.Error -> HighlightInfoType.ERROR
            DiagnosticSeverity.Warning -> HighlightInfoType.WARNING
            DiagnosticSeverity.Information -> HighlightInfoType.INFORMATION
            DiagnosticSeverity.Hint -> HighlightInfoType.WEAK_WARNING
        }

    private fun highlightSeverityFor(severity: DiagnosticSeverity): HighlightSeverity? =
        when (severity) {
            DiagnosticSeverity.Error -> HighlightSeverity.ERROR
            DiagnosticSeverity.Warning -> HighlightSeverity.WARNING
            DiagnosticSeverity.Information -> HighlightSeverity.WEAK_WARNING
            DiagnosticSeverity.Hint -> HighlightSeverity.WEAK_WARNING
        }

    private fun toOffset(document: com.intellij.openapi.editor.Document, line: Int, column: Int): Int? {
        if (line < 0 || line >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val col = column.coerceIn(0, (lineEnd - lineStart).coerceAtLeast(0))
        return lineStart + col
    }
}
