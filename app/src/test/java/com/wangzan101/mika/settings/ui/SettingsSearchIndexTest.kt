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
        val indexed = entries.mapNotNull { (it.destination as? SettingsDestination.Page)?.page }.toSet()
        val leftOut = SettingsSearchIndex.unindexedPages.keys
        val missing = SettingsPage.entries.filter { it !in indexed && it !in leftOut }
        assertTrue("设置子页没有搜索条目,也没写明不登记的原因: $missing", missing.isEmpty())
        val both = indexed intersect leftOut
        assertTrue("既登记了又写着不登记: $both", both.isEmpty())
        for ((page, reason) in SettingsSearchIndex.unindexedPages) assertTrue("$page 不登记要写原因", reason.isNotBlank())
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
        for (id in listOf("developer", "run_stats", "device_directories", "root", "sandbox", "recovery", "self_check", "mcp_servers", "personal", "personas", "memory")) {
            assertFalse("$id 不该无条件出现", id in none)
        }
        val devOnly = SettingsSearchIndex.available(setOf(SettingsRequirement.Developer)).map { it.id }
        assertTrue("developer" in devOnly && "run_stats" in devOnly && "root" !in devOnly)
    }

    /** 13 种界面语言里,每个条目都按该语言的标题找得到;不靠英文关键词。 */
    @Test
    fun everyEntryIsFoundByItsLocalizedTitleInEveryLanguage() {
        for ((folder, locale) in LANGUAGES) {
            val texts = ResFileTexts(folder, locale)
            val items = SettingsSearchIndex.resolve(entries) { texts.string(it, emptyArray()) }
            for (item in items) {
                assertTrue("$folder ${item.entry.id} 标题为空", item.title.isNotBlank())
                val hits = SettingsSearch.search(items, item.title)
                assertTrue("$folder: 搜「${item.title}」找不到 ${item.entry.id}", hits.any { it.item.entry.id == item.entry.id })
            }
        }
    }

    @Test
    fun chineseUiMatchesChineseTitlesAndNotEnglishKeys() {
        val items = SettingsSearchIndex.resolve(entries) { ResFileTexts.chinese.string(it, emptyArray()) }
        assertEquals("plans", SettingsSearch.search(items, "定时").first().item.entry.id)
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
