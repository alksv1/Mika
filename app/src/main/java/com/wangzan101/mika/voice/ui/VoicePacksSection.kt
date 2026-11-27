package com.wangzan101.mika.voice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportInput
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportResult
import com.wangzan101.mika.voice.ondevice.VoicePackStatus
import java.io.IOException
import java.util.Locale

/**
 * 设置 › 模型 › 语音转文字 › 离线语音包:每个包一张卡片(名称、语言、大小、许可、状态和操作)。
 * 从语音转文字页和「还不能语音输入」的「下载离线语音包」进来。设备一个包都跑不了时整页只剩说明(入口本来也不显示)。
 */
@Composable
fun VoicePacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { VoicePacksViewModel.forApp(context, scope) { copyToClipboard(context, it) } }
    Page(stringResource(R.string.voice_packs_title), onBack) {
        VoicePacksSection(viewModel)
    }
}

/** 「离线语音包」一节:说明、最新一条结果提示、每个包一张卡片,以及流量和删除的确认框。 */
@Composable
fun VoicePacksSection(viewModel: VoicePacksViewModel) {
    if (!viewModel.visible) return
    val context = LocalContext.current
    val cards by viewModel.cards.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val notice by viewModel.notice.collectAsState()
    var deleting by remember { mutableStateOf<VoicePackEntry?>(null) }
    // 选文件的系统页回来时要知道是给哪个包导入的
    var importFor by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val entry = cards.firstOrNull { it.entry.id == importFor }?.entry
        importFor = null
        if (entry != null && uris.isNotEmpty()) viewModel.import(entry, importInputs(context, uris))
    }
    // 回到前台时重看文件:可能在别处被删了、装上了
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    Column(Modifier.testTag("voice-packs")) {
        Text(
            stringResource(R.string.voice_packs_lead), Modifier.padding(top = 4.dp, bottom = 12.dp),
            style = MikaType.Body, color = MikaTheme.colors.ink2,
        )
        notice?.let { n ->
            val (text, bad) = noticeText(n)
            FooterNote(text, Modifier.testTag("voice-pack-notice"), color = if (bad) MikaTheme.colors.bad else MikaTheme.colors.ink2)
        }
        cards.forEach { card ->
            VoicePackCardView(
                card,
                onDownload = { viewModel.download(card.entry) },
                onCancel = { viewModel.cancel(card.entry) },
                onDelete = { deleting = card.entry },
                onImport = {
                    importFor = card.entry.id
                    picker.launch(arrayOf("*/*"))
                },
                onCopy = { viewModel.copyUrls(card.entry) },
            )
        }
    }

    (prompt as? VoicePackPrompt.Metered)?.let { p ->
        MikaDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text(stringResource(R.string.voice_pack_metered_title)) },
            text = { Text(stringResource(R.string.voice_pack_metered_body, size(context, p.bytes))) },
            dismissButton = { TextButton(onClick = viewModel::dismissPrompt) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMetered, modifier = Modifier.testTag("voice-pack-metered-confirm")) {
                    Text(stringResource(R.string.voice_pack_metered_confirm))
                }
            },
        )
    }

    deleting?.let { entry ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.voice_pack_delete_title)) },
            text = { Text(stringResource(R.string.voice_pack_delete_body, entry.displayName)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        viewModel.delete(entry)
                    },
                    modifier = Modifier.testTag("voice-pack-delete-confirm"),
                ) { Text(stringResource(R.string.voice_pack_delete), color = MikaTheme.colors.bad) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePackCardView(
    card: VoicePackCard,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val entry = card.entry
    val id = entry.id
    val progress = card.progress
    val downloading = progress != null && progress.phase != DownloadPhase.Failed
    val status = statusText(card, context)
    GroupCard(Modifier.testTag("voice-pack-$id")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.displayName, Modifier.weight(1f, fill = false), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                if (card.recommended) StatusPill(stringResource(R.string.voice_pack_recommended), PillTone.Accent, Modifier.testTag("voice-pack-recommended-$id"))
            }
            Text(
                stringResource(R.string.voice_pack_meta, languagesText(entry), size(context, entry.totalBytes), entry.license.name),
                Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3,
            )
            // 状态这一行读屏读得出来:下载中时连百分比一起读
            Text(
                status, Modifier.padding(top = 8.dp).testTag("voice-pack-status-$id"),
                style = MikaType.Hint, color = if (card.status == VoicePackStatus.Corrupt) c.warn else c.ink2,
            )
            if (downloading) {
                val fraction = progress!!.fraction()
                val name = entry.displayName
                val bar = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("voice-pack-progress-$id")
                    .semantics {
                        contentDescription = name
                        stateDescription = status
                    }
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar)
                else LinearProgressIndicator(modifier = bar)
            }
            if (progress?.phase == DownloadPhase.Failed) {
                Text(
                    stringResource(R.string.voice_pack_download_failed), Modifier.padding(top = 6.dp).testTag("voice-pack-failed-$id"),
                    style = MikaType.Hint, color = c.bad,
                )
            }
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val idle = !downloading && !card.importing
                when {
                    downloading -> PackButton(R.string.voice_pack_cancel, "voice-pack-cancel-$id", onCancel, ButtonKind.Tonal)
                    card.importing -> Unit
                    card.status == VoicePackStatus.Installed -> {
                        PackButton(R.string.voice_pack_delete, "voice-pack-delete-$id", onDelete, ButtonKind.Tonal)
                        // 大小对、内容坏了(用的时候报「语音包已损坏」)也还是「已安装」:同一版本重新下载覆盖,
                        // 不经过删除,选择和备援链照留(删除会把选择改回「自动」)
                        PackButton(R.string.voice_pack_redownload, "voice-pack-redownload-$id", onDownload, ButtonKind.Text)
                    }
                    card.status == VoicePackStatus.UpdateAvailable -> {
                        PackButton(R.string.voice_pack_update, "voice-pack-download-$id", onDownload)
                        PackButton(R.string.voice_pack_delete, "voice-pack-delete-$id", onDelete, ButtonKind.Tonal)
                    }
                    card.status == VoicePackStatus.Corrupt -> {
                        PackButton(R.string.voice_pack_redownload, "voice-pack-download-$id", onDownload)
                        PackButton(R.string.voice_pack_delete, "voice-pack-delete-$id", onDelete, ButtonKind.Tonal)
                    }
                    else -> PackButton(R.string.voice_pack_download, "voice-pack-download-$id", onDownload)
                }
                // 装好的包不用再导入、复制地址;别的状态都给离线用户留着这两条路
                if (idle && card.status != VoicePackStatus.Installed) {
                    PackButton(R.string.voice_pack_import, "voice-pack-import-$id", onImport, ButtonKind.Tonal)
                    PackButton(R.string.voice_pack_copy_urls, "voice-pack-copy-$id", onCopy, ButtonKind.Text)
                }
            }
        }
    }
}

@Composable
private fun PackButton(label: Int, tag: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.Primary) {
    MikaButton(stringResource(label), onClick, Modifier.testTag(tag), kind = kind, size = ButtonSize.Small)
}

@Composable
private fun statusText(card: VoicePackCard, context: Context): String {
    val p = card.progress
    return when {
        p != null && p.phase != DownloadPhase.Failed -> p.fraction()?.let {
            stringResource(R.string.voice_pack_status_downloading_percent, (it * 100).toInt())
        } ?: stringResource(R.string.voice_pack_status_downloading)
        card.importing -> stringResource(R.string.voice_pack_status_importing)
        else -> when (card.status) {
            VoicePackStatus.NotInstalled -> stringResource(R.string.voice_pack_status_not_installed)
            VoicePackStatus.Installed -> stringResource(R.string.voice_pack_status_installed, card.entry.version, size(context, card.entry.totalBytes))
            VoicePackStatus.Corrupt -> stringResource(R.string.voice_pack_status_corrupt)
            VoicePackStatus.UpdateAvailable -> stringResource(R.string.voice_pack_status_update)
        }
    }
}

/** 语言不多时写出名字(按界面语言),多了只写个数。 */
@Composable
private fun languagesText(entry: VoicePackEntry): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return if (entry.languages.size <= MAX_NAMED_LANGUAGES) {
        entry.languages.joinToString(", ") { code -> Locale.forLanguageTag(code).getDisplayLanguage(locale).ifBlank { code } }
    } else stringResource(R.string.voice_pack_languages_count, entry.languages.size)
}

private const val MAX_NAMED_LANGUAGES = 5

/** 第一个是要显示的话,第二个是不是要用户处理的问题(用红字)。 */
@Composable
private fun noticeText(n: VoicePackNotice): Pair<String, Boolean> {
    val context = LocalContext.current
    return when (n) {
        is VoicePackNotice.NoSpace -> stringResource(R.string.voice_pack_no_space, size(context, n.needed), size(context, n.free)) to true
        VoicePackNotice.Unsupported -> stringResource(R.string.voice_pack_unsupported) to true
        VoicePackNotice.NotStarted -> stringResource(R.string.voice_pack_not_started) to true
        VoicePackNotice.DeleteFailed -> stringResource(R.string.voice_pack_delete_failed) to true
        VoicePackNotice.Copied -> stringResource(R.string.voice_pack_copied) to false
        is VoicePackNotice.Imported -> when (val r = n.result) {
            ImportResult.Installed -> stringResource(R.string.voice_pack_import_done) to false
            is ImportResult.Missing -> stringResource(R.string.voice_pack_import_missing, r.files.joinToString(", ")) to true
            is ImportResult.Mismatch -> stringResource(R.string.voice_pack_import_mismatch, r.file) to true
            ImportResult.Busy -> stringResource(R.string.voice_pack_import_busy) to true
            is ImportResult.NoSpace -> stringResource(R.string.voice_pack_no_space, size(context, r.needed), size(context, r.free)) to true
            ImportResult.UnsupportedDevice -> stringResource(R.string.voice_pack_unsupported) to true
            is ImportResult.Failed -> stringResource(R.string.voice_pack_import_failed) to true
        }
    }
}

/** 页面每回到前台一次加一;拿它当 remember 的键,回来时重看文件。 */
@Composable
internal fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        count++
        onPauseOrDispose {}
    }
    return count
}

/**
 * 选择列表里本机语音包那一项的「需要下载」标注:模型和选择都在,文件却不在(从备份恢复、被删)。
 * [resumed] 变了(回到这一页)或目录变了才重看文件。
 */
@Composable
internal fun voicePackBadge(environment: VoiceModelEnvironment, snapshot: VoiceCatalogSnapshot, resumed: Int): (VoiceOption) -> String? {
    val label = stringResource(R.string.voice_pack_needs_download)
    val missing = remember(snapshot, resumed) {
        snapshot.models.filter { it.serviceId == OnDeviceCatalog.SERVICE_ID }
            .filter { environment.voicePackNeedsDownload(OnDeviceCatalog.ref(it.id)) }
            .mapTo(HashSet()) { it.id }
    }
    return { option -> label.takeIf { OnDeviceCatalog.isOnDevice(option.ref) && option.ref.modelId in missing } }
}

private fun DownloadProgress.fraction(): Float? =
    if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** 用户选的文件:显示名从系统取(拿不到用地址的最后一段),每次打开都从头读。 */
private fun importInputs(context: Context, uris: List<Uri>): List<ImportInput> = uris.map { uri ->
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    ImportInput(name) { context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri") }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.voice_pack_copy_urls), text))
}
