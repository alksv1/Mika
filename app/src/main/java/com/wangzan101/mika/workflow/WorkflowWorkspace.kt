package com.wangzan101.mika.workflow

import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Manages the per-run shared sandbox workspace (`w-<runId>`) for multi-step workflows. */
class WorkflowWorkspace(private val dirs: SandboxDirs) {
    data class Artifact(val sandboxPath: String, val relativePath: String, val bytes: Long)

    fun stepResultRelPath(stepIndex: Int): String = "steps/step-${stepIndex + 1}/result.md"

    /** Writes a completed step's markdown output using the symlink-safe file gate. */
    fun writeStepResult(runId: String, stepIndex: Int, result: String) {
        if (!WorkspaceKey.isValidWorkflowId(runId)) return
        runCatching {
            val ws = dirs.workspace(WorkspaceKey.Workflow(runId))
            ws.mkdirs()
            dirs.gate.replace(ws, stepResultRelPath(stepIndex), result.toByteArray(Charsets.UTF_8))
        }
    }

    /** Lists regular non-symlink files inside this run's shared workspace, sorted by relative path. */
    fun listArtifacts(runId: String, maxFiles: Int = 50): List<Artifact> {
        if (!WorkspaceKey.isValidWorkflowId(runId) || maxFiles <= 0) return emptyList()
        val ws = dirs.workspace(WorkspaceKey.Workflow(runId))
        if (!ws.isDirectory || Files.isSymbolicLink(ws.toPath())) return emptyList()
        val collected = mutableListOf<Artifact>()
        collectFiles(ws, ws, depth = 0, out = collected)
        return collected.sortedBy { it.relativePath }.take(maxFiles)
    }

    suspend fun delete(runId: String) {
        if (!WorkspaceKey.isValidWorkflowId(runId)) return
        dirs.deleteWorkspace(WorkspaceKey.Workflow(runId))
    }

    private fun collectFiles(root: File, dir: File, depth: Int, out: MutableList<Artifact>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            val path = child.toPath()
            if (Files.isSymbolicLink(path)) continue
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                collectFiles(root, child, depth + 1, out)
            } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (child.name.startsWith(".mika-new-")) continue
                val rel = root.toPath().relativize(path).toString().replace('\\', '/')
                out += Artifact("/workspace/$rel", rel, Files.size(path))
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
    }
}
