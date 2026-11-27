package com.wangzan101.mika.voice.ondevice

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 加载好的本机识别模型都放这里:每个包(id + 版本)只加载一次,之后常驻;
 * 闲置 [idleMs] 或系统内存紧张([release])时释放,下次用到再加载。
 *
 * 加载在 [scope] 里跑,不跟着调用方:按下说话时 [prewarm] 开始加载,调用方取消或等超时,加载照样做完留着用。
 * 释放时正在识别的那次不会被拆掉原生对象,而是等它结束再关;那次照常拿到文字,之后再用这个句柄才报 [OnDeviceSttException]。
 * 识别本身也在 [scope] 里跑(见 [Held.transcribe]):原生解码取消不了,调用方的超时要能立刻返回。
 */
class OnDeviceEngines(
    private val backends: OnDeviceBackends,
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val options: EngineOptions = EngineOptions.default(),
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 一个包的加载:[ready] 完成后就是可用的模型。 */
    private inner class Slot(val version: Int) {
        val ready = CompletableDeferred<Held>()
        @Volatile var held: Held? = null
        /** 有人等这次加载等到了超时;之后再来的不再干等,直接报超时,直到它真加载完。 */
        @Volatile var timedOut = false
    }

    private val lock = Any()
    private val slots = HashMap<String, Slot>()

    /** 提前在后台加载(按下说话时调),不等结果;出错留到 [engine] 时再报。 */
    fun prewarm(pack: InstalledVoicePack) {
        if (usable(pack)) slot(pack)
    }

    /** 这个包加载好的模型;还没加载就加载,最多等 [OnDeviceTimeout.LOAD_TIMEOUT_MS]。 */
    suspend fun engine(pack: InstalledVoicePack): OnDeviceSttEngine {
        if (!usable(pack)) throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "no backend for ${pack.entry.backend}/${pack.entry.family} on this device")
        val slot = slot(pack)
        val timeout = { OnDeviceSttException(OnDeviceSttException.Kind.Timeout, "loading ${pack.entry.id} took over ${OnDeviceTimeout.LOAD_TIMEOUT_MS} ms") }
        // 卡住的加载还没做完:不再每次干等一整个上限;它晚些做完了照常用
        if (slot.timedOut && !slot.ready.isCompleted) throw timeout()
        val held = withTimeoutOrNull(OnDeviceTimeout.LOAD_TIMEOUT_MS) { slot.ready.await() }
            ?: run {
                slot.timedOut = true
                throw timeout()
            }
        held.touch()
        return held
    }

    /** 释放所有模型(系统内存紧张时调)。 */
    fun release() {
        val all = synchronized(lock) { slots.values.toList().also { slots.clear() } }
        all.forEach { retire(it, OnDeviceSttException.Kind.OutOfMemory) }
    }

    /** 释放一个包(删除或换版本时调)。 */
    fun release(packId: String) {
        synchronized(lock) { slots.remove(packId) }?.let { retire(it, OnDeviceSttException.Kind.Missing) }
    }

    private fun usable(pack: InstalledVoicePack): Boolean =
        backends.find(pack.entry.backend)?.let { it.supports(pack.entry.family) && it.abiSupported() } == true

    /** 当前版本的加载(在做或做完);没有就开始一个。旧版本先释放,不和新版本同时占内存。 */
    private fun slot(pack: InstalledVoicePack): Slot {
        var stale: Slot? = null
        var created = false
        val slot = synchronized(lock) {
            slots[pack.entry.id]?.takeIf { it.version == pack.entry.version }
                ?: Slot(pack.entry.version).also { stale = slots.put(pack.entry.id, it); created = true }
        }
        stale?.let { retire(it, OnDeviceSttException.Kind.Missing) }
        if (created) start(pack, slot)
        return slot
    }

    private fun start(pack: InstalledVoicePack, slot: Slot) {
        val backend = backends.find(pack.entry.backend)!!
        scope.launch(loadDispatcher) {
            val outcome = try {
                Result.success(backend.load(pack, options))
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: OnDeviceSttException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.OutOfMemory, "out of memory loading ${pack.entry.id}", e))
            } catch (e: UnsatisfiedLinkError) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "native library unavailable", e))
            } catch (e: Throwable) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Corrupt, "failed to load ${pack.entry.id}: ${e.message}", e))
            }
            outcome.fold(
                onSuccess = { engine ->
                    val held = Held(pack.entry.id, slot, engine)
                    slot.held = held
                    // 加载途中被释放了:直接关掉,不留着
                    if (slot.ready.complete(held)) held.touch() else engine.close()
                },
                onFailure = { error ->
                    // 失败的加载不留:下次用到重新试
                    synchronized(lock) { if (slots[pack.entry.id] === slot) slots.remove(pack.entry.id) }
                    slot.ready.completeExceptionally(error)
                },
            )
        }
    }

    /** 还在加载的,等它加载完就关;已经加载好的,等正在进行的识别结束再关。 */
    private fun retire(slot: Slot, kind: OnDeviceSttException.Kind) {
        if (slot.ready.completeExceptionally(OnDeviceSttException(kind, "voice pack released"))) return
        slot.held?.retire(kind)
    }

    /** 闲置到时:没在识别就释放;正在识别的那次结束后会重新计时。 */
    private fun idle(held: Held) {
        synchronized(lock) {
            if (slots[held.packId] === held.slot && held.retireIfIdle()) slots.remove(held.packId)
        }
    }

    /** 交给调用方的句柄:记着正在进行的识别,释放时等它们结束再关原生模型。 */
    private inner class Held(val packId: String, val slot: Slot, private val inner: OnDeviceSttEngine) : OnDeviceSttEngine {
        private var inFlight = 0
        /** 调用方已经不等、但原生解码还没结束的次数。 */
        private var abandoned = 0
        private var retired: OnDeviceSttException.Kind? = null
        private var idleJob: Job? = null

        /**
         * 原生解码会一直占着线程(JNI 调用,取消不了),所以放在 [scope] 里单独跑,调用方只是等它:
         * 调用方超时或取消就立刻返回,解码在后台跑完。[inFlight] 按解码真正结束算,
         * 释放或闲置都等它结束才关原生模型。被丢下的解码还没结束时,新的识别直接报超时(换下一个),
         * 不再排队等同一把锁,也不会一次次占住更多线程。
         */
        override suspend fun transcribe(pcm: ShortArray, language: String?): String {
            synchronized(this) {
                retired?.let { throw released(it) }
                if (abandoned > 0) {
                    throw OnDeviceSttException(OnDeviceSttException.Kind.Timeout, "an earlier recognition with $packId is still running")
                }
                inFlight++
                idleJob?.cancel()
            }
            val call = Call()
            val work = scope.async { decode(pcm, language) }
            work.invokeOnCompletion { finished(call) }
            val text = try {
                work.await()
            } catch (e: CancellationException) {
                synchronized(this) {
                    if (!work.isCompleted) {
                        call.gaveUp = true
                        abandoned++
                    }
                }
                throw e
            }.getOrThrow()
            // 解码途中被释放(删除、换版本、内存紧张)也照常交出文字:原生对象等这次解码结束才关,
            // 后端把解码和关闭排在一起,文字是有效的;只有之后再来的识别才报已释放
            return text
        }

        /** 一次识别:调用方有没有丢下它;只在 synchronized(Held) 里读写。 */
        private inner class Call {
            var gaveUp = false
        }

        /** 真正的解码;错误都装进结果里,不让它从 [scope] 里往外抛。 */
        private suspend fun decode(pcm: ShortArray, language: String?): Result<String> = try {
            Result.success(inner.transcribe(pcm, language))
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: OnDeviceSttException) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.OutOfMemory, "out of memory transcribing with $packId", e))
        } catch (e: Throwable) {
            // 模型已经加载成功,解码出意外不是文件损坏:报 Failed,界面只说「请重试」,不让用户重新下载
            Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Failed, "on-device model $packId failed to transcribe: ${e.message}", e))
        }

        /** 一次解码真正结束(不管调用方还等不等):已释放且没有别的在跑就关原生模型,否则重新计闲置。 */
        private fun finished(call: Call) {
            val closeNow = synchronized(this) {
                inFlight--
                if (call.gaveUp) abandoned--
                if (retired == null) {
                    scheduleIdle()
                    false
                } else {
                    inFlight == 0
                }
            }
            if (closeNow) runCatching { inner.close() }
        }

        fun touch() = synchronized(this) { if (retired == null && inFlight == 0) scheduleIdle() }

        /** 没有正在进行的识别时释放并关掉,返回是否释放了;检查和释放是一步,不会撞上刚开始的识别。 */
        fun retireIfIdle(): Boolean {
            synchronized(this) {
                if (retired != null || inFlight > 0) return false
                retired = OnDeviceSttException.Kind.OutOfMemory
                idleJob?.cancel()
            }
            // 在 scope 里闲置到时调用,原生 close 出错不能往外抛
            runCatching { inner.close() }
            return true
        }

        fun retire(kind: OnDeviceSttException.Kind) {
            val closeNow = synchronized(this) {
                if (retired != null) return
                retired = kind
                idleJob?.cancel()
                inFlight == 0
            }
            // 可能在主线程上(onTrimMemory)调用,原生 close 出错不能往外抛
            if (closeNow) runCatching { inner.close() }
        }

        /**
         * 不做事:这是共用的模型,调用方 `engine(pack).use {}` 不该把它扔掉。
         * 释放只经 [OnDeviceEngines.release]、`release(packId)` 和闲置计时。
         */
        override fun close() = Unit

        private fun scheduleIdle() {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(idleMs)
                idle(this@Held)
            }
        }

        private fun released(kind: OnDeviceSttException.Kind) = OnDeviceSttException(kind, "on-device model $packId was released")
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L

        /** App 里共用的一份;`MikaApp.onTrimMemory` 内存紧张时调 [release]。 */
        val shared: OnDeviceEngines by lazy {
            OnDeviceEngines(OnDeviceBackends(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
