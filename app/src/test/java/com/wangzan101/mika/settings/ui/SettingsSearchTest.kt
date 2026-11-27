package com.wangzan101.mika.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置搜索的匹配、排序与高亮:只看已经换成当前语言的标题和所在位置,与界面无关。 */
class SettingsSearchTest {
    private fun item(id: String, title: String, path: String = "") =
        SettingsSearchItem(SettingsSearchEntry(id, SettingsDestination.Models, 0), title, path)

    private val sample = listOf(
        item("plans", "Scheduled tasks", "Capabilities"),
        item("during_work", "Task running", "Preferences"),
        item("sandbox", "Files & computing environment", "Advanced › Runtime"),
        item("files", "Files", "Capabilities"),
        item("checklists", "Saved checklists", "Data › Saved tasks"),
    )

    private fun ids(query: String, items: List<SettingsSearchItem> = sample) =
        SettingsSearch.search(items, query).map { it.item.entry.id }

    @Test
    fun blankOrUnknownQueryFindsNothing() {
        assertTrue(ids("").isEmpty())
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("nonexistent").isEmpty())
    }

    @Test
    fun everyWordMustMatchTitleOrPath() {
        assertEquals(listOf("sandbox"), ids("files runtime"))
        assertEquals(listOf("checklists"), ids("checklists data"))
        assertTrue(ids("files preferences").isEmpty())
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(listOf("plans"), ids("SCHEDULED"))
    }

    /** 标题开头 > 标题里某个词开头 > 标题中间 > 只在所在位置里;同分时标题短的在前,再按登记顺序。 */
    @Test
    fun titleMatchesRankAbovePathMatches() {
        assertEquals(listOf("files", "sandbox"), ids("files"))
        assertEquals(listOf("during_work", "plans", "checklists"), ids("task"))
        assertEquals(listOf("files", "plans"), ids("capab"))
    }

    @Test
    fun latinAccentsAreOptionalButOtherScriptsAreKept() {
        val vi = listOf(item("lang", "Ngôn ngữ và giao diện"), item("about", "Giới thiệu Mika"))
        assertEquals(listOf("lang"), ids("ngon ngu", vi))
        assertEquals(listOf("lang"), ids("Ngôn", vi))
        assertEquals(listOf("d"), ids("duong", listOf(item("d", "Đường dẫn"))))
        assertEquals(listOf("dev"), ids("ileri", listOf(item("dev", "İleri düzey"))))
        // 韩文不拆成字母:「설」不该匹配「선」
        assertEquals(listOf("a"), ids("설", listOf(item("a", "설정"), item("b", "선택"))))
    }

    @Test
    fun highlightsEveryMatchedWordInTitleAndPath() {
        val hit = SettingsSearch.search(sample, "comp run").single()
        assertEquals("sandbox", hit.item.entry.id)
        assertEquals(listOf(8..11), hit.titleMatches)
        assertEquals(listOf(11..13), hit.pathMatches)
    }

    @Test
    fun overlappingHighlightsAreMerged() {
        val hit = SettingsSearch.search(listOf(item("a", "Saved checklists")), "check checklists").single()
        assertEquals(listOf(6..15), hit.titleMatches)
        assertTrue(hit.pathMatches.isEmpty())
    }

    @Test
    fun foldingKeepsLengthSoRangesPointIntoTheOriginalText() {
        for (text in listOf("Ngôn ngữ và giao diện", "İleri düzey", "設定を検索", "Поиск в настройках", "ابحث في الإعدادات", "설정")) {
            assertEquals(text.length, SettingsSearch.fold(text).length)
        }
    }
}
