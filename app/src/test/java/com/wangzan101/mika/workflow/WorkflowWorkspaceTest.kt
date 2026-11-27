package com.wangzan101.mika.workflow

import com.wangzan101.mika.cap.sandbox.JVM_GATE
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkflowWorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dirs() = SandboxDirs(
        File(tmp.root, "files/sandbox"),
        File(tmp.root, "cache"),
        File(tmp.root, "lib"),
        JVM_GATE,
        SandboxTree(),
    )

    @Test fun sharedWorkspaceWritesListsSkipsSymlinksAndDeletes() = runBlocking {
        val dirs = dirs()
        val ws = WorkflowWorkspace(dirs)
        val runId = "wf-run-1"
        val dir = dirs.workspace(WorkspaceKey.Workflow(runId))
        assertEquals("w-wf-run-1", dir.name)

        ws.writeStepResult(runId, 0, "# Step 1\nFound 3 rows.")
        File(dir, "steps/step-1/data.csv").apply { parentFile?.mkdirs(); writeText("a,b\n1,2\n") }
        val outside = File(tmp.root, "outside.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(dir, "leak.txt").toPath(), outside.toPath())

        val artifacts = ws.listArtifacts(runId)
        assertEquals(
            listOf("/workspace/steps/step-1/data.csv", "/workspace/steps/step-1/result.md"),
            artifacts.map { it.sandboxPath },
        )
        assertTrue(File(dir, "steps/step-1/result.md").readText().contains("Found 3 rows."))

        ws.delete(runId)
        assertFalse(dir.exists())
        assertTrue(outside.isFile)
    }
}
