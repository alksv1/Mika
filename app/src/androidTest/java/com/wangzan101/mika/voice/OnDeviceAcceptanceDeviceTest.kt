package com.wangzan101.mika.voice

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.voice.ondevice.OnDeviceBackends
import com.wangzan101.mika.voice.ondevice.OnDeviceEngines
import com.wangzan101.mika.voice.ondevice.OnDeviceLanguage
import com.wangzan101.mika.voice.ondevice.SherpaOnnxBackend
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机验收测量:对每个已安装的语音包记下加载用时、5 秒语音的识别用时、内存,以及逐句识别结果(打分在电脑上做)。
 * 不断言准确率,只出数据;没有测试音频时跳过,平时跑整套仪器测试不受影响。
 *
 * 音频放在 App 的缓存目录 `cache/stt-eval/`(调试包可用 `adb shell run-as <包名>` 从 `/data/local/tmp` 拷进去;
 * shell 推进外部文件目录的文件 App 读不到):
 * - `utt/<界面语言>/<语言>/<序号>.wav`:逐句音频,`<语言>` 是识别语言标签的语言部分(如 `zh`);
 * - `5s/<语言>.wav`:约 5 秒的一段,测速用。
 * 结果写到同一目录的 `results-<包 id>.json`,并在 logcat(标签 `OnDeviceAcceptance`)打出摘要。
 *
 * 参数(`am instrument -e`):`packs` 逗号分隔的包 id(默认全部已安装的);`holdMs` 测完后持续识别多久,
 * 给电脑那边 `dumpsys meminfo` 取识别中的内存(默认 0);`runs` 每段 5 秒语音识别几次(默认 3)。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceAcceptanceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun measureInstalledPacks() = runBlocking {
        assumeTrue("needs arm64-v8a", SherpaOnnxBackend().abiSupported())
        val root = File(context.cacheDir, "stt-eval")
        assumeTrue("no eval audio in $root", File(root, "utt").isDirectory)
        val backends = OnDeviceBackends()
        val catalog = VoicePackCatalog.load(context, backends::supports)
        val store = VoicePackStore(File(context.filesDir, "voice-models"))
        val wanted = args.getString("packs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val holdMs = args.getString("holdMs")?.toLongOrNull() ?: 0L
        val runs = args.getString("runs")?.toIntOrNull() ?: 3

        for (entry in catalog.packs) {
            if (wanted != null && entry.id !in wanted) continue
            val pack = store.installed(entry)
            if (pack == null) {
                Log.i(TAG, "${entry.id}: not installed")
                continue
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engines = OnDeviceEngines(backends, scope)
            try {
                System.gc()
                val before = memory()
                val t0 = SystemClock.elapsedRealtime()
                val engine = engines.engine(pack)
                val loadMs = SystemClock.elapsedRealtime() - t0
                val loaded = memory()

                val latency = JSONArray()
                File(root, "5s").listFiles().orEmpty().sortedBy { it.name }.forEach { wav ->
                    val lang = wav.nameWithoutExtension
                    if (lang !in entry.languages) return@forEach
                    val pcm = Wav.decode(wav.readBytes())
                    val times = JSONArray()
                    var text = ""
                    repeat(runs) {
                        val t = SystemClock.elapsedRealtime()
                        text = engine.transcribe(pcm, lang)
                        times.put(SystemClock.elapsedRealtime() - t)
                    }
                    latency.put(JSONObject().put("lang", lang).put("audioMs", pcm.size * 1000L / Wav.RATE).put("ms", times).put("text", text))
                    Log.i(TAG, "${entry.id} 5s/$lang ms=$times")
                }
                val afterDecode = memory()

                val rows = JSONArray()
                File(root, "utt").listFiles().orEmpty().sortedBy { it.name }.forEach { tagDir ->
                    val langDir = tagDir.listFiles().orEmpty().firstOrNull { it.isDirectory } ?: return@forEach
                    if (langDir.name !in entry.languages) return@forEach
                    // 和适配器一样:识别语言在模型的语言表里就指定,否则自动
                    val lang = OnDeviceLanguage.pick(langDir.name, entry.languages)
                    langDir.listFiles().orEmpty().sortedBy { it.nameWithoutExtension.toInt() }.forEach { wav ->
                        val pcm = Wav.decode(wav.readBytes())
                        val t = SystemClock.elapsedRealtime()
                        val hyp = engine.transcribe(pcm, lang)
                        rows.put(
                            JSONObject().put("tag", tagDir.name).put("i", wav.nameWithoutExtension.toInt())
                                .put("hyp", hyp).put("ms", SystemClock.elapsedRealtime() - t).put("audioMs", pcm.size * 1000L / Wav.RATE),
                        )
                    }
                }

                var hold: JSONObject? = null
                if (holdMs > 0) {
                    val clip = File(root, "5s").listFiles().orEmpty().sortedBy { it.name }.firstOrNull { it.nameWithoutExtension in entry.languages }
                    if (clip != null) {
                        val pcm = Wav.decode(clip.readBytes())
                        Log.i(TAG, "${entry.id} HOLD start")
                        val end = SystemClock.elapsedRealtime() + holdMs
                        var n = 0
                        var peak = memory()
                        while (SystemClock.elapsedRealtime() < end) {
                            engine.transcribe(pcm, clip.nameWithoutExtension)
                            n++
                            val m = memory()
                            if (m.getLong("pssKb") > peak.getLong("pssKb")) peak = m
                        }
                        Log.i(TAG, "${entry.id} HOLD end")
                        hold = JSONObject().put("decodes", n).put("peak", peak)
                    }
                }

                val out = JSONObject().put("pack", entry.id).put("threads", minOf(Runtime.getRuntime().availableProcessors(), 4))
                    .put("loadMs", loadMs).put("memBefore", before).put("memLoaded", loaded).put("memAfterDecode", afterDecode)
                    .put("latency", latency).put("rows", rows).put("hold", hold)
                File(root, "results-${entry.id}.json").writeText(out.toString(1))
                Log.i(TAG, "${entry.id} load=${loadMs}ms memBefore=$before memLoaded=$loaded rows=${rows.length()}")
            } finally {
                engines.release()
                scope.cancel()
            }
        }
    }

    /** 本进程的内存:PSS、RSS、原生堆(KB),和 `dumpsys meminfo` 同一来源。 */
    private fun memory(): JSONObject {
        val mi = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val rssKb = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1L
        return JSONObject().put("pssKb", mi.totalPss.toLong()).put("rssKb", rssKb)
            .put("nativeHeapKb", mi.getMemoryStat("summary.native-heap")?.toLongOrNull() ?: -1L)
    }

    private companion object {
        const val TAG = "OnDeviceAcceptance"
    }
}
