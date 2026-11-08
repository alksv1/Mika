package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.RecommendedTaskError
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置里的推荐任务:和首页是同一份列表,标题用来辨认,提示词留到点按后填进输入框。 */
@Composable
fun RecommendedTasksScreen(store: RecommendedTaskStore, onBack: () -> Unit) {
    remember(store) { store.ensureLoaded { ChatCopy.recommendationSeeds() } }
    val tasks by store.tasks.collectAsState()
    val unreadable by store.unreadable.collectAsState()
    var editing by remember { mutableStateOf<RecommendedTask?>(null) }
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PageScaffold(stringResource(R.string.recommendations_title), onBack) {
        if (unreadable) {
            FooterNote(stringResource(R.string.recommendations_unreadable), color = MikaTheme.colors.bad)
            if (resetError) FooterNote(stringResource(R.string.recommendations_save_failed), color = MikaTheme.colors.bad)
            MikaButton(
                stringResource(R.string.recommendations_reset),
                { resetting = true },
                modifier = Modifier.padding(top = 4.dp),
                kind = ButtonKind.Secondary,
                size = ButtonSize.Medium,
            )
        } else {
            GroupCard {
                if (tasks.isEmpty()) {
                    ListRow(
                        MikaIcons.Message,
                        stringResource(R.string.recommendations_empty),
                        stringResource(R.string.recommendations_empty_sub),
                        first = true,
                    )
                } else {
                    tasks.forEachIndexed { index, task ->
                        val preview = promptPreview(task.prompt)
                        ListRow(
                            MikaIcons.Message,
                            task.title,
                            preview.takeIf { it != task.title },
                            first = index == 0,
                            onClick = { editing = task },
                        ) { RowChevron() }
                    }
                }
                if (tasks.size < RecommendedTaskStore.MAX_TASKS) {
                    ListRow(MikaIcons.Plus, stringResource(R.string.recommendations_add), first = false, onClick = { adding = true })
                }
            }
            if (tasks.size >= RecommendedTaskStore.MAX_TASKS) {
                FooterNote(stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS))
            }
            FooterNote(stringResource(R.string.recommendations_footer))
        }
    }

    if (adding || editing != null) {
        RecommendedTaskDialog(
            store = store,
            initial = editing,
            onDismiss = { adding = false; editing = null },
        )
    }
    if (resetting) {
        MikaDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.recommendations_reset)) },
            text = { Text(stringResource(R.string.recommendations_reset_body)) },
            confirmButton = {
                TextButton({
                    resetting = false
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { store.discardUnreadable() }
                        resetError = error != null
                    }
                }, Modifier.testTag("recommendations-reset")) { Text(stringResource(R.string.recommendations_reset)) }
            },
            dismissButton = { TextButton({ resetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RecommendedTaskDialog(store: RecommendedTaskStore, initial: RecommendedTask?, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var error by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (initial == null) store.add(title, prompt) else store.update(initial.id, title, prompt)
            }
            saving = false
            if (result == null) onDismiss() else error = result
        }
    }

    MikaDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.recommendations_add else R.string.recommendations_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    title, { title = it; error = null },
                    label = { Text(stringResource(R.string.recommendations_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    prompt, { prompt = it; error = null },
                    label = { Text(stringResource(R.string.recommendations_prompt_label)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let {
                    Text(recommendationError(it), Modifier.padding(top = 8.dp), color = MikaTheme.colors.bad)
                }
                if (initial != null) {
                    TextButton({ confirmingDelete = true }, Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.recommendations_delete), color = MikaTheme.colors.bad)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ save() }, Modifier.testTag("recommendations-save"), enabled = !saving) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton({ if (!saving) onDismiss() }) { Text(stringResource(R.string.cancel)) } },
        properties = DialogProperties(dismissOnClickOutside = false),
    )

    if (confirmingDelete && initial != null) {
        MikaDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.recommendations_delete_title)) },
            text = { Text(stringResource(R.string.recommendations_delete_body)) },
            confirmButton = {
                TextButton({
                    confirmingDelete = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { store.delete(initial.id) }
                        if (result == null) onDismiss() else error = result
                    }
                }, Modifier.testTag("recommendations-delete")) {
                    Text(stringResource(R.string.recommendations_delete), color = MikaTheme.colors.bad)
                }
            },
            dismissButton = { TextButton({ confirmingDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun recommendationError(error: RecommendedTaskError): String = when (error) {
    RecommendedTaskError.BlankTitle -> stringResource(R.string.recommendations_title_required)
    RecommendedTaskError.BlankPrompt -> stringResource(R.string.recommendations_prompt_required)
    RecommendedTaskError.TooLong -> stringResource(R.string.recommendations_too_long)
    RecommendedTaskError.TooMany -> stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS)
    RecommendedTaskError.SaveFailed -> stringResource(R.string.recommendations_save_failed)
    RecommendedTaskError.Unreadable -> stringResource(R.string.recommendations_unreadable)
}

/** 设置列表里只露一行,避免一条长提示词把整页撑开。 */
private fun promptPreview(prompt: String): String {
    val flat = prompt.replace(Regex("\\s+"), " ").trim()
    val max = 60
    if (flat.codePointCount(0, flat.length) <= max) return flat
    var end = 0
    var taken = 0
    while (end < flat.length && taken < max) {
        end += Character.charCount(flat.codePointAt(end))
        taken++
    }
    return flat.substring(0, end) + "…"
}
