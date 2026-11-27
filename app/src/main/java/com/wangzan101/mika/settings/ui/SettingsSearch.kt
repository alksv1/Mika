package com.wangzan101.mika.settings.ui

import androidx.annotation.StringRes
import java.text.Normalizer

/** 搜索跳往的模型子页。 */
enum class SettingsModelRoute { Usage, Speech, Voice, CallMode, VoiceServices, Image, Video }

/** 搜索结果点开后去哪:设置里的一个子页(可选定位到页内某一项),或交给设置外面打开的页面与弹窗。 */
internal sealed interface SettingsDestination {
    data class Page(val page: SettingsPage, val item: String? = null) : SettingsDestination
    data object Models : SettingsDestination
    data class ModelRoute(val route: SettingsModelRoute) : SettingsDestination
    data object Plans : SettingsDestination
    data object Files : SettingsDestination
    data object Personal : SettingsDestination
    data object Personas : SettingsDestination
    data object Memory : SettingsDestination
    data object AutoArchive : SettingsDestination
    data object Proxy : SettingsDestination
    data object WebSearch : SettingsDestination
    data object Quit : SettingsDestination
    data object About : SettingsDestination
}

/** 只在某种能力或开关在时才出现的入口;和设置里对应那一行的显示条件一一对应。 */
internal enum class SettingsRequirement {
    /** 系统版本够内置助手用(「恢复与修复」「兼容性自检」)。 */
    Recovery,
    /** 这台手机支持文件与计算环境。 */
    Sandbox,
    /** 外部 MCP 服务器设置接进来了。 */
    McpServers,
    /** 手机上有 su。 */
    Root,
    /** 调试版与性能测试版才有的开发者选项。 */
    Developer,
    /** 「个人助手设为首屏」打开着。 */
    PersonalStart,
    Personas,
    Memory,
    /** 接入了模型下的子页路由。 */
    ModelSubPages,
    /** 实验开关「同时进行的任务」打开着。 */
    ParallelTasks,
    /** Android 16+ 的实时任务通知。 */
    LiveTaskUpdates,
}

/**
 * 一条搜索条目:稳定的 [id]、点开去的 [destination]、标题和它所在位置(组名、上级页)的字符串资源,
 * 以及辅助匹配的同义选项资源([aliases])。只存资源编号,显示与匹配时才按当前界面语言取字,换语言不用重建。
 */
internal data class SettingsSearchEntry(
    val id: String,
    val destination: SettingsDestination,
    @StringRes val title: Int,
    val path: List<Int> = emptyList(),
    val requires: SettingsRequirement? = null,
    val aliases: List<Int> = emptyList(),
)

/** 一条条目换成当前语言后的样子:[path] 是「组 › 上级页」连起来的一行。 */
internal data class SettingsSearchItem(
    val entry: SettingsSearchEntry,
    val title: String,
    val path: String,
    val aliases: List<String> = emptyList(),
)

/** 一条命中:标题和位置里各自要高亮的区间(下标指向原文)。 */
internal data class SettingsSearchHit(
    val item: SettingsSearchItem,
    val titleMatches: List<IntRange>,
    val pathMatches: List<IntRange>,
)

/**
 * 匹配与排序:查询按空白切成词,每个词都要出现在标题、位置或同义词里。
 * 排序看每个词落在哪:标题开头、标题里某个词的开头、标题中间、只在位置里、只在同义词里,依次靠后;
 * 同分时标题短的在前,再按登记顺序。
 * 比较前把大小写和拉丁字母上的附加符号去掉(越南语、土耳其语、西葡语不打声调也找得到),其他文字原样比较。
 */
internal object SettingsSearch {
    fun search(items: List<SettingsSearchItem>, query: String): List<SettingsSearchHit> {
        val words = fold(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.mapNotNull { item -> score(item, words) }
            .sortedWith(compareBy<Pair<SettingsSearchHit, Int>> { it.second }.thenBy { it.first.item.title.length })
            .map { it.first }
    }

    private fun score(item: SettingsSearchItem, words: List<String>): Pair<SettingsSearchHit, Int>? {
        val title = fold(item.title)
        val path = fold(item.path)
        val aliases = item.aliases.map(::fold)
        var total = 0
        for (w in words) {
            val at = title.indexOf(w)
            total += when {
                at == 0 -> 0
                at > 0 && wordStartIn(title, w) -> 1
                at > 0 -> 2
                path.contains(w) -> 3
                aliases.any { it.contains(w) } -> 4
                else -> return null
            }
        }
        return SettingsSearchHit(item, ranges(title, words), ranges(path, words)) to total
    }

    private fun wordStartIn(text: String, word: String): Boolean {
        var at = text.indexOf(word)
        while (at >= 0) {
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            at = text.indexOf(word, at + 1)
        }
        return false
    }

    /** [words] 在 [text] 里出现的每一处,重叠或相接的合并成一段。 */
    private fun ranges(text: String, words: List<String>): List<IntRange> {
        val found = buildList {
            for (w in words) {
                var at = text.indexOf(w)
                while (at >= 0) {
                    add(at until at + w.length)
                    at = text.indexOf(w, at + 1)
                }
            }
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    /** 逐字折叠成小写、去掉拉丁字母的附加符号;长度不变,命中的下标可以直接用在原文上。 */
    fun fold(text: String): String = buildString(text.length) { for (c in text) append(foldChar(c)) }

    private fun foldChar(c: Char): Char {
        if (c == 'đ' || c == 'Đ') return 'd'
        if (c == 'ı') return 'i'
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val stripped = if (base.length > 1 && base[0].code < LATIN_END && base.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) base[0] else c
        return stripped.lowercaseChar()
    }

    private const val LATIN_END = 0x0250
    private val WHITESPACE = Regex("\\s+")
}
