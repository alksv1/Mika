package com.wangzan101.mika.voice

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.voice.ondevice.OnDeviceBackends
import com.wangzan101.mika.voice.ondevice.OnDeviceEngines
import com.wangzan101.mika.voice.ondevice.SherpaOnnxBackend
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机:用真的 sherpa-onnx 和 SenseVoice 语音包识别一段自带的 3 秒多英文录音(`assets/ondevice/en-weather.wav`,
 * macOS `say` 合成的 "Hello, the weather is really nice today. Let's go for a walk.",16 kHz 单声道 16 位),
 * 记下加载和识别用时(logcat 标签 `OnDeviceSttTest`)。
 *
 * 语音包不随测试安装,没装时跳过(assumeTrue)。装法:在 App 的语音包界面下载 SenseVoice Small,
 * 或者手动放进调试包的安装目录 `files/voice-models/sensevoice-small-int8/<清单里的版本>/`(由 [VoicePackStore] 定),
 * 文件名、大小都要和 catalog.json 一致,许可证文件 `LICENSE` 也要在,否则 [VoicePackStore.installed] 不认
 * (`-PdevInstall` 构建时包名是 `com.wangzan101.mika.dev`):
 * ```
 * adb push model.int8.onnx tokens.txt LICENSE /data/local/tmp/sv/
 * adb shell run-as com.wangzan101.mika mkdir -p files/voice-models/sensevoice-small-int8/1
 * adb shell run-as com.wangzan101.mika cp /data/local/tmp/sv/model.int8.onnx /data/local/tmp/sv/tokens.txt \
 *     /data/local/tmp/sv/LICENSE files/voice-models/sensevoice-small-int8/1/
 * ```
 * 非 arm64 设备也跳过(这个后端只带 arm64-v8a 的库)。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceRecognitionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transcribesFixture() = runBlocking {
        assumeTrue("needs arm64-v8a", SherpaOnnxBackend().abiSupported())
        val backends = OnDeviceBackends()
        val entry = VoicePackCatalog.load(context, backends::supports).entry(PACK)!!
        val store = VoicePackStore(File(context.filesDir, "voice-models"))
        val pack = store.installed(entry)
        assumeTrue("voice pack ${entry.id} v${entry.version} not installed (${store.status(entry)})", pack != null)

        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { it.readBytes() }
        val pcm = Wav.decode(wav)
        val audioMs = pcm.size * 1000L / Wav.RATE
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = OnDeviceEngines(backends, scope)
        try {
            val t0 = SystemClock.elapsedRealtime()
            val engine = engines.engine(pack!!)
            val loadMs = SystemClock.elapsedRealtime() - t0

            val t1 = SystemClock.elapsedRealtime()
            val forced = engine.transcribe(pcm, "en")
            val decodeMs = SystemClock.elapsedRealtime() - t1

            val t2 = SystemClock.elapsedRealtime()
            val auto = engine.transcribe(pcm, null)
            val autoMs = SystemClock.elapsedRealtime() - t2

            Log.i(TAG, "audio=${audioMs}ms load=${loadMs}ms decode(en)=${decodeMs}ms decode(auto)=${autoMs}ms en='$forced' auto='$auto'")
            assertTrue(forced, forced.lowercase().contains("weather"))
            assertTrue(auto, auto.lowercase().contains("weather"))
        } finally {
            engines.release()
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "OnDeviceSttTest"
        const val PACK = "sensevoice-small-int8"
        const val FIXTURE = "ondevice/en-weather.wav"
    }
}
