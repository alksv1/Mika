package com.wangzan101.mika.voice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.SheetTrailing
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.voice.SystemTtsDiscovery
import com.wangzan101.mika.voice.SystemTtsEngineInfo
import com.wangzan101.mika.voice.SystemTtsVoiceInfo
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import java.util.Locale

/**
 * 换系统朗读用的引擎:先把引擎写进目录,写成了才把音色清回默认(音色名只在一个引擎里有效);
 * 写失败时引擎和音色都保持原样。重选当前引擎不动音色。
 */
internal fun chooseSystemTtsEngine(tts: TtsSelectionController, settings: VoiceSettings, engine: String): Boolean {
    val changed = try {
        tts.setSystemEngine(engine)
    } catch (e: Exception) {
        // 目录读不出、写不进(VoiceCatalogStore.commit 的检查)或系统朗读没登记:什么都不改
        return false
    }
    if (changed) settings.setVoice("")
    return changed
}

/** 引擎面板里每项的文字:同名的引擎后面带上包名,分得清是哪个。 */
internal fun engineLabels(engines: List<SystemTtsEngineInfo>): Map<String, String> {
    val duplicated = engines.groupBy { it.label }.filterValues { it.size > 1 }.keys
    return engines.associate { it.packageName to if (it.label in duplicated) "${it.label} (${it.packageName})" else it.label }
}

/**
 * 内置系统朗读的「引擎」「音色」两行。引擎列手机上登记的语音合成引擎,音色列所选引擎已装好的音色,
 * 都在后台查,查不到时只给「默认」。行和面板只认公开接口给的名字,不对任何厂商的引擎特殊处理。
 * 给了 [onAudition] 时音色面板每项带试听按钮;播放归调用方,面板关掉时调 [onVoicesClosed] 让它停下。
 */
@Composable
internal fun SystemTtsRows(
    voiceModels: VoiceModelEnvironment,
    settings: VoiceSettings,
    onChanged: () -> Unit,
    onAudition: ((SystemTtsVoiceInfo) -> Unit)? = null,
    onVoicesClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by settings.state.collectAsState()
    val catalog by voiceModels.catalog.state.collectAsState()
    val engine = remember(catalog) { voiceModels.tts.systemEngine() }
    val engines by produceState(emptyList<SystemTtsEngineInfo>()) { value = SystemTtsDiscovery.engines(context) }
    val voices by produceState<List<SystemTtsVoiceInfo>?>(null, engine) {
        value = null
        val preferred = AppLanguage.resolved(context).speechTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        value = SystemTtsDiscovery.voices(context, engine, preferred)
    }
    var enginesOpen by remember { mutableStateOf(false) }
    var voicesOpen by remember { mutableStateOf(false) }
    val labels = engineLabels(engines)

    ListRow(
        null, tr(R.string.voice_tts_engine), first = true, onClick = { enginesOpen = true },
        modifier = Modifier.testTag("tts-engine"),
    ) {
        RowValue(if (engine.isBlank()) tr(R.string.voice_engine_default) else labels[engine] ?: engine)
        RowChevron()
    }
    if (enginesOpen) {
        val actions = listOf(SheetAction(tr(R.string.voice_engine_default)) {
            if (chooseSystemTtsEngine(voiceModels.tts, settings, "")) onChanged()
        }) + engines.map { info ->
            SheetAction(labels[info.packageName] ?: info.label) {
                if (chooseSystemTtsEngine(voiceModels.tts, settings, info.packageName)) onChanged()
            }
        }
        ActionSheet(tr(R.string.voice_tts_engine), actions) { enginesOpen = false }
    }

    val current = voices?.firstOrNull { it.name == state.voice }
    ListRow(null, tr(R.string.voice_voice), onClick = { voicesOpen = true }, modifier = Modifier.testTag("tts-voice")) {
        RowValue(if (state.voice.isBlank()) tr(R.string.voice_voice_default) else current?.displayName ?: state.voice)
        RowChevron()
    }
    if (voicesOpen) {
        val actions = listOf(SheetAction(tr(R.string.voice_voice_default), searchable = false) {
            settings.setVoice("")
            onChanged()
        }) + voices.orEmpty().map { voice ->
            SheetAction(
                voice.displayName,
                trailing = onAudition?.let { play -> SheetTrailing(MikaIcons.Sound, tr(R.string.voice_audition, voice.displayName)) { play(voice) } },
            ) {
                settings.setVoice(voice.name)
                onChanged()
            }
        }
        ActionSheet(tr(R.string.voice_voice), actions, searchHint = tr(R.string.search)) {
            voicesOpen = false
            onVoicesClosed()
        }
    }
}
