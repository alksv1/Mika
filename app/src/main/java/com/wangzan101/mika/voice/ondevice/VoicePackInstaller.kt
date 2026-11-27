package com.wangzan101.mika.voice.ondevice

import android.content.Context
import android.net.ConnectivityManager
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.about.ApkIntegrity
import com.wangzan101.mika.about.MirrorSettings
import com.wangzan101.mika.about.UpdateMirrors
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.download.DownloadFile
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.download.DownloadUrls
import com.wangzan101.mika.download.VerifiedDownload
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * 语音包的下载、手动导入和删除,设置页的语音包卡片调它(本身没有界面)。
 *
 * - **同一个包同时只有一个操作**:下载和导入互斥,第二个请求不排队,`download` 返回 false、`import` 返回 [ImportResult.Busy];
 *   [busy] 可以给界面判断。删除不算第二个请求:它先取消正在跑的下载,等任务彻底结束(或等正在进行的导入做完),再删。
 * - 下载和导入都经 [VoicePackStore] 建 staging、全部通过后 [VoicePackStore.commit] 一次生效,再把模型写进语音目录
 *   ([VoiceCatalogStore.installVoicePack])。失败、取消都 [VoicePackStore.abandon],staging 不留;提交失败时旧版本照用、目录不变。
 *   `commit` 在新版本改名生效后删旧版本时也可能抛错,这时重新看一眼 [VoicePackStore.installed]:装上了就算成功
 *   (残留的旧版本由下次启动的 `cleanup` 清掉)。
 * - 下载只在用户点按时开始([download] 不会被别处自动调用);走应用代理,不绑定 Wi-Fi。开始前界面先调 [preflight]。
 * - 哈希和文件读写都在 [io] 上。
 */
class VoicePackInstaller(
    private val catalog: VoicePackCatalog,
    private val store: VoicePackStore,
    private val download: VerifiedDownload,
    private val mirrors: () -> MirrorSettings,
    /** 当前网络是否按流量计费;产品里是 `ConnectivityManager.isActiveNetworkMetered()`。 */
    private val metered: () -> Boolean,
    private val voiceCatalog: VoiceCatalogStore,
    private val engines: OnDeviceEngines,
    private val backends: OnDeviceBackends = OnDeviceBackends(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 下载前检查的结果;顺序是设备不支持 → 空间不够 → 按流量计费要确认。 */
    sealed interface Preflight {
        data object Ready : Preflight
        /** 按流量计费的网络:先告诉用户要下 [bytes] 字节,确认后再 [download]。 */
        data class ConfirmMetered(val bytes: Long) : Preflight
        data class NoSpace(val needed: Long, val free: Long) : Preflight
        data object UnsupportedDevice : Preflight
    }

    /** 导入的一个输入:用户选的一个文件(单个模型文件,或整包 zip)。[name] 是显示名,[open] 每次调都从头读。 */
    data class ImportInput(val name: String, val open: () -> InputStream)

    sealed interface ImportResult {
        data object Installed : ImportResult
        /** 选的文件里没有清单里的这些文件。 */
        data class Missing(val files: List<String>) : ImportResult
        /** 这个文件大小或 SHA-256 和清单对不上。 */
        data class Mismatch(val file: String) : ImportResult
        /** 这个包正在下载或导入。 */
        data object Busy : ImportResult
        data class NoSpace(val needed: Long, val free: Long) : ImportResult
        data object UnsupportedDevice : ImportResult
        /** 读不出来(文件打不开、zip 坏了)或写不进去。 */
        data class Failed(val error: Throwable) : ImportResult
    }

    /** 每个包一把锁:下载从开始到任务彻底结束、导入从头到尾、删除时都拿着它。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lock(entry: VoicePackEntry) = locks.getOrPut(entry.id) { Mutex() }

    /** 这个包有下载或导入在进行。 */
    fun busy(entry: VoicePackEntry): Boolean = lock(entry).isLocked

    /**
     * 一个文件的下载来源,按顺序:用户的镜像(开着时,用户排的顺序)+ Release 地址、官方 Release 地址、上游固定提交地址。
     * 每个都过 [DownloadUrls.requireTrusted];不合格的(比如用户填了个不是 HTTPS 的镜像)直接不用。
     */
    fun sources(entry: VoicePackEntry, file: VoicePackFile): List<String> {
        val settings = mirrors()
        val release = catalog.releaseUrl(entry, file)
        val upstream = catalog.upstreamUrl(entry, file)
        val candidates = UpdateMirrors.candidates(release, settings).map { it to release } + (upstream to upstream)
        return candidates.filter { (url, official) ->
            try {
                DownloadUrls.requireTrusted(url, official, settings.mirrors, null)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }.map { it.first }.distinct()
    }

    /** 「复制下载地址」:每个文件的官方 Release 地址,一行一个。 */
    fun downloadUrls(entry: VoicePackEntry): String = entry.files.joinToString("\n") { catalog.releaseUrl(entry, it) }

    fun preflight(entry: VoicePackEntry): Preflight {
        if (!supported(entry)) return Preflight.UnsupportedDevice
        space(entry)?.let { (needed, free) -> return Preflight.NoSpace(needed, free) }
        if (metered()) return Preflight.ConfirmMetered(entry.totalBytes)
        return Preflight.Ready
    }

    /**
     * 开始下载(用户点「下载」,[preflight] 之后)。这个包已经有下载或导入在进行时什么都不做,返回 false;
     * 一个可用的来源都没有时也返回 false。进度看 [progress]。
     *
     * 在调用线程上只拿这个包的锁、算地址(第一次会读镜像设置),不碰 staging:建 staging(会删上次的残留)在下载任务里、
     * 后台线程上做。锁一直拿到任务彻底结束;放弃 staging、放开锁挂在任务自己的结束上([VerifiedDownload.start] 的 onEnd),
     * 不靠另起的协程,所以 scope 被取消也不会漏掉。
     */
    fun download(entry: VoicePackEntry): Boolean {
        val lock = lock(entry)
        if (!lock.tryLock()) return false
        var started = false
        try {
            val sources = entry.files.map { sources(entry, it) }
            if (sources.any { it.isEmpty() }) return false
            val staging = store.stagingPath(entry)
            val files = entry.files.mapIndexed { i, f ->
                DownloadFile(File(staging, f.name), sources[i], verify = { verify(it, f) }, size = f.size)
            }
            started = download.start(
                key(entry), files,
                prepare = {
                    // 空的新目录,登记为正在用;只有锁住的这一个操作会碰它
                    check(store.stagingDir(entry) == staging) { "unexpected staging dir" }
                },
                onEnd = {
                    // 成功时 staging 已改名走,这一步什么都不做;失败、取消时清掉 staging、撤销登记。
                    try {
                        store.abandon(entry)
                    } finally {
                        lock.unlock()
                    }
                },
            ) { install(entry, staging) }
        } finally {
            // 没开始(没来源、key 被占、start 抛错):staging 还没建,只放开锁
            if (!started) lock.unlock()
        }
        return started
    }

    fun progress(entry: VoicePackEntry): StateFlow<DownloadProgress> = download.progress(key(entry))

    /** 取消下载;staging 在任务彻底停下后清掉,之后 [busy] 变回 false。 */
    fun cancel(entry: VoicePackEntry) = download.cancel(key(entry))

    /**
     * 从用户选的文件导入:[inputs] 可以是几个单独的文件,也可以是 zip(条目可以带一层 `<包id>/` 目录),混着也行。
     * 文件名按清单名对,Release 附件名 `<包id>--<文件名>` 也认;清单里没有的文件不管。
     * zip 条目只认「文件名」或「`<包id>/`文件名」两种,`..`、绝对路径、别的子目录一律跳过,写入的只会是 staging 里的清单文件名。
     * 每个文件最多写到清单大小,再多就停下报 [ImportResult.Mismatch](防 zip 炸弹)。全部大小和 SHA-256 对上才提交。
     */
    suspend fun import(entry: VoicePackEntry, inputs: List<ImportInput>): ImportResult {
        val lock = lock(entry)
        if (!lock.tryLock()) return ImportResult.Busy
        try {
            if (!supported(entry)) return ImportResult.UnsupportedDevice
            return withContext(io) {
                space(entry)?.let { (needed, free) -> return@withContext ImportResult.NoSpace(needed, free) }
                try {
                    val staging = store.stagingDir(entry)
                    importInto(entry, staging, inputs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 打不开(文件没了、没权限)、zip 坏了、条目名编码不对、写不进去
                    ImportResult.Failed(e)
                } finally {
                    // 成功时 staging 已改名走,这里什么都不做
                    runCatching { store.abandon(entry) }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 删掉这个包:有下载先取消并等它彻底停下,正在导入的等它做完;再释放加载的模型、删文件、从语音目录去掉
     * (选中的是它时语音输入回到「自动」)。删文件出错照样从目录去掉,错误再往外抛。
     */
    suspend fun delete(entry: VoicePackEntry) {
        download.cancel(key(entry))
        val lock = lock(entry)
        lock.lock()
        try {
            download.join(key(entry))
            engines.release(entry.id)
            withContext(io) {
                try {
                    store.abandon(entry)
                    store.delete(entry.id)
                } finally {
                    voiceCatalog.removeVoicePack(entry.id)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun importInto(entry: VoicePackEntry, staging: File, inputs: List<ImportInput>): ImportResult {
        val manifest = entry.files.associateBy { it.name }
        val written = HashSet<String>()
        val mismatched = HashSet<String>()

        /** 把一个流写成清单里的 [file];已经有一份对上的就跳过。 */
        fun accept(file: VoicePackFile, input: InputStream) {
            if (file.name in written) return
            val target = File(staging, file.name)
            if (copyVerified(input, target, file)) {
                written += file.name
                mismatched -= file.name
            } else {
                target.delete()
                mismatched += file.name
            }
        }

        for (input in inputs) {
            coroutineContext.ensureActive()
            val direct = packFileName(input.name, entry.id, allowDir = false)?.let(manifest::get)
            if (direct != null) {
                input.open().use { accept(direct, it) }
                continue
            }
            if (!isZip(input)) continue
            ZipInputStream(input.open().buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val item = zip.nextEntry ?: break
                    if (item.isDirectory) continue
                    val file = packFileName(item.name, entry.id, allowDir = true)?.let(manifest::get) ?: continue
                    accept(file, zip)
                }
            }
        }
        entry.files.firstOrNull { it.name in mismatched }?.let { return ImportResult.Mismatch(it.name) }
        val missing = entry.files.map { it.name }.filter { it !in written }
        if (missing.isNotEmpty()) return ImportResult.Missing(missing)
        install(entry, staging)
        return ImportResult.Installed
    }

    /**
     * 提交 staging 并写进语音目录,再释放这个包已加载的模型(换了版本时旧模型不留在内存里,下次用到加载新的)。
     * 提交抛错时,staging 已经不在(改名生效了)且新版本装全,说明是之后删旧版本出错,算成功;
     * 否则往外抛,旧版本和目录不动。同一版本重新下载时改名失败,旧的那份会被挪回原处,`installed` 也不为空,
     * 所以只看 `installed` 不够,要看 staging 还在不在。
     */
    private fun install(entry: VoicePackEntry, staging: File) {
        try {
            store.commit(entry, staging)
        } catch (e: IOException) {
            if (staging.exists() || store.installed(entry) == null) throw e
        }
        voiceCatalog.installVoicePack(entry)
        engines.release(entry.id)
    }

    /** 流的内容写进 [target],最多写清单大小加一个字节就停;大小和 SHA-256 都对上返回 true。 */
    private fun copyVerified(input: InputStream, target: File, file: VoicePackFile): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val room = (file.size - total + 1).coerceAtMost(buf.size.toLong()).toInt()
                val n = input.read(buf, 0, room)
                if (n < 0) break
                total += n
                if (total > file.size) return false
                out.write(buf, 0, n)
                sha.update(buf, 0, n)
            }
        }
        return total == file.size && sha.digest().joinToString("") { "%02x".format(it) }.equals(file.sha256, ignoreCase = true)
    }

    private fun isZip(input: ImportInput): Boolean = input.open().use { s ->
        val head = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = s.read(head, read, 4 - read)
            if (n < 0) break
            read += n
        }
        read == 4 && head.contentEquals(ZIP_MAGIC)
    }

    private fun verify(file: File, manifest: VoicePackFile) = ApkIntegrity.verify(file, manifest.size, "sha256:" + manifest.sha256)

    private fun supported(entry: VoicePackEntry): Boolean =
        backends.find(entry.backend)?.let { it.supports(entry.family) && it.abiSupported() } == true

    /** 空间不够时返回(需要, 可用)。 */
    private fun space(entry: VoicePackEntry): Pair<Long, Long>? {
        val needed = entry.totalBytes + VoicePackStore.SPACE_MARGIN
        val free = store.freeBytes()
        return if (free < needed) needed to free else null
    }

    companion object {
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

        fun key(entry: VoicePackEntry) = "voice-pack:${entry.id}"

        /**
         * 用户给的名字对应清单里的哪个文件名:去掉一层 `<包id>/` 目录([allowDir],zip 条目才有)或 Release 附件的
         * `<包id>--` 前缀。别的路径(`..`、绝对路径、更深的目录、反斜杠)一律返回 null。
         */
        internal fun packFileName(name: String, id: String, allowDir: Boolean): String? {
            val parts = name.split('/')
            val base = when {
                parts.size == 1 -> parts[0]
                allowDir && parts.size == 2 && parts[0] == id -> parts[1]
                else -> return null
            }
            val plain = base.removePrefix("$id--")
            return plain.takeIf { VoicePackCatalog.isSafeName(it) }
        }

        /**
         * App 启动时、[VoicePackStore.cleanup] 之后调(后台线程):文件装着(哪个版本都行)、语音目录里却没有模型的包补登记。
         * 提交把新版本改名生效后、写进语音目录前进程被杀,就会是这样;不补的话包装着却选不了。已登记的不动、不写。
         */
        fun restoreInstalled(catalog: VoicePackCatalog, store: VoicePackStore, voiceCatalog: VoiceCatalogStore) {
            voiceCatalog.restoreVoicePacks(catalog.packs.filter { store.installedAny(it) != null })
        }

        @Volatile
        private var shared: VoicePackInstaller? = null

        /** App 里共用的一份(和 [VoiceModelEnvironment] 共用同一个 [VoicePackStore]、清单和语音目录)。 */
        fun get(context: Context): VoicePackInstaller = shared ?: synchronized(this) {
            shared ?: create(context.applicationContext).also { shared = it }
        }

        private fun create(context: Context): VoicePackInstaller {
            val env = VoiceModelEnvironment.get(context)
            val app = context as MikaApp
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return VoicePackInstaller(
                catalog = env.voicePackCatalog!!(),
                store = env.voicePacks!!,
                // 走应用代理(含 SOCKS),不绑定 Wi-Fi:下载只在用户点按时开始
                download = VerifiedDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS))),
                mirrors = app.updateMirrors::snapshot,
                // 拿不到网络信息时按计费处理,宁可多问一次
                metered = { connectivity?.isActiveNetworkMetered ?: true },
                voiceCatalog = env.catalog,
                engines = OnDeviceEngines.shared,
            )
        }
    }
}
