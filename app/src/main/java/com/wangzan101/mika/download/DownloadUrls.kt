package com.wangzan101.mika.download

import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.UpdateMirror
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 下载地址的白名单,App 更新和语音包共用。地址只能是「官方地址」本身,或「内置/用户添加的镜像前缀 + 官方地址」。
 * 镜像本身仍由用户信任;下载完照样校验大小、摘要(更新包还验签名),地址放宽不会让别的文件装进来。
 *
 * - 更新包([suffix] 为 `.apk`):官方地址只能是 Mika 仓库的 HTTPS 发布附件。
 * - 语音包([suffix] 为空):官方地址是清单给出的 Release 地址或上游地址,调用方传入这个文件的精确地址。
 *   Release 地址同样是 Mika 仓库的发布附件;上游地址只认 HuggingFace 固定提交(`/resolve/<提交>/`),
 *   以及清单里单独写明、同样固定提交的 GitHub 原始文件(许可证原文)。镜像只镜像 GitHub 发布附件。
 */
object DownloadUrls {
    private val release = "${MikaLinks.RELEASES}/download/".toHttpUrl()
    private val commit = "[0-9a-f]{40}"
    /** 上游主机和它的固定提交路径形式:`/<作者>/<仓库>/resolve/<提交>/…`、`/<作者>/<仓库>/<提交>/…`。 */
    private val upstream = mapOf(
        "huggingface.co" to Regex("/[^/]+/[^/]+/resolve/$commit/.+"),
        "raw.githubusercontent.com" to Regex("/[^/]+/[^/]+/$commit/.+"),
    )

    /** [url] 是 Mika 仓库的 HTTPS 发布附件,且以 [suffix] 结尾(不分大小写;为空不限)。 */
    fun requireRelease(url: String, suffix: String?) {
        val parsed = url.toHttpUrl()
        requireHttps(parsed)
        require(parsed.host == release.host)
        require(parsed.encodedPath.startsWith(release.encodedPath))
        requireSuffix(parsed, suffix)
    }

    /**
     * [url] 只能是 [official] 本身,或 [mirrors] 里某个前缀 + [official]。
     * [suffix] 为 `.apk` 时是更新包的规则;为空时是语音包的规则,[official] 可以是 Release 或上游地址。
     * 不合格抛 [IllegalArgumentException]。
     */
    fun requireTrusted(url: String, official: String, mirrors: List<UpdateMirror>, suffix: String?) {
        if (url == official) {
            if (suffix != null) requireRelease(url, suffix) else requireOfficialPackUrl(url)
            return
        }
        require(mirrors.any { url == it.prefix + official }) { "not an official or configured mirror URL" }
        // 更新包的官方地址调用方已先验过;语音包有两种官方地址,镜像只能套在 Release 地址上。
        if (suffix == null) requireRelease(official, null)
        val parsed = url.toHttpUrl()
        require(parsed.isHttps)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        requireSuffix(parsed, suffix)
    }

    private fun requireOfficialPackUrl(url: String) {
        val parsed = url.toHttpUrl()
        if (parsed.host == release.host) return requireRelease(url, null)
        requireHttps(parsed)
        val path = requireNotNull(upstream[parsed.host]) { "not a trusted voice-pack host" }
        require(parsed.query == null && parsed.fragment == null)
        require(path.matches(parsed.encodedPath))
    }

    private fun requireHttps(parsed: HttpUrl) {
        require(parsed.isHttps && parsed.port == 443)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
    }

    private fun requireSuffix(parsed: HttpUrl, suffix: String?) {
        if (suffix != null) require(parsed.encodedPath.endsWith(suffix, ignoreCase = true))
    }
}
