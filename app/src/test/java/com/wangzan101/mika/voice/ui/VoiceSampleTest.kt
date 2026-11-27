package com.wangzan101.mika.voice.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceSampleTest {
    @Test fun voiceInAUiLanguageSpeaksThatLanguage() {
        for (tag in listOf("en-US", "zh-CN", "zh-TW", "ja-JP", "ar-EG", "fa-IR", "in-ID", "id-ID", "pt-BR", "vi-VN")) {
            assertTrue(tag, hasUiTranslation(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun otherLanguagesFallBackToTheUiLanguage() {
        for (tag in listOf("fr-FR", "de-DE", "hi-IN", "und")) {
            assertFalse(tag, hasUiTranslation(Locale.forLanguageTag(tag)))
        }
    }
}
