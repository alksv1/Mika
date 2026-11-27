package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.i18n.ResFileTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 设置搜索登记了哪些页面:设置里每个子页要么登记了搜索条目,要么写明为什么不登记。
 * 新加一个 [SettingsPage] 而忘了登记,这里会失败。
 */
class SettingsSearchIndexTest {
    private val entries = SettingsSearchIndex.entries

    @Test
    fun everySettingsPageIsSearchableOrExplicitlyLeftOut() {
        val indexed = entries.mapNotNull { (it.destination as? SettingsDestination.Page)?.takeIf { d -> d.item == null }?.page }.toSet()
        val leftOut = SettingsSearchIndex.unindexedPages.keys
        val missing = SettingsPage.entries.filter { it !in indexed && it !in leftOut }
        assertTrue("设置子页没有搜索条目,也没写明不登记的原因: $missing", missing.isEmpty())
        val both = indexed intersect leftOut
        assertTrue("既登记了又写着不登记: $both", both.isEmpty())
        for ((page, reason) in SettingsSearchIndex.unindexedPages) assertTrue("$page 不登记要写原因", reason.isNotBlank())
    }

    @Test
    fun inPageItemsUseKnownFocusKeys() {
        val keyed = entries.mapNotNull { it.destination as? SettingsDestination.Page }.filter { it.item != null }
        val items = keyed.map { it.item!! }
        assertTrue("未知定位标识: ${items.filterNot { it in SettingsFocusKey.ALL }}", items.all { it in SettingsFocusKey.ALL })
        assertEquals("SettingsFocusKey 有未进搜索索引的项", SettingsFocusKey.ALL, items.toSet())
        val pairs = keyed.map { it.page to it.item }
        assertEquals("同一子页下定位标识重复", pairs.distinct(), pairs)
    }

    @Test
    fun entryIdsAreUniqueAndStable() {
        val ids = entries.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.all { it.matches(Regex("[a-z_]+")) })
    }

    /** 能力不在时,对应的入口在设置里不显示,搜索里也不出现。 */
    @Test
    fun entriesNeedingAMissingCapabilityAreHidden() {
        val none = SettingsSearchIndex.available(emptySet()).map { it.id }.toSet()
        val all = SettingsSearchIndex.available(SettingsRequirement.entries.toSet()).map { it.id }.toSet()
        assertEquals(entries.map { it.id }.toSet(), all)
        for (e in entries) assertEquals(e.id, e.requires == null, e.id in none)
        for (id in listOf(
            "developer", "run_stats", "device_directories", "root", "sandbox", "sandbox_network", "sandbox_usage", "sandbox_reset",
            "recovery", "recents_exclusion", "recovery_a11y", "recovery_battery", "recovery_boot", "recovery_auto_wireless",
            "self_check", "mcp_servers", "personal", "personas", "memory",
            "usage", "voice_stt", "voice_tts", "call_mode", "voice_services", "image_model", "video_model",
            "parallel_tasks", "live_task_updates", "shizuku", "shell_transport",
        )) {
            assertFalse("$id 不该无条件出现", id in none)
        }
        val devOnly = SettingsSearchIndex.available(setOf(SettingsRequirement.Developer)).map { it.id }
        assertTrue("developer" in devOnly && "run_stats" in devOnly && "root" !in devOnly)
        val modelOnly = SettingsSearchIndex.available(setOf(SettingsRequirement.ModelSubPages)).map { it.id }
        assertTrue("usage" in modelOnly && "voice_tts" in modelOnly && "parallel_tasks" !in modelOnly)
    }

    /** 13 种界面语言里,每个条目都按该语言的标题和同义词找得到;不靠英文关键词。 */
    @Test
    fun everyEntryIsFoundByItsLocalizedTitleInEveryLanguage() {
        for ((folder, locale) in LANGUAGES) {
            val texts = ResFileTexts(folder, locale)
            val items = SettingsSearchIndex.resolve(entries) { texts.string(it, emptyArray()) }
            for (item in items) {
                assertTrue("$folder ${item.entry.id} 标题为空", item.title.isNotBlank())
                val hits = SettingsSearch.search(items, item.title)
                assertTrue("$folder: 搜「${item.title}」找不到 ${item.entry.id}", hits.any { it.item.entry.id == item.entry.id })
                for (alias in item.aliases) {
                    assertTrue("$folder ${item.entry.id} 同义词为空", alias.isNotBlank())
                    val aliasHits = SettingsSearch.search(items, alias)
                    assertTrue("$folder: 搜「$alias」找不到 ${item.entry.id}", aliasHits.any { it.item.entry.id == item.entry.id })
                }
            }
        }
    }

    @Test
    fun chineseUiMatchesChineseTitlesAndNotEnglishKeys() {
        val items = SettingsSearchIndex.resolve(entries) { ResFileTexts.chinese.string(it, emptyArray()) }
        assertEquals("plans", SettingsSearch.search(items, "定时").first().item.entry.id)
        assertEquals("proxy", SettingsSearch.search(items, "代理").first().item.entry.id)
        assertEquals("appearance", SettingsSearch.search(items, "深色").first().item.entry.id)
        assertEquals("step_limit", SettingsSearch.search(items, "步数").first().item.entry.id)
        assertEquals("recovery_auto_wireless", SettingsSearch.search(items, "无线调试").first().item.entry.id)
        assertTrue(SettingsSearch.search(items, "无线调试").any { it.item.entry.id == "shell_transport" })
        assertEquals("voice_tts", SettingsSearch.search(items, "朗读").first().item.entry.id)
        assertEquals("lock_screen_credential", SettingsSearch.search(items, "锁屏").first().item.entry.id)
        assertTrue(SettingsSearch.search(items, "锁屏").any { it.item.entry.id == "lock_screen_tasks" })
        assertTrue(SettingsSearch.search(items, "Scheduled").isEmpty())
        // 子页带着它在哪:沙盒在「高级 › 运行环境」下面
        val sandbox = items.single { it.entry.id == "sandbox" }
        assertTrue(sandbox.path.startsWith(ResFileTexts.chinese.strings.getValue("settings_advanced")))
    }

    private companion object {
        val LANGUAGES = listOf(
            "values" to Locale.ENGLISH,
            "values-zh" to Locale.SIMPLIFIED_CHINESE,
            "values-b+zh+Hant" to Locale.TRADITIONAL_CHINESE,
            "values-ja" to Locale.JAPANESE,
            "values-ko" to Locale.KOREAN,
            "values-es" to Locale("es"),
            "values-pt" to Locale("pt"),
            "values-ru" to Locale("ru"),
            "values-tr" to Locale("tr"),
            "values-vi" to Locale("vi"),
            "values-in" to Locale("in"),
            "values-ar" to Locale("ar"),
            "values-fa" to Locale("fa"),
        )
    }
}
