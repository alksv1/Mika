package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.UiTexts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 密钥池里按 Key 数量说话的几处文案:英文和简体中文各按真实译文断言,数量走进各语言自己的复数词条。 */
class KeyPoolCopyTest {
    private var previous: UiTexts? = null

    @Before fun rememberTexts() { previous = UiText.source }
    @After fun restoreTexts() { UiText.source = previous }

    @Test fun sectionTitleFollowsTheNumberOfKeys() {
        UiText.source = ResFileTexts.english
        assertEquals("API Keys", KeyPoolCopy.sectionTitle(0))
        assertEquals("API Key Pool · 1 key", KeyPoolCopy.sectionTitle(1))
        assertEquals("API Key Pool · 3 keys · auto-rotating", KeyPoolCopy.sectionTitle(3))
        UiText.source = ResFileTexts.chinese
        assertEquals("API Key", KeyPoolCopy.sectionTitle(0))
        assertEquals("API Key 池 · 1 个 Key", KeyPoolCopy.sectionTitle(1))
        assertEquals("API Key 池 · 3 个 Key · 自动轮换", KeyPoolCopy.sectionTitle(3))
    }

    @Test fun footerNoteOnlyOnceAKeyExists() {
        UiText.source = ResFileTexts.english
        assertNull(KeyPoolCopy.footerNote(0))
        assertEquals("Add more keys to enable automatic failover rotation.", KeyPoolCopy.footerNote(1))
        assertEquals("Keys rotate automatically. Any key that hits a 429 rate-limit or timeout is skipped until it recovers — zero downtime.",
            KeyPoolCopy.footerNote(2))
        UiText.source = ResFileTexts.chinese
        assertNull(KeyPoolCopy.footerNote(0))
        assertEquals("再添加几个 Key，就能自动故障转移轮换。", KeyPoolCopy.footerNote(1))
        assertEquals("Key 会自动轮换。遇到 429 限流或超时的 Key 会被跳过，等它恢复再用，任务不中断。", KeyPoolCopy.footerNote(3))
    }

    @Test fun pastedKeyCountsAreSaidWhereverKeysArePasted() {
        UiText.source = ResFileTexts.english
        assertEquals("2 keys detected — all will be added to the rotation pool", KeyPoolCopy.detectedAdd(2))
        assertEquals("✓ Key Pool: 3 API keys detected (auto-rotating on 429/timeout)", KeyPoolCopy.detectedChange(3))
        assertEquals("2 keys detected — pool will auto-rotate on rate-limit or timeout", KeyPoolCopy.detectedSetup(2))
        UiText.source = ResFileTexts.chinese
        assertEquals("检测到 2 个 Key，会全部加入轮换池", KeyPoolCopy.detectedAdd(2))
        assertEquals("✓ Key 池：检测到 3 个 API Key（遇到 429 限流或超时会自动轮换）", KeyPoolCopy.detectedChange(3))
        assertEquals("检测到 2 个 Key，遇到限流或超时会自动轮换", KeyPoolCopy.detectedSetup(2))
    }

    @Test fun addConfirmNamesTheCountOnlyForSeveralKeys() {
        UiText.source = ResFileTexts.english
        assertEquals("Save", KeyPoolCopy.addConfirm(1, "Save"))
        assertEquals("Add 3 Keys", KeyPoolCopy.addConfirm(3, "Save"))
        UiText.source = ResFileTexts.chinese
        assertEquals("保存", KeyPoolCopy.addConfirm(1, "保存"))
        assertEquals("添加 3 个 Key", KeyPoolCopy.addConfirm(3, "保存"))
    }

    @Test fun providerLineNamesThePoolOnlyForSeveralKeys() {
        UiText.source = ResFileTexts.english
        assertEquals("deepseek-chat", KeyPoolCopy.providerLine("deepseek-chat", 1))
        assertEquals("deepseek-chat · Key Pool (2 keys)", KeyPoolCopy.providerLine("deepseek-chat", 2))
        UiText.source = ResFileTexts.chinese
        assertEquals("deepseek-chat", KeyPoolCopy.providerLine("deepseek-chat", 0))
        assertEquals("deepseek-chat · Key 池（2 个 Key）", KeyPoolCopy.providerLine("deepseek-chat", 2))
    }

    @Test fun providersSubtitleCountsProvidersWithThePluralForms() {
        UiText.source = ResFileTexts.english
        assertEquals("No providers connected", KeyPoolCopy.providersSubtitle(0, 0))
        // 只有一家服务商但池里有多把 Key:服务商数用单数,不再写成「1 providers」
        assertEquals("1 provider · 3 keys in pool", KeyPoolCopy.providersSubtitle(1, 3))
        assertEquals("2 providers · 5 keys in pool", KeyPoolCopy.providersSubtitle(2, 5))
        assertEquals("2 providers", KeyPoolCopy.providersSubtitle(2, 1))
        UiText.source = ResFileTexts.chinese
        assertEquals("还没有连接服务商", KeyPoolCopy.providersSubtitle(0, 0))
        assertEquals("1 家 · 池里有 3 个 Key", KeyPoolCopy.providersSubtitle(1, 3))
        assertEquals("2 家", KeyPoolCopy.providersSubtitle(2, 1))
    }
}
