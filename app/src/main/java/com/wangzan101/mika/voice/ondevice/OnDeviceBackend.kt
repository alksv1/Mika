package com.wangzan101.mika.voice.ondevice

import java.io.File

/** 一个本机推理后端,比如 sherpa-onnx。按清单里包的 [VoicePackEntry.backend] 找到它。 */
interface OnDeviceSttBackend {
    /** 和清单的 `backend` 字段对应,如 `sherpa-onnx`。 */
    val id: String

    /** 这个后端认不认这种模型([VoicePackEntry.family])。 */
    fun supports(family: String): Boolean

    /** 这台设备能不能跑(处理器架构)。 */
    fun abiSupported(): Boolean

    /** 加载一个已安装、已校验的包;失败抛 [OnDeviceSttException]。可能很慢,在后台线程调。 */
    fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine
}

/** 加载好的一个模型,常驻内存,直到 [close]。 */
interface OnDeviceSttEngine : AutoCloseable {
    /** 16 kHz 单声道 PCM 进、文字出。[language] 是 ISO 639 语言部分,null 是自动检测。 */
    suspend fun transcribe(pcm: ShortArray, language: String?): String
}

data class EngineOptions(val threads: Int) {
    companion object {
        /** 线程数取 CPU 核数和 4 中较小的那个。 */
        fun default() = EngineOptions(minOf(Runtime.getRuntime().availableProcessors(), 4))
    }
}

/** 清单里一个包,加上安装位置;文件按 role 取路径,不按文件名。 */
data class InstalledVoicePack(val entry: VoicePackEntry, val dir: File) {
    /** [role] 对应的文件;清单里这个包没有这个 role 时抛 [OnDeviceSttException.Kind.Missing]。 */
    fun file(role: String): File {
        val f = entry.files.firstOrNull { it.role == role }
            ?: throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${entry.id} has no '$role' file")
        return File(dir, f.name)
    }
}

/** 本机识别的失败,按 [kind] 分类,接入语音目录时再换成 `VoiceModelError`。 */
class OnDeviceSttException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * [Corrupt] 只用于加载失败(文件读不出、模型建不起来),界面会让用户重新下载;
     * [Failed] 是模型已经加载好、这一次解码出了意外,重新下载没用,只让用户重试。
     */
    enum class Kind { Missing, Corrupt, OutOfMemory, Timeout, Unsupported, Failed }
}

/** 后端注册表:构造时传入列表,测试换成假的。 */
class OnDeviceBackends(private val backends: List<OnDeviceSttBackend> = listOf(SherpaOnnxBackend())) {
    fun find(id: String): OnDeviceSttBackend? = backends.firstOrNull { it.id == id }

    /** 有这个后端、而且它认这种模型;清单解析时用它筛掉当前版本跑不了的包。 */
    fun supports(backend: String, family: String): Boolean = find(backend)?.supports(family) == true
}
