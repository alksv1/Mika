package com.wangzan101.mika.voice.ondevice

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 语音包里的一个文件。[url] 是这个文件的上游绝对地址(如许可证原文),没有时上游地址是包的 [VoicePackEntry.upstream] 加文件名。 */
@Serializable
data class VoicePackFile(
    val name: String,
    val role: String,
    val size: Long,
    val sha256: String,
    val url: String? = null,
)

@Serializable
data class VoicePackLicense(val name: String, val file: String, val attribution: String)

/** 一个可下载的离线识别语音包。[backend] + [family] 决定由哪个识别引擎加载它。 */
@Serializable
data class VoicePackEntry(
    val id: String,
    val version: Int,
    val backend: String,
    val family: String,
    val displayName: String,
    val languages: List<String>,
    val recommendedFor: List<String>,
    val files: List<VoicePackFile>,
    val license: VoicePackLicense,
    val upstream: String,
) {
    /** 所有文件加起来的字节数(下载量、占用空间)。 */
    val totalBytes: Long get() = files.sumOf { it.size }
}

/**
 * 随 APK 自带的语音包清单(`assets/voice-models/catalog.json`)。只留下当前版本认得的引擎和模型族,
 * 新版清单里多出来的包和字段都不影响解析。
 */
class VoicePackCatalog(val release: String, val packs: List<VoicePackEntry>) {

    fun entry(id: String): VoicePackEntry? = packs.firstOrNull { it.id == id }

    /** 发布页上的地址:Release 里的文件按「包 id--文件名」平铺存放。下载优先走这里。 */
    fun releaseUrl(entry: VoicePackEntry, file: VoicePackFile): String = release + entry.id + "--" + file.name

    /** 上游原始地址:文件自带绝对地址时用它,否则是包的上游目录加文件名。Release 取不到时退回这里。 */
    fun upstreamUrl(entry: VoicePackEntry, file: VoicePackFile): String = file.url ?: (entry.upstream + file.name)

    @Serializable
    private data class Manifest(val schema: Int, val release: String, val packs: List<VoicePackEntry>)

    companion object {
        const val SCHEMA = 1
        const val ASSET = "voice-models/catalog.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析清单;[supported] 判断某个引擎/模型族当前能不能跑,不能跑的包不列出来。
         * 包 id 或某个文件名不是安全的单段名字([isSafeName])时,这个包也不列出来:id 和文件名会直接拼成
         * 安装目录和下载、导入写入的路径;只藏这一个包,别的包照常能用。
         * 清单随 APK 一起发,结构版本对不上是打包出错,直接抛 [IllegalStateException]。
         */
        fun parse(json: String, supported: (backend: String, family: String) -> Boolean): VoicePackCatalog {
            val m = this.json.decodeFromString<Manifest>(json)
            check(m.schema == SCHEMA) { "voice-pack catalog schema ${m.schema}, expected $SCHEMA" }
            return VoicePackCatalog(m.release, m.packs.filter { p ->
                supported(p.backend, p.family) && isSafeName(p.id) && p.files.all { isSafeName(it.name) }
            })
        }

        /** 能直接当一段路径用的名字:非空、不是 `.` 或 `..`、不含 `/` 和 `\`。 */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

        fun load(context: Context, supported: (backend: String, family: String) -> Boolean): VoicePackCatalog =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }, supported)
    }
}
