package com.wangzan101.mika.workflow

import com.wangzan101.mika.core.run.PauseFlag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Android adapter only starts an intent; TaskService owns execution and calls complete.
 * 用户暂停了 Mika([paused]):开始、继续、确认都拒绝([WorkflowProblem.Paused]);
 * 已在进行的流程到了要派下一步时不派:停在「已暂停」(已认领待派的那一步记失败 [PauseFlag.REASON]、停在待确认),
 * 恢复后由用户点继续,不自动接着跑。
 */
class WorkflowRuntime(
    val store: WorkflowStore,
    private val paused: PauseFlag = PauseFlag.Never,
    private val launch: (WorkflowDispatch) -> Unit,
) {
    private data class Completion(val id: String, val successful: Boolean, val result: String, val problem: String)
    private val pending = linkedMapOf<String, Completion>()
    private val _saveError = MutableStateFlow(false)
    val saveError = _saveError.asStateFlow()

    @Synchronized fun start(template: WorkflowTemplate, input: String): String {
        refuseWhilePaused()
        val run = store.start(template.id, template.revision, input)
        advanceOrReport(run.id)
        return run.id
    }
    @Synchronized fun resume(id: String) { refuseWhilePaused(); store.resume(id); advanceOrReport(id) }
    @Synchronized fun resolve(id: String, attemptId: String, result: String?) {
        refuseWhilePaused()
        store.resolve(id, attemptId, result)
        advanceOrReport(id)
    }
    @Synchronized fun complete(id: String, successful: Boolean, result: String, problem: String = "") {
        pending[id] = Completion(id, successful, result, problem)
        retrySave()
    }
    @Synchronized fun retrySave() {
        try {
            store.load()
            pending.values.toList().forEach { value ->
                val runId = store.finish(value.id, value.successful, value.result, value.problem)
                pending.remove(value.id)
                if (runId != null) advance(runId)
            }
            store.data.value.runs.filter { it.phase == WorkflowPhase.Active && it.current == null }.forEach { advance(it.id) }
            _saveError.value = false
        } catch (_: Exception) { _saveError.value = true }
    }
    private fun advanceOrReport(id: String) {
        try { advance(id) } catch (_: WorkflowException) { _saveError.value = true }
    }
    private fun refuseWhilePaused() { if (paused.paused()) throw WorkflowException(WorkflowProblem.Paused) }
    /** 用户刚暂停了 Mika:还没派出下一步的流程都停下(见 [advance]);正在跑的那一步随任务被取消而结束。 */
    @Synchronized fun haltForPause() {
        try {
            store.load()
            store.data.value.runs.filter { it.phase == WorkflowPhase.Active }.forEach { advance(it.id) }
        } catch (_: Exception) { _saveError.value = true }
    }
    private fun advance(id: String) {
        if (paused.paused()) {
            // 已认领待派的那一步记失败,流程停在待确认;还没到下一步的直接停在「已暂停」。恢复后都由用户点继续
            val pending = store.pendingDispatch(id)
            if (pending != null) store.finish(pending.attemptId, false, "", PauseFlag.REASON) else store.pause(id)
            return
        }
        val next = store.pendingDispatch(id) ?: store.prepare(id) ?: return
        try { launch(next) }
        catch (_: Exception) {
            pending[next.attemptId] = Completion(next.attemptId, false, "", "start_failed")
            store.finish(next.attemptId, false, "", "start_failed")
            pending.remove(next.attemptId)
        }
    }
}
