package com.wangzan101.mika.export

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ui.AttachmentMessage
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.export.ExportBlockId
import com.wangzan101.mika.core.export.ExportContent
import com.wangzan101.mika.core.export.ExportIds
import com.wangzan101.mika.core.export.ExportNames
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportRedactor
import com.wangzan101.mika.core.export.ExportSelection
import com.wangzan101.mika.core.export.RedactionLevel
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.ui.components.ActivitySizedDialog
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EXPORT_FORMATS = listOf("markdown", "json", "html")
private val MonoPreviewStyle = MikaType.Hint.copy(fontFamily = FontFamily.Monospace)

private data class ExportCandidate(val id: ExportBlockId, val title: String, val preview: String)

private data class ExportSnapshot(
    val source: ConversationExportSource,
    val candidates: List<ExportCandidate>,
    val fileAssets: Boolean,
    val screenAssets: Boolean,
)

private data class ExportRequest(
    val source: ConversationExportSource,
    val selection: ExportSelection,
    val content: ExportContent,
    val format: String,
    val bundle: Boolean,
)

/** 导出与预览都消费这里创建的不可变来源快照；离开页面会取消任务并释放预览暂存。 */
@Composable
internal fun ExportConversationDialog(
    conversationId: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    val runtime = remember(context) { ExportRuntime(context) }
    var exportPreview by remember(conversationId) { mutableStateOf<ExportSnapshot?>(null) }
    var pending by remember(conversationId) { mutableStateOf<ExportRequest?>(null) }
    var selected by remember { mutableStateOf<Set<ExportBlockId>>(emptySet()) }
    var format by remember { mutableStateOf("markdown") }
    var toolSteps by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(true) }
    var screens by remember { mutableStateOf(true) }
    var timestamps by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(false) }
    var pii by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var outcome by remember { mutableStateOf<ExportOutcome?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewResult by remember { mutableStateOf<ExportPreview?>(null) }

    LaunchedEffect(conversationId) {
        val records = withContext(Dispatchers.IO) { ConversationStore.shared(context).records(conversationId) }
        var files = false
        var screens = false
        records.forEach { (turn, shared) ->
            if (shared.isNotEmpty() || AttachmentLines.parse(turn.userText).files.isNotEmpty()) files = true
            if (UserScreenMessage.has(turn.userText)) screens = true
            turn.steers.forEach { steer ->
                if (AttachmentLines.parse(steer).files.isNotEmpty()) files = true
                if (UserScreenMessage.has(steer)) screens = true
            }
        }
        val list = buildList {
            records.forEachIndexed { index, (turn, _) ->
                add(ExportCandidate(ExportIds.user(index), context.getString(R.string.export_label_user), preview(turn.userText)))
                turn.steers.forEachIndexed { i, steer ->
                    add(ExportCandidate(ExportIds.steer(index, i), context.getString(R.string.export_label_steer), preview(steer)))
                }
                add(ExportCandidate(ExportIds.answer(index), context.getString(R.string.export_label_mika), preview(turn.outcome)))
            }
        }
        exportPreview = ExportSnapshot(ConversationExportSource(context, conversationId, records), list, files, screens)
        selected = list.map { it.id }.toSet()
    }

    val all = exportPreview?.candidates.orEmpty()
    val bundle = exportPreview?.let { (attachments && it.fileAssets) || (screens && it.screenAssets) } == true

    fun request(): ExportRequest? {
        val source = exportPreview?.source ?: return null
        return ExportRequest(
            source = source,
            selection = ExportSelection(conversationId, selected, allSelected = selected.size == all.size),
            content = ExportContent(
                includeToolSteps = toolSteps,
                includeAttachments = attachments,
                includeScreens = screens,
                includeTimestamps = timestamps,
                includeModel = model,
                redaction = if (pii) RedactionLevel.SecretsAndPii else RedactionLevel.Secrets,
            ),
            format = format,
            bundle = bundle,
        )
    }

    fun start(uri: Uri?) {
        val request = pending
        pending = null
        if (uri == null || request == null) return
        running = true
        failure = null
        outcome = null
        progress = null
        job = scope.launch {
            try {
                val result = runtime.export(
                    source = request.source,
                    title = title,
                    selection = request.selection,
                    content = request.content,
                    presentation = ExportPresentation(),
                    formatId = request.format,
                    destination = uri,
                    bundle = request.bundle,
                ) { p -> handler.post { progress = p } }
                outcome = result
            } catch (e: CancellationException) {
                // 用户取消；runtime 只清理这次暂存。
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
                job = null
            }
        }
    }

    fun startPreview() {
        if (running || previewing || pending != null) return
        val request = request() ?: return
        (previewResult as? ExportPreview.Html)?.release()
        previewResult = null
        previewing = true
        failure = null
        progress = null
        previewJob = scope.launch {
            try {
                previewResult = runtime.preview(
                    source = request.source,
                    title = title,
                    selection = request.selection,
                    content = request.content,
                    presentation = ExportPresentation(),
                    formatId = request.format,
                ) { p -> handler.post { progress = p } }
            } catch (e: CancellationException) {
                // 取消或离开面板时，runtime 清理尚未交付的暂存目录。
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                previewing = false
                previewJob = null
            }
        }
    }

    fun dismiss() {
        if (running) return
        previewJob?.cancel()
        (previewResult as? ExportPreview.Html)?.release()
        previewResult = null
        onDismiss()
    }

    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::start)
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::start)
    val html = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html"), ::start)
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::start)
    val suggested = suggestedName(title, bundle, format, context.getString(R.string.export_filename_fallback), pii)
    val c = MikaTheme.colors
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxBodyHeight = minOf(screenHeight * 0.56f, screenHeight - 320.dp).coerceIn(160.dp, 500.dp)
    val canExport = selected.isNotEmpty() && all.isNotEmpty() && pending == null
    val finished = outcome != null || failure != null

    fun launchExport() {
        pending = request()
        when {
            pending == null -> Unit
            bundle -> zip.launch(suggested)
            format == "json" -> json.launch(suggested)
            format == "html" -> html.launch(suggested)
            else -> md.launch(suggested)
        }
    }

    MikaDialog(
        onDismissRequest = ::dismiss,
        modifier = Modifier.safeDrawingPadding(),
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            when {
                running -> {
                    Row(
                        Modifier.fillMaxWidth().background(c.fill, MikaShapes.Card).padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.hair)
                        Text(progressText(progress), style = MikaType.Body, color = c.ink)
                    }
                }
                outcome != null -> {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth().background(c.okSoft, MikaShapes.Card).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(MikaIcons.Check, null, Modifier.size(18.dp), tint = c.ok)
                            Text(stringResource(R.string.export_done), style = MikaType.BodyStrong, color = c.ok)
                        }
                        outcome?.takeIf { it.warnings.isNotEmpty() }?.let {
                            Row(
                                Modifier.fillMaxWidth().background(c.warnSoft, MikaShapes.Chip).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(MikaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.warn)
                                Text(stringResource(R.string.export_warnings, it.warnings.size), style = MikaType.Caption, color = c.warn)
                            }
                        }
                    }
                }
                failure != null -> {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth().background(c.badSoft, MikaShapes.Card).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(MikaIcons.Exclaim, null, Modifier.padding(top = 2.dp).size(18.dp), tint = c.bad)
                            Text(stringResource(R.string.export_failed, failure.orEmpty()), style = MikaType.Body, color = c.bad)
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxBodyHeight).testTag("export-config-list")) {
                        item(key = "format") {
                            Text(stringResource(R.string.export_format), style = MikaType.Label, color = c.ink3)
                            Spacer(Modifier.height(6.dp))
                            SegmentedControl(
                                options = listOf(
                                    stringResource(R.string.export_format_markdown),
                                    stringResource(R.string.export_format_json),
                                    stringResource(R.string.export_format_html),
                                ),
                                selected = EXPORT_FORMATS.indexOf(format).coerceAtLeast(0),
                                onSelect = { format = EXPORT_FORMATS[it] },
                                tags = listOf("export-format-markdown", "export-format-json", "export-format-html"),
                            )
                        }
                        item(key = "options") {
                            Spacer(Modifier.height(14.dp))
                            Column(Modifier.fillMaxWidth().clip(MikaShapes.Card).background(c.fill)) {
                                ListRow(null, stringResource(R.string.export_include_tool_steps), first = true, onClick = { toolSteps = !toolSteps }) { MikaSwitch(toolSteps, { toolSteps = it }) }
                                ListRow(null, stringResource(R.string.export_include_attachments), first = false, onClick = { attachments = !attachments }) { MikaSwitch(attachments, { attachments = it }) }
                                ListRow(null, stringResource(R.string.export_include_screens), first = false, onClick = { screens = !screens }) { MikaSwitch(screens, { screens = it }) }
                                ListRow(null, stringResource(R.string.export_include_timestamps), first = false, onClick = { timestamps = !timestamps }) { MikaSwitch(timestamps, { timestamps = it }) }
                                ListRow(null, stringResource(R.string.export_include_model), first = false, onClick = { model = !model }) { MikaSwitch(model, { model = it }) }
                                ListRow(null, stringResource(R.string.export_redact_pii), first = false, onClick = { pii = !pii }) { MikaSwitch(pii, { pii = it }) }
                            }
                            if (bundle) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().background(c.accentSoft, MikaShapes.Chip).padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(MikaIcons.Info, null, Modifier.size(16.dp), tint = c.accent)
                                    Text(stringResource(R.string.export_bundle_note), style = MikaType.Hint, color = c.ink2)
                                }
                            }
                        }
                        item(key = "messages-header") {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.export_messages), style = MikaType.Label, color = c.ink3)
                                    if (all.isNotEmpty()) {
                                        Text(stringResource(R.string.export_selected_count, selected.size, all.size), style = MikaType.Hint, color = c.ink3)
                                    }
                                }
                                if (all.isNotEmpty()) {
                                    Box(
                                        Modifier.clip(MikaShapes.Chip)
                                            .clickable(role = Role.Button) {
                                                selected = if (selected.size == all.size) emptySet() else all.map { it.id }.toSet()
                                            }
                                            .heightIn(min = 36.dp)
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                            .testTag("export-select-all"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            stringResource(R.string.export_select_all),
                                            style = MikaType.Caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = c.accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        when {
                            exportPreview == null -> item(key = "loading") {
                                Row(
                                    Modifier.fillMaxWidth().background(c.fill, MikaShapes.Card).padding(horizontal = 12.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.hair)
                                    Text(stringResource(R.string.export_preparing), style = MikaType.Caption, color = c.ink2)
                                }
                            }
                            all.isEmpty() -> item(key = "empty") {
                                Box(
                                    Modifier.fillMaxWidth().background(c.fill, MikaShapes.Card).padding(horizontal = 14.dp, vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stringResource(R.string.export_no_messages), style = MikaType.Body, color = c.ink3)
                                }
                            }
                            else -> itemsIndexed(all, key = { _, item -> item.id.value }) { index, item ->
                                ExportMessageRow(
                                    item = item,
                                    selected = item.id in selected,
                                    first = index == 0,
                                    last = index == all.lastIndex,
                                    onToggle = { selected = toggle(selected, item.id) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (finished) {
                TextButton(onClick = ::dismiss) { Text(stringResource(R.string.export_close)) }
            } else if (running) {
                TextButton(onClick = { job?.cancel() }) {
                    Text(stringResource(R.string.export_cancel))
                }
            }
        },
        actions = when {
            finished || running -> null
            previewing -> {
                {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().background(c.fill, MikaShapes.Card).padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.hair)
                            Text(progressText(progress), style = MikaType.Body, color = c.ink)
                        }
                        MikaButton(
                            text = stringResource(R.string.export_cancel),
                            onClick = { previewJob?.cancel() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("export-preview-cancel"),
                            kind = ButtonKind.Tonal,
                            size = ButtonSize.Medium,
                            maxLines = 2,
                        )
                    }
                }
            }
            else -> {
                {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MikaButton(
                                text = stringResource(R.string.export_preview),
                                onClick = ::startPreview,
                                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp).testTag("export-preview"),
                                kind = ButtonKind.Tonal,
                                size = ButtonSize.Medium,
                                enabled = canExport,
                                maxLines = 2,
                            )
                            MikaButton(
                                text = stringResource(R.string.export_button),
                                onClick = ::launchExport,
                                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp).testTag("export-confirm"),
                                kind = ButtonKind.Primary,
                                size = ButtonSize.Medium,
                                enabled = canExport,
                                maxLines = 2,
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(MikaShapes.Chip)
                                .clickable(role = Role.Button, onClick = ::dismiss)
                                .heightIn(min = 40.dp)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("export-cancel"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.export_cancel),
                                style = MikaType.Body,
                                color = c.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            dismissOnBackPress = !running,
            dismissOnClickOutside = !running,
        ),
    )

    previewResult?.let { result ->
        ExportPreviewDialog(result) { previewResult = null }
    }
    DisposableEffect(previewResult) {
        val current = previewResult
        onDispose {
            // 必须释放 effect 创建时的值；重组后的新预览不能替旧值清理。
            (current as? ExportPreview.Html)?.release()
        }
    }
}

@Composable
internal fun ExportPreviewDialog(preview: ExportPreview, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val formatLabel = when (preview) {
        is ExportPreview.Html -> stringResource(R.string.export_format_html)
        is ExportPreview.Text -> when (preview.formatId) {
            "json" -> stringResource(R.string.export_format_json)
            else -> stringResource(R.string.export_format_markdown)
        }
    }
    ActivitySizedDialog(onDismiss = onDismiss, dismissOnClickOutside = false) { safeArea ->
        Column(Modifier.fillMaxSize().background(c.bg).then(safeArea)) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = MikaSize.TopBar)
                    .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.export_preview),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MikaType.Title,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CapabilityTag(formatLabel)
                }
                MikaButton(
                    text = stringResource(R.string.export_close),
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("export-preview-close"),
                    kind = ButtonKind.Tonal,
                    size = ButtonSize.Small,
                    maxLines = 2,
                )
            }
            Hairline(start = 0.dp)
            val hasWarnings = preview.warnings.isNotEmpty()
            val isTruncated = preview is ExportPreview.Text && preview.truncated
            if (hasWarnings) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (isTruncated) 0.dp else 4.dp)
                        .background(c.warnSoft, MikaShapes.Chip)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(MikaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.warn)
                    Text(
                        stringResource(R.string.export_warnings, preview.warnings.size),
                        style = MikaType.Caption,
                        color = c.warn,
                    )
                }
            }
            if (isTruncated) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                        .background(c.fill, MikaShapes.Chip)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(MikaIcons.Info, null, Modifier.size(16.dp), tint = c.ink2)
                    Text(
                        stringResource(R.string.export_preview_truncated),
                        style = MikaType.Caption,
                        color = c.ink2,
                    )
                }
            }
            when (preview) {
                is ExportPreview.Text -> SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    val chunks = remember(preview.text) {
                        preview.text.lineSequence().chunked(200).map { it.joinToString("\n") }.toList()
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        items(chunks) { chunk ->
                            Text(chunk, style = MonoPreviewStyle, color = c.ink)
                        }
                    }
                }
                is ExportPreview.Html -> HtmlPreview(preview, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ExportMessageRow(
    item: ExportCandidate,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
) {
    val c = MikaTheme.colors
    val shape = when {
        first && last -> MikaShapes.Card
        first -> RoundedCornerShape(topStart = MikaRadius.Card, topEnd = MikaRadius.Card)
        last -> RoundedCornerShape(bottomStart = MikaRadius.Card, bottomEnd = MikaRadius.Card)
        else -> RectangleShape
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(c.fill)) {
        if (!first) Hairline(start = 42.dp)
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExportCheckMark(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MikaType.Label,
                    color = if (selected) c.ink else c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.preview.isNotBlank()) {
                    Text(
                        item.preview,
                        style = MikaType.Caption,
                        color = if (selected) c.ink2 else c.ink3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportCheckMark(selected: Boolean) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(20.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(c.accent)
                else Modifier.border(1.5.dp, c.ink3, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
    }
}

private fun toggle(selected: Set<ExportBlockId>, id: ExportBlockId): Set<ExportBlockId> =
    if (id in selected) selected - id else selected + id

@Composable
private fun progressText(progress: ExportProgress?): String = when (progress?.phase) {
    ExportPhase.Preparing -> stringResource(R.string.export_preparing)
    ExportPhase.Rendering -> stringResource(R.string.export_rendering)
    ExportPhase.Packaging -> stringResource(R.string.export_packaging)
    ExportPhase.Delivering -> stringResource(R.string.export_delivering)
    null -> stringResource(R.string.export_preparing)
}

private fun preview(text: String): String = ScreenCopy.plain(AttachmentMessage.plain(text)).replace('\n', ' ').trim().take(120)

private fun suggestedName(title: String, bundle: Boolean, format: String, fallback: String, pii: Boolean): String {
    val level = if (pii) RedactionLevel.SecretsAndPii else RedactionLevel.Secrets
    val safeTitle = ExportRedactor.apply(title, level)
    val base = ExportNames.safeFileName(safeTitle.ifBlank { fallback }, fallback)
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val ext = if (bundle) "zip" else when (format) {
        "json" -> "json"
        "html" -> "html"
        else -> "md"
    }
    return ExportNames.fit("$base-$stamp.$ext")
}
