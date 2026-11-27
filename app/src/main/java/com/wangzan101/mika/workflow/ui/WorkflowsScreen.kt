package com.wangzan101.mika.workflow.ui

import androidx.activity.compose.BackHandler
import com.wangzan101.mika.core.run.PauseFlag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.trigger.CONDITION_PLAN_EDITED
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.workflow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import com.wangzan101.mika.chat.ui.copyMessage
import com.wangzan101.mika.memory.ui.Row
import com.wangzan101.mika.settings.ui.models.edit
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.ui.Route

@Composable
internal fun WorkflowsScreen(runtime: WorkflowRuntime, onBack: () -> Unit, onOpenConversation: ((String) -> Unit)? = null, onUseResult: ((String) -> Unit)? = null, initialRunId: String? = null) {
    val data by runtime.store.data.collectAsState()
    val loaded by runtime.store.loaded.collectAsState()
    val saveError by runtime.saveError.collectAsState()
    var selectedTemplate by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRun by rememberSaveable { mutableStateOf<String?>(initialRunId) }
    LaunchedEffect(initialRunId) {
        if (initialRunId != null) {
            selectedTemplate = null
            selectedRun = initialRunId
        }
    }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<WorkflowProblem?>(null) }
    var starting by remember { mutableStateOf<WorkflowTemplate?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var resolving by remember { mutableStateOf<WorkflowRun?>(null) }
    var editingResult by remember { mutableStateOf<WorkflowRun?>(null) }
    var retrying by remember { mutableStateOf<WorkflowRun?>(null) }
    var viewing by remember { mutableStateOf<StepAttempt?>(null) }
    var artifacts by remember { mutableStateOf<List<WorkflowWorkspace.Artifact>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun perform(block: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: WorkflowException) { error = e.problem }
            finally { busy = false }
        }
    }
    LaunchedEffect(runtime) {
        try { withContext(Dispatchers.IO) { runtime.store.load() } }
        catch (e: WorkflowException) { error = e.problem }
    }
    val template = data.templates.find { it.id == selectedTemplate }
    val run = data.runs.find { it.id == selectedRun }
    LaunchedEffect(run?.id, run?.completed, run?.phase) {
        artifacts = if (run != null) withContext(Dispatchers.IO) { WorkflowWorkspace(SandboxDirs(context)).listArtifacts(run.id) } else emptyList()
    }
    val back = { if (selectedRun != null) selectedRun = null else if (selectedTemplate != null) selectedTemplate = null else onBack() }
    BackHandler(onBack = back)
    editor?.let { encoded ->
        val initial = remember(encoded) { Json.decodeFromString<WorkflowTemplate>(encoded) }
        WorkflowEditor(runtime.store, initial, onBack = { editor = null }, onSaved = { selectedTemplate = it; editor = null })
        return
    }
    PageScaffold(run?.template?.title ?: template?.title ?: stringResource(R.string.workflows_title), back) {
        if (error != null) FooterNote(workflowError(error!!), color = MikaTheme.colors.bad)
        if (!loaded) {
            if (error == null) CircularProgressIndicator(Modifier.padding(16.dp))
            else MikaButton(stringResource(R.string.retry), { perform { runtime.store.load() } })
        } else if (run != null) {
            Text(stringResource(phaseLabel(run.phase)), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("workflow-phase"))
            Text(stringResource(R.string.workflow_progress, run.completed, run.template.steps.size), Modifier.padding(vertical = 8.dp))
            if (saveError) {
                FooterNote(stringResource(R.string.workflow_result_save_failed), color = MikaTheme.colors.bad)
                MikaButton(stringResource(R.string.retry), { perform { runtime.retrySave() } }, enabled = !busy)
            }
            when (run.phase) {
                WorkflowPhase.Active -> MikaButton(stringResource(R.string.workflow_pause), { perform { runtime.store.pause(run.id) } }, enabled = !busy)
                WorkflowPhase.Paused -> if (run.checkpointPaused) {
                    FooterNote(stringResource(R.string.workflow_checkpoint_note))
                    val lastIdx = run.completed - 1
                    val lastResult = if (lastIdx >= 0) run.attempts.getOrNull(lastIdx)?.lastOrNull()?.result.orEmpty() else ""
                    if (lastResult.isNotBlank()) {
                        SelectionContainer { Text(lastResult, Modifier.padding(bottom = 12.dp)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MikaButton(
                            stringResource(R.string.workflow_continue),
                            { perform { runtime.resume(run.id) } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("workflow-continue"),
                        )
                        if (lastIdx >= 0) {
                            MikaButton(
                                stringResource(R.string.workflow_edit_result),
                                { editingResult = run },
                                kind = ButtonKind.Secondary,
                                enabled = !busy,
                                modifier = Modifier.weight(1f).testTag("workflow-edit-result"),
                            )
                        }
                    }
                } else {
                    MikaButton(stringResource(R.string.workflow_continue), { perform { runtime.resume(run.id) } }, enabled = !busy, modifier = Modifier.testTag("workflow-continue"))
                }
                WorkflowPhase.Review -> {
                    FooterNote(stringResource(R.string.workflow_review_note))
                    run.current?.let { current ->
                        if (current.problem.isNotBlank()) FooterNote(when (current.problem) {
                            "interrupted" -> stringResource(R.string.workflow_interrupted)
                            "start_failed" -> stringResource(R.string.workflow_start_failed)
                            PauseFlag.REASON -> stringResource(R.string.workflow_stopped_paused)
                            "result_too_long" -> stringResource(R.string.workflow_result_too_long)
                            "condition_uncertain" -> stringResource(R.string.workflow_condition_uncertain)
                            else -> current.problem
                        }, color = MikaTheme.colors.bad)
                        if (onOpenConversation != null) MikaButton(stringResource(R.string.workflow_view_chat), { onOpenConversation(current.conversationId) }, kind = ButtonKind.Secondary)
                    }
                    MikaButton(stringResource(R.string.workflow_mark_done), { resolving = run }, modifier = Modifier.testTag("workflow-resolve"), enabled = !busy)
                    TextButton({ retrying = run }, enabled = !busy, modifier = Modifier.testTag("workflow-retry")) { Text(stringResource(R.string.workflow_retry_step)) }
                }
                WorkflowPhase.Completed -> {
                    if (run.earlyStopped) {
                        val stopNote = if (run.earlyStopReason.isNotBlank()) {
                            stringResource(R.string.workflow_early_stopped_reason, run.earlyStopReason)
                        } else {
                            stringResource(R.string.workflow_early_stopped)
                        }
                        FooterNote(stopNote)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MikaButton(stringResource(R.string.chat_copy), { copyMessage(context, run.result) }, modifier = Modifier.weight(1f))
                        if (onUseResult != null) MikaButton(stringResource(R.string.workflow_use_result), { onUseResult(run.result) }, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f).testTag("workflow-use-result"))
                    }
                    SelectionContainer { Text(run.result, Modifier.padding(vertical = 16.dp).testTag("workflow-final-result")) }
                    data.templates.find { it.id == run.template.id }?.let { current ->
                        TextButton({ starting = current }, enabled = !busy) { Text(stringResource(R.string.workflow_run_again)) }
                    }
                }
                WorkflowPhase.Pausing -> Unit
            }
            GroupLabel(stringResource(R.string.workflow_steps))
            GroupCard {
                run.template.steps.forEachIndexed { index, step ->
                    val attempts = run.attempts[index]
                    val latest = attempts.lastOrNull()
                    ListRow(MikaIcons.Route, "${index + 1}. ${step.title}", stringResource(when (latest?.phase) {
                        AttemptPhase.Done -> R.string.workflow_done
                        AttemptPhase.Dispatching, AttemptPhase.Running -> R.string.workflow_running
                        AttemptPhase.Review -> R.string.workflow_review
                        null -> if (run.earlyStopped) R.string.workflow_skipped else R.string.workflow_pending
                    }), first = index == 0, onClick = latest?.let { { viewing = it } })
                    if (attempts.size > 1) attempts.dropLast(1).forEachIndexed { n, previous ->
                        ListRow(null, stringResource(R.string.workflow_previous_attempt, n + 1), onClick = { viewing = previous })
                    }
                }
            }
            if (artifacts.isNotEmpty()) {
                GroupLabel(stringResource(R.string.workflow_workspace_files))
                GroupCard {
                    artifacts.forEachIndexed { index, a ->
                        ListRow(MikaIcons.Doc, a.relativePath, WorkflowPromptBuilder.formatSize(a.bytes), first = index == 0)
                    }
                }
            }
            if (run.phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing)) {
                TextButton({ deleting = run.id to true }, enabled = !busy) { Text(stringResource(R.string.workflow_delete_record)) }
            }
        } else if (template != null) {
            MikaButton(stringResource(R.string.workflow_start), { starting = template }, enabled = !busy, modifier = Modifier.testTag("workflow-start"))
            val fields = remember(template) { WorkflowParams.userFields(template.steps) }
            if (fields.isNotEmpty()) {
                FooterNote(stringResource(R.string.workflow_fields_summary, fields.joinToString(", ")))
            }
            GroupCard {
                template.steps.forEachIndexed { index, step ->
                    val badges = buildList {
                        if (step.readOnly) add(stringResource(R.string.workflow_step_readonly))
                        if (index < template.steps.lastIndex && step.pauseAfter) add(stringResource(R.string.workflow_step_pause_after))
                        if (index < template.steps.lastIndex && step.continueIf.isNotBlank()) add(stringResource(R.string.workflow_step_continue_if))
                    }
                    val sub = if (badges.isEmpty()) step.instruction else badges.joinToString(" · ") + "\n" + step.instruction
                    ListRow(MikaIcons.Route, "${index + 1}. ${step.title}", sub, first = index == 0)
                }
            }
            Row {
                TextButton({ editor = Json.encodeToString(template) }, modifier = Modifier.testTag("workflow-edit")) { Text(stringResource(R.string.workflow_edit)) }
                TextButton({ editor = Json.encodeToString(template.copy(id = UUID.randomUUID().toString(), revision = 0)) }) { Text(stringResource(R.string.workflow_duplicate)) }
                TextButton({ deleting = template.id to false }) { Text(stringResource(R.string.recommendations_delete)) }
            }
        } else {
            MikaButton(stringResource(R.string.workflow_new), { editor = Json.encodeToString(WorkflowTemplate(UUID.randomUUID().toString(), 0, "", listOf(
                WorkflowStep(title = "", instruction = ""), WorkflowStep(title = "", instruction = "")))) }, modifier = Modifier.testTag("workflow-new"))
            if (data.templates.isNotEmpty()) GroupCard {
                data.templates.forEachIndexed { index, item -> ListRow(MikaIcons.Route, item.title, stringResource(R.string.workflow_step_count, item.steps.size),
                    first = index == 0, onClick = { selectedTemplate = item.id }) }
            }
            if (data.runs.isNotEmpty()) {
                GroupLabel(stringResource(R.string.workflow_history))
                GroupCard {
                    data.runs.forEachIndexed { index, item -> ListRow(MikaIcons.Route, item.template.title,
                        stringResource(phaseLabel(item.phase)) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)),
                        first = index == 0, onClick = { selectedRun = item.id }) }
                }
            }
        }
    }
    starting?.let { item ->
        WorkflowStartDialog(
            template = item,
            onDismiss = { starting = null },
            onStart = { text, params ->
                val id = withContext(Dispatchers.IO) { runtime.start(item, text, params) }
                selectedRun = id
                starting = null
            },
        )
    }
    editingResult?.let { item ->
        val idx = (item.completed - 1).coerceAtLeast(0)
        val initial = item.attempts.getOrNull(idx)?.lastOrNull()?.result.orEmpty()
        WorkflowTextDialog(
            title = stringResource(R.string.workflow_edit_result),
            label = stringResource(R.string.workflow_result_label),
            initial = initial,
            action = stringResource(R.string.save),
            allowEmpty = false,
            onDismiss = { editingResult = null },
            onSave = { text ->
                withContext(Dispatchers.IO) { runtime.editPausedResult(item.id, idx, text) }
                editingResult = null
            },
        )
    }
    resolving?.let { item ->
        WorkflowTextDialog(stringResource(R.string.workflow_mark_done), stringResource(R.string.workflow_result_label), item.current?.result.orEmpty(), stringResource(R.string.workflow_continue),
            onDismiss = { resolving = null }, onSave = { text ->
                withContext(Dispatchers.IO) { runtime.resolve(item.id, item.current!!.id, text) }; resolving = null
            })
    }
    retrying?.let { item ->
        MikaDialog(onDismissRequest = { retrying = null }, title = { Text(stringResource(R.string.workflow_retry_step)) },
            text = { Text(stringResource(R.string.workflow_retry_note)) },
            confirmButton = { TextButton({ perform { runtime.resolve(item.id, item.current!!.id, null) }; retrying = null }, modifier = Modifier.testTag("workflow-retry-confirm")) { Text(stringResource(R.string.workflow_retry_step)) } },
            dismissButton = { TextButton({ retrying = null }) { Text(stringResource(R.string.cancel)) } })
    }
    viewing?.let { item ->
        MikaDialog(onDismissRequest = { viewing = null }, title = { Text(stringResource(R.string.workflow_result_label)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (item.manual) FooterNote(stringResource(R.string.workflow_manual_result))
                if (item.result.isNotBlank()) SelectionContainer { Text(item.result) }
                if (onOpenConversation != null) TextButton({ viewing = null; onOpenConversation(item.conversationId) }) { Text(stringResource(R.string.workflow_view_chat)) }
            } }, confirmButton = { TextButton({ viewing = null }) { Text(stringResource(R.string.ok_got_it)) } })
    }
    deleting?.let { (id, record) ->
        MikaDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(if (record) R.string.workflow_delete_record else R.string.workflow_delete_template)) },
            confirmButton = { TextButton({
                val revision = template?.revision
                val targetRun = if (record) data.runs.find { it.id == id } else null
                perform {
                    if (record) {
                        if (targetRun != null && targetRun.triggerKey != null && targetRun.phase != WorkflowPhase.Completed) {
                            TriggerRuntime.skippedWorkflow(context, targetRun.triggerKey, CONDITION_PLAN_EDITED)
                        }
                        runtime.store.deleteRun(id)
                    } else {
                        runtime.store.deleteTemplate(id, revision ?: 0)
                    }
                }
                deleting = null
            }) { Text(stringResource(R.string.recommendations_delete)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable internal fun WorkflowTextDialog(title: String, label: String, initial: String, action: String, allowEmpty: Boolean = false,
    onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<WorkflowProblem?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MikaDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MikaTextField(value, { value = it; error = null }, label = { Text(label) }, minLines = 3, maxLines = 8,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("workflow-dialog-input"))
            error?.let { FooterNote(workflowError(it), color = MikaTheme.colors.bad) }
        }
    }, confirmButton = { TextButton({
        busy = true
        scope.launch { try { onSave(value) } catch (e: WorkflowException) { error = e.problem } finally { busy = false } }
    }, enabled = !busy && (allowEmpty || value.isNotBlank()), modifier = Modifier.testTag("workflow-dialog-apply")) { Text(action) } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

private fun phaseLabel(phase: WorkflowPhase) = when (phase) {
    WorkflowPhase.Active -> R.string.workflow_running
    WorkflowPhase.Pausing -> R.string.workflow_pausing
    WorkflowPhase.Paused -> R.string.workflow_paused
    WorkflowPhase.Review -> R.string.workflow_review
    WorkflowPhase.Completed -> R.string.workflow_done
}
@Composable internal fun workflowError(problem: WorkflowProblem) = stringResource(when (problem) {
    WorkflowProblem.Invalid -> R.string.workflow_invalid
    WorkflowProblem.Full -> R.string.workflow_full
    WorkflowProblem.Changed -> R.string.workflow_changed
    WorkflowProblem.Busy -> R.string.workflow_busy
    WorkflowProblem.Read -> R.string.workflow_read_failed
    WorkflowProblem.Write -> R.string.recommendations_save_failed
    WorkflowProblem.ResultTooLong -> R.string.workflow_result_too_long
    WorkflowProblem.Paused -> R.string.pause_blocked_workflow
})
