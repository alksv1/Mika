package com.wangzan101.mika.workflow

import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.TaskTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class WorkflowStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val instruction: String,
    val pauseAfter: Boolean = false,
    val continueIf: String = "",
    val readOnly: Boolean = false,
)
@Serializable data class WorkflowTemplate(val id: String, val revision: Int, val title: String, val steps: List<WorkflowStep>)
@Serializable enum class WorkflowPhase { Active, Pausing, Paused, Review, Completed }
@Serializable enum class AttemptPhase { Dispatching, Running, Done, Review }
@Serializable data class StepAttempt(
    val id: String, val conversationId: String, val phase: AttemptPhase,
    val result: String = "", val problem: String = "", val manual: Boolean = false,
)
@Serializable data class WorkflowRun(
    val id: String, val template: WorkflowTemplate, val input: String, val createdAt: Long,
    val phase: WorkflowPhase = WorkflowPhase.Active,
    val attempts: List<List<StepAttempt>> = template.steps.map { emptyList() },
    val triggerKey: String? = null,
    val originThreadId: String? = null,
    val unattended: Boolean = false,
    val earlyStopped: Boolean = false,
    val earlyStopReason: String = "",
    val checkpointPaused: Boolean = false,
    val params: Map<String, String> = emptyMap(),
) {
    val completed: Int get() = attempts.takeWhile { it.lastOrNull()?.phase == AttemptPhase.Done }.size
    val current: StepAttempt? get() = attempts.getOrNull(completed)?.lastOrNull()
    val result: String get() = if (phase == WorkflowPhase.Completed) {
        attempts.getOrNull((completed - 1).coerceAtLeast(0))?.lastOrNull()?.result.orEmpty()
    } else ""
}
@Serializable data class WorkflowData(val templates: List<WorkflowTemplate> = emptyList(), val runs: List<WorkflowRun> = emptyList())
enum class WorkflowProblem {
    Invalid, Full, Changed, Busy, Read, Write, ResultTooLong,
    /** 用户暂停了 Mika:不开始、不继续,恢复后再操作。 */
    Paused,
}
class WorkflowException(val problem: WorkflowProblem) : Exception(problem.name)
data class WorkflowDispatch(
    val runId: String,
    val attemptId: String,
    val conversationId: String,
    val step: WorkflowStep,
    val input: String,
    val previous: String,
    val stepIndex: Int = 0,
    val priorResults: List<String> = emptyList(),
    val priorStepTitles: List<String> = emptyList(),
    val unattended: Boolean = false,
    val triggerKey: String? = null,
)

/** One encrypted, atomic ledger for templates and executions. Call on IO; never dispatch before committing. */
class WorkflowStore(
    private val file: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRunsRemoved: (List<String>) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(WorkflowData())
    private val _loaded = MutableStateFlow(false)
    private val convToRun = ConcurrentHashMap<String, String>()
    val data = _data.asStateFlow()
    val loaded = _loaded.asStateFlow()

    fun runIdForConversation(conversationId: String): String? = convToRun[conversationId]

    fun hasActiveRun(): Boolean = _data.value.runs.any { it.phase in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing) }

    @Synchronized fun stepContinueIf(attemptId: String): String? {
        load()
        val run = _data.value.runs.find { it.current?.id == attemptId } ?: return null
        val index = run.completed
        if (index >= run.template.steps.lastIndex) return null
        return run.template.steps[index].continueIf.trim().ifEmpty { null }
    }

    @Synchronized fun load() {
        if (_loaded.value) return
        val saved = try {
            if (!file.exists()) WorkflowData() else json.decodeFromString<WorkflowData>(cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8))
                .also(::validate)
        } catch (_: Exception) { throw WorkflowException(WorkflowProblem.Read) }
        // A dead process cannot prove what a screen action did. Never replay its in-flight attempt.
        val recovered = saved.copy(runs = saved.runs.map { run ->
            if (run.phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing)) run
            else if (run.current != null) replaceAttempt(run, run.current!!.copy(phase = AttemptPhase.Review, problem = "interrupted"))
                .copy(phase = WorkflowPhase.Review, checkpointPaused = false)
            else run.copy(phase = WorkflowPhase.Paused, checkpointPaused = false)
        })
        if (recovered != saved) persist(recovered)
        rebuildConversationMap(recovered)
        _data.value = recovered
        _loaded.value = true
    }

    @Synchronized fun saveTemplate(id: String, expected: Int, title: String, steps: List<WorkflowStep>): WorkflowTemplate {
        load()
        val clean = steps.map {
            it.copy(title = it.title.trim(), instruction = it.instruction.trim(), continueIf = it.continueIf.trim())
        }
        checkTemplate(title.trim(), clean)
        val old = _data.value.templates.find { it.id == id }
        requireProblem((old?.revision ?: 0) == expected, WorkflowProblem.Changed)
        requireProblem(old != null || _data.value.templates.size < MAX_TEMPLATES, WorkflowProblem.Full)
        val item = WorkflowTemplate(id, expected + 1, title.trim(), clean)
        commit(_data.value.copy(templates = listOf(item) + _data.value.templates.filterNot { it.id == id }))
        return item
    }

    @Synchronized fun deleteTemplate(id: String, expected: Int) {
        load()
        requireProblem(_data.value.templates.find { it.id == id }?.revision == expected, WorkflowProblem.Changed)
        commit(_data.value.copy(templates = _data.value.templates.filterNot { it.id == id }))
    }

    @Synchronized fun start(
        id: String,
        expected: Int,
        input: String,
        params: Map<String, String> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): WorkflowRun {
        load()
        requireProblem(input.length <= MAX_INPUT, WorkflowProblem.Invalid)
        requireIdle()
        val template = _data.value.templates.find { it.id == id && it.revision == expected }
            ?: throw WorkflowException(WorkflowProblem.Changed)
        return startWithTemplate(template, input, params, triggerKey = null, originThreadId = null, unattended = false, zone = zone, locale = locale)
    }

    @Synchronized fun startScheduled(
        templateId: String,
        input: String,
        params: Map<String, String> = emptyMap(),
        triggerKey: String,
        originThreadId: String,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): WorkflowRun {
        load()
        requireProblem(input.length <= MAX_INPUT, WorkflowProblem.Invalid)
        requireIdle()
        val template = _data.value.templates.find { it.id == templateId }
            ?: throw WorkflowException(WorkflowProblem.Changed)
        return startWithTemplate(template, input, params, triggerKey = triggerKey, originThreadId = originThreadId, unattended = true, zone = zone, locale = locale)
    }

    private fun startWithTemplate(
        template: WorkflowTemplate,
        input: String,
        params: Map<String, String>,
        triggerKey: String?,
        originThreadId: String?,
        unattended: Boolean,
        zone: ZoneId,
        locale: Locale,
    ): WorkflowRun {
        val cleanParams = params.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val rendered = WorkflowParams.renderTemplate(template, cleanParams, clock(), zone, locale)
            ?: throw WorkflowException(WorkflowProblem.Invalid)
        requireProblem(rendered.steps.all { it.instruction.length <= MAX_RENDERED_INSTRUCTION && it.continueIf.length <= MAX_CONTINUE_IF }, WorkflowProblem.Invalid)
        val run = WorkflowRun(
            id = UUID.randomUUID().toString(),
            template = rendered,
            input = input.trim(),
            createdAt = clock(),
            triggerKey = triggerKey,
            originThreadId = originThreadId,
            unattended = unattended,
            params = cleanParams,
        )
        val old = _data.value.runs
        // Prune only completed history; unresolved runs must remain reviewable.
        var prunedId: String? = null
        val retained = if (old.size < MAX_RUNS) old else old.indexOfLast { it.phase == WorkflowPhase.Completed }
            .takeIf { it >= 0 }?.let { index ->
                prunedId = old[index].id
                old.filterIndexed { i, _ -> i != index }
            }
            ?: throw WorkflowException(WorkflowProblem.Full)
        commit(_data.value.copy(runs = listOf(run) + retained))
        prunedId?.let { id -> runCatching { onRunsRemoved(listOf(id)) } }
        return run
    }

    @Synchronized fun prepare(id: String): WorkflowDispatch? {
        load()
        val run = get(id)
        if (run.phase != WorkflowPhase.Active || run.current != null) return null
        val index = run.completed
        if (index >= run.template.steps.size) return null
        val attempt = StepAttempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), AttemptPhase.Dispatching)
        val updated = run.copy(attempts = run.attempts.mapIndexed { i, list -> if (i == index) list + attempt else list })
        update(updated)
        return dispatch(updated, attempt)
    }

    /** At-most-once claim, including duplicate Android start intents. */
    @Synchronized fun claim(attemptId: String): WorkflowDispatch? {
        load()
        val run = _data.value.runs.find { it.current?.id == attemptId } ?: return null
        val attempt = run.current ?: return null
        if (attempt.phase != AttemptPhase.Dispatching || run.phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing)) return null
        val updated = replaceAttempt(run, attempt.copy(phase = AttemptPhase.Running))
        update(updated)
        return dispatch(updated, updated.current!!)
    }

    @Synchronized fun finish(
        attemptId: String,
        successful: Boolean,
        result: String,
        problem: String = "",
        earlyStop: Boolean = false,
        earlyStopReason: String = "",
    ): String? {
        load()
        val run = _data.value.runs.find { it.current?.id == attemptId } ?: return null
        val attempt = run.current!!
        if (attempt.phase !in listOf(AttemptPhase.Dispatching, AttemptPhase.Running)) return null
        val complete = successful && result.isNotBlank() && result.length <= MAX_RESULT
        val finishedStep = run.template.steps[run.completed]
        val next = replaceAttempt(run, attempt.copy(phase = if (complete) AttemptPhase.Done else AttemptPhase.Review,
            result = result.take(MAX_RESULT), problem = if (result.length > MAX_RESULT) "result_too_long" else problem.take(1000)))
        val stoppedEarly = complete && earlyStop && next.completed < next.template.steps.size
        val allDone = complete && next.completed == next.template.steps.size
        val checkpoint = complete && !stoppedEarly && !allDone && run.phase != WorkflowPhase.Pausing && finishedStep.pauseAfter
        val phase = when {
            !complete -> WorkflowPhase.Review
            stoppedEarly || allDone -> WorkflowPhase.Completed
            run.phase == WorkflowPhase.Pausing || checkpoint -> WorkflowPhase.Paused
            else -> WorkflowPhase.Active
        }
        update(next.copy(
            phase = phase,
            earlyStopped = stoppedEarly,
            earlyStopReason = if (stoppedEarly) earlyStopReason.trim().take(500) else "",
            checkpointPaused = checkpoint,
        ))
        return run.id
    }

    @Synchronized fun pause(id: String) {
        load()
        val run = get(id)
        if (run.phase != WorkflowPhase.Active) return
        update(run.copy(phase = if (run.current == null) WorkflowPhase.Paused else WorkflowPhase.Pausing, checkpointPaused = false))
    }

    @Synchronized fun resume(id: String) {
        load(); requireIdle()
        val run = get(id)
        requireProblem(run.phase == WorkflowPhase.Paused, WorkflowProblem.Changed)
        update(run.copy(phase = WorkflowPhase.Active, checkpointPaused = false))
    }

    /** Edits the just-completed step's result while paused at a checkpoint so the next step receives the updated text. */
    @Synchronized fun editCompletedResult(runId: String, stepIndex: Int, newResult: String): WorkflowRun {
        load()
        val run = get(runId)
        requireProblem(run.phase == WorkflowPhase.Paused && stepIndex == run.completed - 1 && stepIndex >= 0, WorkflowProblem.Changed)
        val trimmed = newResult.trim()
        requireProblem(trimmed.isNotEmpty() && trimmed.length <= MAX_RESULT, WorkflowProblem.Invalid)
        val latest = run.attempts[stepIndex].lastOrNull() ?: throw WorkflowException(WorkflowProblem.Changed)
        val updatedAttempts = run.attempts.mapIndexed { idx, list ->
            if (idx == stepIndex) list.dropLast(1) + latest.copy(result = trimmed, manual = true) else list
        }
        return run.copy(attempts = updatedAttempts).also(::update)
    }

    /** Explicit user resolution only. Retrying starts a new conversation and keeps the previous attempt. */
    @Synchronized fun resolve(id: String, attemptId: String, completedResult: String?) {
        load(); requireIdle()
        val run = get(id)
        requireProblem(run.phase == WorkflowPhase.Review && run.current?.id == attemptId, WorkflowProblem.Changed)
        if (completedResult != null) {
            requireProblem(completedResult.isNotBlank() && completedResult.length <= MAX_RESULT, WorkflowProblem.Invalid)
            val finishedStep = run.template.steps[run.completed]
            val next = replaceAttempt(run, run.current!!.copy(phase = AttemptPhase.Done, result = completedResult.trim(), manual = true))
            val allDone = next.completed == next.template.steps.size
            val checkpoint = !allDone && finishedStep.pauseAfter
            update(next.copy(
                phase = when {
                    allDone -> WorkflowPhase.Completed
                    checkpoint -> WorkflowPhase.Paused
                    else -> WorkflowPhase.Active
                },
                checkpointPaused = checkpoint,
            ))
        } else {
            requireProblem(run.attempts[run.completed].size < MAX_ATTEMPTS, WorkflowProblem.Full)
            val next = StepAttempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), AttemptPhase.Dispatching)
            update(run.copy(phase = WorkflowPhase.Active, checkpointPaused = false, attempts = run.attempts.mapIndexed { index, list ->
                if (index == run.completed) list + next else list
            }))
        }
    }

    @Synchronized fun pendingDispatch(id: String): WorkflowDispatch? {
        load()
        val run = get(id)
        return run.current?.takeIf { it.phase == AttemptPhase.Dispatching }?.let { dispatch(run, it) }
    }

    @Synchronized fun deleteRun(id: String) {
        load()
        requireProblem(get(id).phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing), WorkflowProblem.Busy)
        commit(_data.value.copy(runs = _data.value.runs.filterNot { it.id == id }))
        runCatching { onRunsRemoved(listOf(id)) }
    }

    private fun requireIdle() = requireProblem(_data.value.runs.none { it.phase in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing) }, WorkflowProblem.Busy)
    private fun get(id: String) = _data.value.runs.find { it.id == id } ?: throw WorkflowException(WorkflowProblem.Changed)
    private fun update(run: WorkflowRun) = commit(_data.value.copy(runs = _data.value.runs.map { if (it.id == run.id) run else it }))
    private fun replaceAttempt(run: WorkflowRun, attempt: StepAttempt) = run.copy(attempts = run.attempts.mapIndexed { index, list ->
        if (index == run.completed) list.dropLast(1) + attempt else list
    })
    private fun dispatch(run: WorkflowRun, attempt: StepAttempt): WorkflowDispatch {
        val stepIndex = run.completed
        val priorResults = (0 until stepIndex).map { i -> run.attempts[i].lastOrNull()?.result.orEmpty() }
        val priorStepTitles = (0 until stepIndex).map { i -> run.template.steps[i].title }
        return WorkflowDispatch(
            runId = run.id,
            attemptId = attempt.id,
            conversationId = attempt.conversationId,
            step = run.template.steps[stepIndex],
            input = run.input,
            previous = priorResults.lastOrNull().orEmpty(),
            stepIndex = stepIndex,
            priorResults = priorResults,
            priorStepTitles = priorStepTitles,
            unattended = run.unattended,
            triggerKey = run.triggerKey,
        )
    }
    private fun commit(data: WorkflowData) {
        persist(data)
        rebuildConversationMap(data)
        _data.value = data
    }
    private fun rebuildConversationMap(data: WorkflowData) {
        val next = HashMap<String, String>()
        data.runs.forEach { run ->
            run.attempts.forEach { list ->
                list.forEach { attempt -> next[attempt.conversationId] = run.id }
            }
        }
        convToRun.putAll(next)
        convToRun.keys.retainAll(next.keys)
    }
    private fun persist(data: WorkflowData) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val bytes = cipher.encrypt(json.encodeToString(data).toByteArray(Charsets.UTF_8))
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) { throw WorkflowException(WorkflowProblem.Write) }
        finally { tmp.delete() }
    }
    private fun validate(data: WorkflowData) {
        require(data.templates.size <= MAX_TEMPLATES && data.runs.size <= MAX_RUNS)
        require(data.templates.map { it.id }.distinct().size == data.templates.size)
        require(data.runs.map { it.id }.distinct().size == data.runs.size)
        data.templates.forEach { require(it.id.isNotBlank() && it.revision > 0); checkTemplate(it.title, it.steps) }
        data.runs.forEach { run ->
            checkTemplate(run.template.title, run.template.steps, maxInstruction = MAX_RENDERED_INSTRUCTION, maxContinueIf = MAX_RENDERED_CONTINUE_IF)
            require(run.input.length <= MAX_INPUT && run.attempts.size == run.template.steps.size)
            run.attempts.forEach { list ->
                require(list.size <= MAX_ATTEMPTS)
                list.forEach { require(it.id.isNotBlank() && it.conversationId.isNotBlank() && it.result.length <= MAX_RESULT) }
            }
            require(run.attempts.drop(run.completed + 1).all { it.isEmpty() })
            if (run.earlyStopped) require(run.phase == WorkflowPhase.Completed && run.completed in 1 until run.template.steps.size)
            else require((run.phase == WorkflowPhase.Completed) == (run.completed == run.template.steps.size))
        }
    }
    companion object {
        const val FILE = "workflows-v1.bin"
        const val MAX_TEMPLATES = 30
        const val MAX_RUNS = 50
        const val MAX_STEPS = 8
        const val MAX_ATTEMPTS = 10
        const val MAX_INPUT = 4000
        const val MAX_RESULT = 12000
        const val MAX_INSTRUCTION = 2000
        const val MAX_CONTINUE_IF = 500
        private const val MAX_RENDERED_INSTRUCTION = 8000
        private const val MAX_RENDERED_CONTINUE_IF = 2000
        private fun requireProblem(ok: Boolean, problem: WorkflowProblem) { if (!ok) throw WorkflowException(problem) }
        private fun checkTemplate(
            title: String,
            steps: List<WorkflowStep>,
            maxInstruction: Int = MAX_INSTRUCTION,
            maxContinueIf: Int = MAX_CONTINUE_IF,
        ) {
            requireProblem(title.isNotBlank() && title.length <= 100 && steps.size in 2..MAX_STEPS &&
                steps.map { it.id }.distinct().size == steps.size && steps.all {
                    it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 100 &&
                        it.instruction.isNotBlank() && it.instruction.length <= maxInstruction &&
                        it.continueIf.length <= maxContinueIf
                } && WorkflowParams.userFields(steps).size <= TaskTemplate.MAX_FIELDS, WorkflowProblem.Invalid)
        }
    }
}
