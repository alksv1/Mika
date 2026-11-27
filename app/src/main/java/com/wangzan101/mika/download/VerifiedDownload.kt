package com.wangzan101.mika.download

import com.wangzan101.mika.about.DownloadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 任务里的一个文件:按 [sources] 的顺序下到 [target],每个来源下完都先过 [verify](大小、摘要等),
 * 不通过就当这个来源失败。[size] 是事先知道的字节数(清单里写着),用来算整个任务的进度;不知道为 -1。
 */
data class DownloadFile(
    val target: File,
    val sources: List<String>,
    val verify: (File) -> Unit,
    val size: Long = -1,
)

/** 一个任务的进度。[totalBytes] 不知道时为 -1;[error] 是 [DownloadPhase.Failed] 时最后一次的失败原因。 */
data class DownloadProgress(
    val phase: DownloadPhase,
    val doneBytes: Long,
    val totalBytes: Long,
    val error: Throwable? = null,
)

/**
 * 通用的带校验下载器,App 更新(`about/`)和离线语音包(`voice/`)共用,所以单独成一个顶层包。
 *
 * - 一个任务按顺序下一组文件;每个文件各自按来源顺序尝试,不要求整组从同一个来源下完。
 * - 每个文件先写到 `<目标>.part`,下完整才改名成目标,**然后**才过校验;开始时目标已在且校验通过的文件直接跳过。
 * - 目标文件以最终名字存在**不等于**校验过:校验进行中、校验没过还没来得及删、上次被打断时都可能是这样。
 *   调用方(比如语音包安装)只能在 [start] 的 `onComplete` 里、或自己重新校验之后使用这些文件。
 * - 某个文件所有来源都失败(或 `onComplete` 失败),整个任务失败,这个任务的目标文件一个都不留,
 *   包括开始时因为已经校验通过而跳过的那些。取消只清掉正在下的那个文件,已校验的留着,重试时跳过。
 * - 任务按 key 区分:同一个 key 同时只有一个任务,不同 key 互不取消。
 * - 进程被杀后没下完的算失败,用户重试即可。下载只在调用方(用户点按)要求时开始。
 */
class VerifiedDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Run(val files: List<DownloadFile>) {
        lateinit var job: Job
        @Volatile var call: Call? = null
        @Volatile var current: File? = null
        /** 任务失败的原因;为空且任务没正常结束就是被取消。 */
        @Volatile var failure: Throwable? = null
        /** 每个文件的已下字节和总字节;总字节先取 [DownloadFile.size],下载中拿到响应长度、下完拿到实际长度时更新。 */
        val done = LongArray(files.size)
        val sizes = LongArray(files.size) { files[it].size }
    }

    private val runs = mutableMapOf<String, Run>()
    /** 每个 key 最近的一个任务;同 key 的新任务等它彻底结束(连接断开、半截文件清掉)才开始。 */
    private val last = mutableMapOf<String, Job>()
    private val flows = mutableMapOf<String, MutableStateFlow<DownloadProgress>>()

    /**
     * 开始下载 [files];[key] 已经有任务在跑时什么都不做,返回 false。
     * [client] 为空时用构造时给的 [http](走应用代理);全部通过后先跑 [onComplete](比如把文件挪到安装位置),
     * 它也成功才算 [DownloadPhase.Ready],它抛错算失败。
     *
     * [prepare] 在任务里、下第一个文件之前跑(比如在后台线程上建空的目标目录),抛错算失败。
     * [onEnd] 在任务彻底结束后调一次,不论成功、失败、取消,也不论 scope 在任务开跑前就已取消;
     * 它先于进度变成最终状态,调用方在里面清理自己的东西(放弃 staging、放开锁)。它不该抛错,抛了也只吞掉。
     */
    @Synchronized
    fun start(
        key: String,
        files: List<DownloadFile>,
        client: OkHttpClient? = null,
        prepare: suspend () -> Unit = {},
        onEnd: () -> Unit = {},
        onComplete: suspend () -> Unit = {},
    ): Boolean {
        require(files.isNotEmpty()) { "nothing to download" }
        require(files.all { it.sources.isNotEmpty() }) { "no download source" }
        if (key in runs) return false
        val run = Run(files)
        val previous = last[key]
        runs[key] = run
        flow(key).value = progress(run, DownloadPhase.Downloading)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                prepare()
                download(key, run, client ?: http)
                onComplete()
            } catch (e: Throwable) {
                // 真正的取消(任务本身已不在跑)按取消处理;别的一律算失败,包括 Error,
                // 以及任务还在跑时 verify/onComplete 里冒出来的 CancellationException(比如超时)。
                if (e is CancellationException && !isActive) throw e
                run.failure = e
                files.forEach { it.target.delete() }
                // 内存耗尽这类虚拟机错误清理后照样抛出,不当成普通失败吞掉;其余失败只记在进度里。
                if (e is VirtualMachineError) throw e
            }
        }
        run.job = job
        last[key] = job
        // 收尾放在完成回调里:scope 在任务开跑前就被取消时任务体一行都不会执行,也要把 key 放出来。
        job.invokeOnCompletion { cause ->
            try {
                runCatching(onEnd)
            } finally {
                finish(key, run, cause)
            }
        }
        job.start()
        return true
    }

    @Synchronized
    private fun finish(key: String, run: Run, cause: Throwable?) {
        if (last[key] === run.job) last.remove(key)
        // 被 [cancel] 停掉时已经摘掉、发过 Idle 了。
        if (runs[key] !== run) return
        runs.remove(key)
        val failure = run.failure
        flow(key).value = when {
            failure != null -> progress(run, DownloadPhase.Failed, failure)
            cause == null -> progress(run, DownloadPhase.Ready)
            else -> DownloadProgress(DownloadPhase.Idle, 0, -1)
        }
    }

    @Synchronized
    fun progress(key: String): StateFlow<DownloadProgress> = flow(key).asStateFlow()

    /**
     * 等 [key] 最近的任务彻底结束(包括 [cancel] 之后连接断开、半截文件清掉、`onComplete` 跑完);没有就立刻返回。
     * 调用方要在任务结束后清理自己的东西(比如语音包的 staging)时用它,不能只看进度:取消时进度立刻回到 Idle,任务可能还没停。
     */
    suspend fun join(key: String) {
        val job = synchronized(this) { last[key] }
        job?.join()
    }

    /** 停掉 [key] 的任务:连带掐断连接,不等读完这一块;正在下的半截文件删掉,进度回到 [DownloadPhase.Idle]。 */
    @Synchronized
    fun cancel(key: String) {
        val run = runs.remove(key) ?: return
        run.job.cancel()
        run.call?.cancel()
        run.call = null
        run.current?.let { File(it.path + PART).delete() }
        flow(key).value = DownloadProgress(DownloadPhase.Idle, 0, -1)
    }

    private suspend fun download(key: String, run: Run, client: OkHttpClient) {
        for ((index, file) in run.files.withIndex()) {
            coroutineContext.ensureActive()
            if (alreadyVerified(file)) {
                update(key, run) { run.sizes[index] = file.target.length(); run.done[index] = run.sizes[index] }
                continue
            }
            run.current = file.target
            var failure: Exception? = null
            for (url in file.sources) {
                coroutineContext.ensureActive()
                // 换来源时这个文件从头算。
                update(key, run) { run.done[index] = 0; run.sizes[index] = file.size }
                try {
                    fetch(client, url, file.target, onCall = { next ->
                        synchronized(this) {
                            if (runs[key] === run && run.job.isActive) run.call = next else next.cancel()
                        }
                    }) { done, size ->
                        update(key, run) {
                            run.done[index] = done
                            if (file.size < 0) run.sizes[index] = size
                        }
                    }
                    coroutineContext.ensureActive()
                    file.verify(file.target)
                    coroutineContext.ensureActive()
                    failure = null
                    break
                } catch (e: CancellationException) {
                    file.target.delete()
                    throw e
                } catch (e: Exception) {
                    // 取消导致的连接异常不能触发下一个来源。
                    file.target.delete()
                    coroutineContext.ensureActive()
                    failure = e
                }
            }
            failure?.let { throw it }
            update(key, run) { run.sizes[index] = file.target.length(); run.done[index] = run.sizes[index] }
        }
        run.current = null
    }

    private fun alreadyVerified(file: DownloadFile): Boolean {
        if (!file.target.isFile) return false
        return try {
            file.verify(file.target)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            file.target.delete()
            false
        }
    }

    /** 只改还在跑的这个任务的进度;被取消或换掉后迟到的回调不覆盖新状态。 */
    private inline fun update(key: String, run: Run, change: () -> Unit) {
        synchronized(this) {
            if (runs[key] !== run) return
            change()
            flow(key).value = progress(run, DownloadPhase.Downloading)
        }
    }

    private fun progress(run: Run, phase: DownloadPhase, error: Throwable? = null) = DownloadProgress(
        phase,
        run.done.sum(),
        if (run.sizes.any { it < 0 }) -1 else run.sizes.sum(),
        error,
    )

    private fun flow(key: String) = flows.getOrPut(key) { MutableStateFlow(DownloadProgress(DownloadPhase.Idle, 0, -1)) }

    companion object {
        private const val PART = ".part"

        /** 把 [url] 下到 [file]:边下边报进度(已下、总共,不知道总共为 -1);不是 2xx、或没下完整就抛错,不留目标文件。 */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            val part = File(file.path + PART)
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                coroutineContext.ensureActive()
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val size = body.contentLength()
                    var done = 0L
                    progress(0, size)
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, size)
                            }
                        }
                    }
                    if (size >= 0 && done != size) throw IOException("incomplete download")
                }
                coroutineContext.ensureActive()
                if (!part.renameTo(file)) throw IOException("cannot move download")
            } finally {
                part.delete()
            }
        }
    }
}
