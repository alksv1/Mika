package com.wangzan101.mika.workflow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaTextField
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.workflow.WorkflowException
import com.wangzan101.mika.workflow.WorkflowParams
import com.wangzan101.mika.workflow.WorkflowProblem
import com.wangzan101.mika.workflow.WorkflowTemplate
import kotlinx.coroutines.launch

@Composable
internal fun WorkflowStartDialog(
    template: WorkflowTemplate,
    onDismiss: () -> Unit,
    onStart: suspend (input: String, params: Map<String, String>) -> Unit,
) {
    val fields = remember(template) { WorkflowParams.userFields(template.steps) }
    val values = remember(template.id, template.revision) { mutableStateMapOf<String, String>() }
    var input by rememberSaveable(template.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<WorkflowProblem?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tooManyFields = fields.size > TaskTemplate.MAX_FIELDS
    val canStart = !busy && !tooManyFields && fields.all { !values[it].isNullOrBlank() }

    MikaDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workflow_start)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (tooManyFields) {
                    FooterNote(
                        stringResource(R.string.task_fields_too_many, TaskTemplate.MAX_FIELDS),
                        color = MikaTheme.colors.bad,
                    )
                } else {
                    fields.forEachIndexed { index, field ->
                        MikaTextField(
                            value = values[field].orEmpty(),
                            onValueChange = { values[field] = it; error = null },
                            label = { Text(field) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                .testTag("workflow-param-$index"),
                        )
                    }
                }
                MikaTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text(stringResource(R.string.workflow_run_input)) },
                    minLines = 3,
                    maxLines = 8,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("workflow-dialog-input"),
                )
                error?.let { FooterNote(workflowError(it), color = MikaTheme.colors.bad) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    val paramMap = fields.associateWith { values[it].orEmpty().trim() }
                    scope.launch {
                        try {
                            onStart(input, paramMap)
                        } catch (e: WorkflowException) {
                            error = e.problem
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = canStart,
                modifier = Modifier.testTag("workflow-dialog-apply"),
            ) { Text(stringResource(R.string.workflow_start)) }
        },
        dismissButton = {
            TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}
