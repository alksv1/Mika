package com.wangzan101.mika.download

import com.wangzan101.mika.about.UpdateMirror
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DownloadUrlsTest {
    private val mirrors = listOf(UpdateMirror("m", "GhFast", "https://ghfast.top/"))
    private val release = "https://github.com/wangzan101/Mika/releases/download/voice-models-v1/sensevoice-small-int8--tokens.txt"
    private val upstream = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/base-tokens.txt"

    /** 和更新原来的规则一模一样:官方附件,或「镜像前缀 + 官方地址」,都得是 .apk。 */
    @Test fun apkRulesUnchanged() {
        val official = "https://github.com/wangzan101/Mika/releases/download/v0.2.0/Mika.apk"
        DownloadUrls.requireTrusted(official, official, mirrors, ".apk")
        DownloadUrls.requireTrusted("https://ghfast.top/$official", official, mirrors, ".apk")
        DownloadUrls.requireTrusted(official.replace(".apk", ".APK"), official.replace(".apk", ".APK"), mirrors, ".apk")
        for ((url, allowed) in listOf(
            "https://ghfast.top/$official" to emptyList(),
            "http://ghfast.top/$official" to mirrors,
            "https://evil.test/$official" to mirrors,
            "https://ghfast.top/$official?x=1" to mirrors,
            "https://user:pass@ghfast.top/$official" to mirrors,
            "https://ghfast.top/https://github.com/wangzan101/Mika/releases/download/v0.2.0/other.apk" to mirrors,
            "https://ghfast.top/https://github.com/wangzan101/Mika/releases/tag/v0.2.0" to mirrors,
        )) rejects { DownloadUrls.requireTrusted(url, official, allowed, ".apk") }
        for (url in listOf(
            "http://github.com/wangzan101/Mika/releases/download/v0.2.0/Mika.apk",
            "https://github.com.evil.test/wangzan101/Mika/releases/download/v0.2.0/Mika.apk",
            "https://github.com/other/Mika/releases/download/v0.2.0/Mika.apk",
            "https://github.com/wangzan101/Mika/releases/download/%2e%2e/%2E%2e/%2e%2e/%2e%2e/other/x.apk",
            "https://github.com/wangzan101/Mika/releases/download/v0.2.0/Mika.zip",
            "https://user:pass@github.com/wangzan101/Mika/releases/download/v0.2.0/Mika.apk",
            "https://github.com:8443/wangzan101/Mika/releases/download/v0.2.0/Mika.apk",
            // 语音包的上游主机不能拿来当更新包的官方地址。
            "https://huggingface.co/x/y/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/Mika.apk",
        )) rejects { DownloadUrls.requireTrusted(url, url, mirrors, ".apk") }
    }

    @Test fun packAcceptsReleaseAndMirrorPrefix() {
        DownloadUrls.requireTrusted(release, release, mirrors, null)
        DownloadUrls.requireTrusted("https://ghfast.top/$release", release, mirrors, null)
        DownloadUrls.requireTrusted(upstream, upstream, mirrors, null)
    }

    /** 随 APK 带的清单里每个文件的 Release、镜像和上游地址都能过。 */
    @Test fun packAcceptsEveryBundledCatalogUrl() {
        val catalog = VoicePackCatalog.parse(File("src/main/assets/voice-models/catalog.json").readText()) { _, _ -> true }
        for (entry in catalog.packs) for (f in entry.files) {
            val r = catalog.releaseUrl(entry, f)
            DownloadUrls.requireTrusted(r, r, mirrors, null)
            DownloadUrls.requireTrusted("https://ghfast.top/$r", r, mirrors, null)
            val u = catalog.upstreamUrl(entry, f)
            DownloadUrls.requireTrusted(u, u, mirrors, null)
        }
    }

    @Test fun packRejectsOtherHostsAndUserinfo() {
        for (url in listOf(
            "https://evil.test/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/base-tokens.txt",
            "http://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/base-tokens.txt",
            "https://user@huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/base-tokens.txt",
            "https://user:pass@github.com/wangzan101/Mika/releases/download/voice-models-v1/x",
            "https://huggingface.co:8443/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/base-tokens.txt",
            "https://huggingface.co.evil.test/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/base-tokens.txt",
            // 上游只认固定提交,不认会变的分支。
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt",
            "https://github.com/other/Mika/releases/download/voice-models-v1/x",
            "https://raw.githubusercontent.com/openai/whisper/main/LICENSE",
        )) rejects { DownloadUrls.requireTrusted(url, url, mirrors, null) }
        for ((url, official, allowed) in listOf(
            Triple("https://ghfast.top/$release", release, emptyList()),
            Triple("https://evil.test/$release", release, mirrors),
            Triple("https://user@ghfast.top/$release", release, mirrors),
            Triple("https://ghfast.top/$release?x=1", release, mirrors),
            Triple(release, upstream, mirrors),
            // 镜像只镜像 GitHub 发布附件,不拿来转 HuggingFace。
            Triple("https://ghfast.top/$upstream", upstream, mirrors),
        )) rejects { DownloadUrls.requireTrusted(url, official, allowed, null) }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }
}
