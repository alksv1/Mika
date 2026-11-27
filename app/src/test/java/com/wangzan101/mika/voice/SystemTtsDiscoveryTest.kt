package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SystemTtsDiscoveryTest {
    @Test fun defaultEngineComesFirstThenByName() {
        val sorted = SystemTtsDiscovery.sortEngines(
            listOf(
                SystemTtsEngineInfo("com.example.b", "beta"),
                SystemTtsEngineInfo("com.example.c", "Gamma", isDefault = true),
                SystemTtsEngineInfo("com.example.a", "Alpha"),
            ),
        )

        assertEquals(listOf("com.example.c", "com.example.a", "com.example.b"), sorted.map { it.packageName })
    }

    @Test fun voicesInTheAppLanguageComeFirst() {
        val sorted = SystemTtsDiscovery.sortVoices(
            listOf(
                SystemTtsVoiceInfo("en-1", Locale.US, "English · en-1"),
                SystemTtsVoiceInfo("zh-2", Locale.SIMPLIFIED_CHINESE, "中文 · zh-2"),
                SystemTtsVoiceInfo("zh-1", Locale.TRADITIONAL_CHINESE, "中文 · zh-1"),
                SystemTtsVoiceInfo("x", null, "x"),
            ),
            Locale.forLanguageTag("zh-CN"),
        )

        assertEquals(listOf("zh-1", "zh-2", "en-1", "x"), sorted.map { it.name })
    }
}
