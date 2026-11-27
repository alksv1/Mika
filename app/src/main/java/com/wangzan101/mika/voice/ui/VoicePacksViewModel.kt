package com.wangzan101.mika.voice.ui

import android.content.Context
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ondevice.OnDeviceLanguage
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportInput
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportResult
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.Preflight
import com.wangzan101.mika.voice.ondevice.VoicePackStatus
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 语音包卡片的一张。[progress] 只在下载中、或上次下载失败(还没装上)时有;[importing] 是正在从文件导入。
 */
data class VoicePackCard(
    val entry: VoicePackEntry,
    val status: VoicePackStatus,
    val progress: DownloadProgress?,
    val recommended: Boolean,
    val importing: Boolean = false,
)

/** 下载前要用户确认的事。 */
sealed interface VoicePackPrompt {
    /** 按流量计费的网络:写明大小,用户确认再下。 */
    data class Metered(val entry: VoicePackEntry, val bytes: Long) : VoicePackPrompt
}

/** 卡片上方的一句结果提示(最新的一条)。 */
sealed interface VoicePackNotice {
    data class NoSpace(val needed: Long, val free: Long) : VoicePackNotice
    data object Unsupported : VoicePackNotice
    /** 已经有下载或导入在进行,或一个可用的来源都没有。 */
    data object NotStarted : VoicePackNotice
    data class Imported(val result: ImportResult) : VoicePackNotice
    data object DeleteFailed : VoicePackNotice
    data object Copied : VoicePackNotice
}

/** 语音包卡片要用到的安装操作;App 里是 [VoicePackInstaller] 加 [VoicePackStore],测试换成假的。 */
interface VoicePackOps {
    /** 清单里的包(当前版本认得的引擎和模型族)。 */
    val packs: List<VoicePackEntry>
    /** 这台设备能不能跑这个包(引擎认这个模型族、处理器架构支持)。 */
    fun runnable(entry: VoicePackEntry): Boolean
    fun status(entry: VoicePackEntry): VoicePackStatus
    fun preflight(entry: VoicePackEntry): Preflight
    fun download(entry: VoicePackEntry): Boolean
    fun progress(entry: VoicePackEntry): StateFlow<DownloadProgress>
    fun cancel(entry: VoicePackEntry)
    suspend fun delete(entry: VoicePackEntry)
    suspend fun import(entry: VoicePackEntry, inputs: List<ImportInput>): ImportResult
    fun downloadUrls(entry: VoicePackEntry): String
}

/**
 * 设置里「离线语音包」一节的状态和动作。[recommended] 是推荐给当前界面语言的包(见 [OnDeviceLanguage.recommended]),
 * 排在最前。设备一个包都跑不了时 [visible] 为 false,整节不显示。
 * 下载只在用户点按时开始:[download] 先过 [VoicePackOps.preflight],空间不够只提示,按流量计费先问([prompt])。
 * 卡片的状态看文件(不是推送来的):下载进度一变、导入删除做完、[refresh](页面回到前台)时重算,算在 [io] 上。
 */
class VoicePacksViewModel(
    private val ops: VoicePackOps,
    private val recommended: VoicePackEntry?,
    private val scope: CoroutineScope,
    private val copy: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val packs = ops.packs.filter(ops::runnable)
        .sortedBy { if (it.id == recommended?.id) 0 else 1 }

    val visible: Boolean get() = packs.isNotEmpty()

    private val tick = MutableStateFlow(0)
    private val importing = MutableStateFlow(emptySet<String>())

    private val _prompt = MutableStateFlow<VoicePackPrompt?>(null)
    val prompt: StateFlow<VoicePackPrompt?> = _prompt.asStateFlow()

    private val _notice = MutableStateFlow<VoicePackNotice?>(null)
    val notice: StateFlow<VoicePackNotice?> = _notice.asStateFlow()

    val cards: StateFlow<List<VoicePackCard>> =
        if (packs.isEmpty()) MutableStateFlow(emptyList())
        else combine(combine(packs.map(ops::progress)) { it.toList() }, tick, importing) { progress, _, busy -> progress to busy }
            .map { (progress, busy) -> build(progress, busy) }
            .flowOn(io)
            .stateIn(scope, SharingStarted.Eagerly, build(packs.map { ops.progress(it).value }, emptySet()))

    private fun build(progress: List<DownloadProgress>, busy: Set<String>): List<VoicePackCard> =
        packs.mapIndexed { i, entry ->
            val status = ops.status(entry)
            val p = progress[i]
            val shown = when (p.phase) {
                DownloadPhase.Downloading, DownloadPhase.Paused -> p
                // 下载失败:装上之前一直提示,可以重试
                DownloadPhase.Failed -> p.takeIf { status != VoicePackStatus.Installed }
                DownloadPhase.Idle, DownloadPhase.Ready -> null
            }
            VoicePackCard(entry, status, shown, entry.id == recommended?.id, importing = entry.id in busy)
        }

    /** 页面回到前台时调:文件可能在别处被删了、装上了。 */
    fun refresh() = tick.update { it + 1 }

    fun download(entry: VoicePackEntry) {
        _notice.value = null
        when (val p = ops.preflight(entry)) {
            Preflight.Ready -> start(entry)
            is Preflight.ConfirmMetered -> _prompt.value = VoicePackPrompt.Metered(entry, p.bytes)
            is Preflight.NoSpace -> _notice.value = VoicePackNotice.NoSpace(p.needed, p.free)
            Preflight.UnsupportedDevice -> _notice.value = VoicePackNotice.Unsupported
        }
    }

    fun confirmMetered() {
        val p = _prompt.value as? VoicePackPrompt.Metered ?: return
        _prompt.value = null
        start(p.entry)
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun start(entry: VoicePackEntry) {
        if (!ops.download(entry)) _notice.value = VoicePackNotice.NotStarted
    }

    fun cancel(entry: VoicePackEntry) {
        ops.cancel(entry)
        refresh()
    }

    fun delete(entry: VoicePackEntry) {
        _notice.value = null
        scope.launch {
            try {
                ops.delete(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = VoicePackNotice.DeleteFailed
            }
            refresh()
        }
    }

    fun import(entry: VoicePackEntry, inputs: List<ImportInput>) {
        _notice.value = null
        importing.update { it + entry.id }
        scope.launch {
            val result = try {
                ops.import(entry, inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.Failed(e)
            } finally {
                importing.update { it - entry.id }
            }
            _notice.value = VoicePackNotice.Imported(result)
            refresh()
        }
    }

    fun copyUrls(entry: VoicePackEntry) {
        copy(ops.downloadUrls(entry))
        _notice.value = VoicePackNotice.Copied
    }

    companion object {
        /** App 里的一份:清单、存放和安装器都是进程里共用的那一份;推荐按当前界面语言。 */
        fun forApp(context: Context, scope: CoroutineScope, copy: (String) -> Unit): VoicePacksViewModel {
            val ops = AppVoicePackOps.from(context)
            val recommended = ops.catalog?.let { OnDeviceLanguage.recommended(it, AppLanguage.current(context)) }
            return VoicePacksViewModel(ops, recommended, scope, copy)
        }
    }
}

/** [VoicePackOps] 的 App 实现。清单读不出来时一个包都没有(整节不显示)。 */
internal class AppVoicePackOps(
    val catalog: VoicePackCatalog?,
    private val store: VoicePackStore?,
    private val environment: VoiceModelEnvironment,
    private val installer: () -> VoicePackInstaller,
) : VoicePackOps {
    override val packs: List<VoicePackEntry> = catalog?.packs.orEmpty()
    override fun runnable(entry: VoicePackEntry) = environment.runsVoicePack(entry)
    override fun status(entry: VoicePackEntry) = store?.status(entry) ?: VoicePackStatus.NotInstalled
    override fun preflight(entry: VoicePackEntry) = installer().preflight(entry)
    override fun download(entry: VoicePackEntry) = installer().download(entry)
    override fun progress(entry: VoicePackEntry) = installer().progress(entry)
    override fun cancel(entry: VoicePackEntry) = installer().cancel(entry)
    override suspend fun delete(entry: VoicePackEntry) = installer().delete(entry)
    override suspend fun import(entry: VoicePackEntry, inputs: List<ImportInput>) = installer().import(entry, inputs)
    override fun downloadUrls(entry: VoicePackEntry) = installer().downloadUrls(entry)

    companion object {
        fun from(context: Context): AppVoicePackOps {
            val env = VoiceModelEnvironment.get(context)
            return AppVoicePackOps(env.voicePackCatalogOrNull(), env.voicePacks, env) { VoicePackInstaller.get(context) }
        }
    }
}

/** 清单;没有语音包环境(测试)或清单读不了时为 null。 */
internal fun VoiceModelEnvironment.voicePackCatalogOrNull(): VoicePackCatalog? = try {
    voicePackCatalog?.invoke()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** 这台设备能不能跑 [entry]:有它的引擎、引擎认这个模型族、处理器架构支持。 */
internal fun VoiceModelEnvironment.runsVoicePack(entry: VoicePackEntry): Boolean =
    voicePackBackends?.find(entry.backend)?.let { it.supports(entry.family) && it.abiSupported() } == true

/** 这台设备上有能跑的语音包:「离线语音包」一节、选择页的入口和「下载离线语音包」提示都按它显示。 */
internal fun VoiceModelEnvironment.hasRunnableVoicePacks(): Boolean =
    voicePackCatalogOrNull()?.packs?.any(::runsVoicePack) == true

/**
 * 选择列表里这一项是本机语音包、文件却不在(从备份恢复、被删):标「需要下载」,选择照留,下好后不用重新选。
 * 只看文件在不在(旧版本也算,照样能用),不算校验和。
 */
internal fun VoiceModelEnvironment.voicePackNeedsDownload(ref: VoiceModelRef): Boolean {
    if (!OnDeviceCatalog.isOnDevice(ref)) return false
    val store = voicePacks ?: return false
    val entry = voicePackCatalogOrNull()?.entry(ref.modelId) ?: return true
    return store.installedAny(entry) == null
}
