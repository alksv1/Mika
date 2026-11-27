package com.wangzan101.mika.workflow

import com.wangzan101.mika.core.run.PauseFlag
import com.wangzan101.mika.settings.AesGcmKeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** 暂停 Mika 时的工作流:不开始、不继续,在进行的到下一步就停下,恢复后不自动接着跑。 */
class WorkflowPauseTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = WorkflowStore(File(tmp.root, "workflows.bin"), AesGcmKeyCipher { key }) { 1000L }
    private val steps = listOf(WorkflowStep(title = "Find", instruction = "Find materials"), WorkflowStep(title = "Plan", instruction = "Make a plan"))
    private var paused = false
    private val launched = mutableListOf<WorkflowDispatch>()

    private fun refused(action: () -> Unit) {
        try { action(); fail("expected Paused") } catch (e: WorkflowException) { assertEquals(WorkflowProblem.Paused, e.problem) }
    }

    @Test fun startIsRefusedWhilePaused() {
        val store = store()
        val runtime = WorkflowRuntime(store, PauseFlag { paused }) { launched += it }
        paused = true
        refused { runtime.start(store.saveTemplate("t", 0, "Study", steps), "x") }
        assertTrue(launched.isEmpty())
        assertTrue(store.data.value.runs.isEmpty())
    }

    @Test fun runningStepFinishingWhilePausedDoesNotDispatchTheNextStep() {
        val store = store()
        val runtime = WorkflowRuntime(store, PauseFlag { paused }) { launched += it }
        val id = runtime.start(store.saveTemplate("t", 0, "Study", steps), "trip")
        val first = launched.single()
        store.claim(first.attemptId)

        paused = true
        runtime.haltForPause()
        runtime.complete(first.attemptId, true, "Gathered")

        assertEquals(1, launched.size)
        assertEquals(WorkflowPhase.Paused, store.data.value.runs.single { it.id == id }.phase)
        // 暂停期间不能点继续;恢复后也不会自己跑,要用户点继续。
        refused { runtime.resume(id) }
        paused = false
        runtime.retrySave()
        assertEquals(1, launched.size)
        runtime.resume(id)
        assertEquals(2, launched.size)
    }

    @Test fun dispatchedButUnclaimedStepIsMarkedPausedForReview() {
        val store = store()
        val runtime = WorkflowRuntime(store, PauseFlag { paused }) { launched += it }
        val id = runtime.start(store.saveTemplate("t", 0, "Study", steps), "trip")

        paused = true
        runtime.haltForPause()

        val run = store.data.value.runs.single { it.id == id }
        assertEquals(WorkflowPhase.Review, run.phase)
        assertEquals(PauseFlag.REASON, run.current?.problem)
        // 任务服务那边晚到的「因暂停没开始」不会再改它。
        runtime.complete(launched.single().attemptId, false, "", PauseFlag.REASON)
        assertEquals(WorkflowPhase.Review, store.data.value.runs.single { it.id == id }.phase)
    }
}
