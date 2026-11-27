package com.wangzan101.mika.workflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.MikaTextField
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.workflow.WorkflowException
import com.wangzan101.mika.workflow.WorkflowParams
import com.wangzan101.mika.workflow.WorkflowProblem
import com.wangzan101.mika.workflow.WorkflowStep
import com.wangzan101.mika.workflow.WorkflowStore
import com.wangzan101.mika.workflow.WorkflowTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun WorkflowEditor(
    store: WorkflowStore,
    initial: WorkflowTemplate,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var encoded by rememberSaveable(initial.id) { mutableStateOf(Json.encodeToString(initial.steps)) }
    val steps = remember(encoded) { Json.decodeFromString<List<WorkflowStep>>(encoded) }
    val selections = remember(initial.id) { mutableStateMapOf<String, TextRange>() }
    val compositions = remember(initial.id) { mutableStateMapOf<String, TextRange?>() }
    var addingFieldFor by remember { mutableStateOf<String?>(null) }
    var fieldName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<WorkflowProblem?>(null) }
    val scope = rememberCoroutineScope()

    fun change(next: List<WorkflowStep>) {
        encoded = Json.encodeToString(next)
        error = null
    }

    fun updateStep(stepId: String, transform: (WorkflowStep) -> WorkflowStep) {
        change(steps.map { if (it.id == stepId) transform(it) else it })
    }

    fun insertToken(stepId: String, token: String) {
        val step = steps.find { it.id == stepId } ?: return
        val sel = selections[stepId] ?: TextRange(step.instruction.length)
        val start = sel.min.coerceIn(0, step.instruction.length)
        val end = sel.max.coerceIn(start, step.instruction.length)
        val nextText = step.instruction.replaceRange(start, end, token)
        selections[stepId] = TextRange(start + token.length)
        compositions.remove(stepId)
        updateStep(stepId) { it.copy(instruction = nextText) }
    }

    BackHandler { if (!busy) onBack() }
    PageScaffold(stringResource(R.string.workflow_edit), { if (!busy) onBack() }) {
        MikaTextField(
            value = title,
            onValueChange = { title = it; error = null },
            label = { Text(stringResource(R.string.recommendations_title_label)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("workflow-title"),
        )
        steps.forEachIndexed { index, step ->
            key(step.id) {
                GroupLabel(stringResource(R.string.workflow_step_number, index + 1))
                MikaTextField(
                    value = step.title,
                    onValueChange = { value -> updateStep(step.id) { it.copy(title = value) } },
                    label = { Text(stringResource(R.string.workflow_step_name)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("workflow-step-title-$index"),
                )
                val rawSel = selections[step.id] ?: TextRange(step.instruction.length)
                val clampedSel = TextRange(
                    rawSel.start.coerceIn(0, step.instruction.length),
                    rawSel.end.coerceIn(0, step.instruction.length),
                )
                val comp = compositions[step.id]?.takeIf {
                    it.start in 0..step.instruction.length && it.end in 0..step.instruction.length
                }
                MikaTextField(
                    value = TextFieldValue(step.instruction, clampedSel, comp),
                    onValueChange = { tfv ->
                        selections[step.id] = tfv.selection
                        compositions[step.id] = tfv.composition
                        if (tfv.text != step.instruction) {
                            updateStep(step.id) { it.copy(instruction = tfv.text) }
                        }
                    },
                    label = { Text(stringResource(R.string.workflow_instruction)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("workflow-step-instruction-$index"),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MikaButton(
                        text = "+ ${stringResource(R.string.task_field_add)}",
                        onClick = { fieldName = ""; addingFieldFor = step.id },
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Small,
                        enabled = !busy,
                        modifier = Modifier.testTag("workflow-step-add-field-$index"),
                    )
                    MikaButton(
                        text = stringResource(R.string.workflow_chip_today),
                        onClick = { insertToken(step.id, "{{today}}") },
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                    if (index > 0) {
                        MikaButton(
                            text = stringResource(R.string.workflow_chip_prev_step, index),
                            onClick = { insertToken(step.id, "{{step.$index}}") },
                            kind = ButtonKind.Tonal,
                            size = ButtonSize.Small,
                            enabled = !busy,
                        )
                    }
                }
                GroupCard(Modifier.padding(top = 8.dp)) {
                    ListRow(
                        icon = null,
                        title = stringResource(R.string.workflow_step_readonly),
                        subtitle = stringResource(R.string.workflow_step_readonly_sub),
                        first = true,
                    ) {
                        MikaSwitch(
                            checked = step.readOnly,
                            onCheckedChange = { checked -> updateStep(step.id) { it.copy(readOnly = checked) } },
                            enabled = !busy,
                            modifier = Modifier.testTag("workflow-step-readonly-$index"),
                        )
                    }
                    if (index < steps.lastIndex) {
                        ListRow(
                            icon = null,
                            title = stringResource(R.string.workflow_step_pause_after),
                            subtitle = stringResource(R.string.workflow_step_pause_after_sub),
                            first = false,
                        ) {
                            MikaSwitch(
                                checked = step.pauseAfter,
                                onCheckedChange = { checked -> updateStep(step.id) { it.copy(pauseAfter = checked) } },
                                enabled = !busy,
                                modifier = Modifier.testTag("workflow-step-pause-$index"),
                            )
                        }
                    }
                }
                if (index < steps.lastIndex) {
                    MikaTextField(
                        value = step.continueIf,
                        onValueChange = { value -> updateStep(step.id) { it.copy(continueIf = value) } },
                        label = { Text(stringResource(R.string.workflow_step_continue_if)) },
                        placeholder = { Text(stringResource(R.string.workflow_step_continue_if_hint)) },
                        maxLines = 3,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            .testTag("workflow-step-continue-if-$index"),
                    )
                }
                Row {
                    TextButton(
                        { change(steps.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                        enabled = !busy && index > 0,
                    ) { Text(stringResource(R.string.workflow_move_up)) }
                    TextButton(
                        { change(steps.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                        enabled = !busy && index < steps.lastIndex,
                    ) { Text(stringResource(R.string.workflow_move_down)) }
                    TextButton(
                        { change(steps.filterNot { it.id == step.id }) },
                        enabled = !busy && steps.size > 2,
                    ) { Text(stringResource(R.string.recommendations_delete)) }
                }
            }
        }
        TextButton(
            { change(steps + WorkflowStep(title = "", instruction = "")) },
            enabled = !busy && steps.size < WorkflowStore.MAX_STEPS,
            modifier = Modifier.testTag("workflow-add-step"),
        ) { Text(stringResource(R.string.workflow_add_step)) }

        val userFields = remember(steps) { WorkflowParams.userFields(steps) }
        if (userFields.isNotEmpty()) {
            FooterNote(stringResource(R.string.workflow_fields_summary, userFields.joinToString(", ")))
            if (userFields.size > TaskTemplate.MAX_FIELDS) {
                FooterNote(
                    stringResource(R.string.task_fields_too_many, TaskTemplate.MAX_FIELDS),
                    color = MikaTheme.colors.bad,
                )
            }
        }
        error?.let { FooterNote(workflowError(it), color = MikaTheme.colors.bad) }
        MikaButton(
            text = stringResource(R.string.save),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.saveTemplate(initial.id, initial.revision, title, steps) }
                        onSaved(initial.id)
                    } catch (e: WorkflowException) {
                        error = e.problem
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag("workflow-save"),
        )
    }

    addingFieldFor?.let { targetStepId ->
        val trimmed = fieldName.trim()
        val valid = TaskTemplate.validName(trimmed)
        val showInvalid = fieldName.isNotEmpty() && !valid
        MikaDialog(
            onDismissRequest = { addingFieldFor = null },
            title = { Text(stringResource(R.string.task_field_add)) },
            text = {
                MikaTextField(
                    value = fieldName,
                    onValueChange = { fieldName = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.task_field_name)) },
                    isError = showInvalid,
                    supportingText = if (showInvalid) ({ Text(stringResource(R.string.task_field_name_invalid)) }) else null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("workflow-field-name"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        insertToken(targetStepId, "{{$trimmed}}")
                        fieldName = ""
                        addingFieldFor = null
                    },
                    enabled = !busy && valid,
                    modifier = Modifier.testTag("workflow-field-insert"),
                ) { Text(stringResource(R.string.task_field_insert)) }
            },
            dismissButton = {
                TextButton({ addingFieldFor = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
