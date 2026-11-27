package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 上手页(`strings_setup.xml`)和锁屏凭据页(`strings_device_access.xml`)的文案,在每种非英语语言里都要有译文:
 * 条目数和占位符都对得上、内容却是英文原文的时候,[StringResourcesTest] 查不出来,阿拉伯语目录曾这样整页留着英文。
 * 各语言本来就拼写相同的条目列在 [sameSpelling] 里。
 */
class ModelSetupTranslationTest {
    private val files = listOf("strings_setup.xml", "strings_device_access.xml")

    private val others = listOf(
        "values-zh" to Locale.SIMPLIFIED_CHINESE,
        "values-b+zh+Hant" to Locale.TRADITIONAL_CHINESE,
        "values-ja" to Locale.JAPANESE,
        "values-ko" to Locale.KOREAN,
        "values-es" to Locale.forLanguageTag("es"),
        "values-pt" to Locale.forLanguageTag("pt"),
        "values-ru" to Locale.forLanguageTag("ru"),
        "values-tr" to Locale.forLanguageTag("tr"),
        "values-ar" to Locale.forLanguageTag("ar"),
        "values-fa" to Locale.forLanguageTag("fa"),
        // 印尼语的资源文件夹沿用安卓的旧语言代码 in
        "values-in" to Locale.forLanguageTag("id"),
        "values-vi" to Locale.forLanguageTag("vi"),
    )

    /** 条目名 to 可以与英文拼写相同的语言文件夹;空集合表示每种语言都相同。 */
    private val sameSpelling = mapOf(
        // 各语言通用的缩写
        "device_access_pin" to emptySet(),
        // 「Key %1$d」:中文保留 Key 的叫法
        "model_key_number" to setOf("values-zh", "values-b+zh+Hant"),
    )

    private val en = ResFileTexts.english

    @Test
    fun setupAndCredentialTextsAreTranslatedEverywhere() {
        val names = files.flatMap(::englishNames)
        assertTrue(names.size > 100)
        val leftInEnglish = others.flatMap { (folder, locale) ->
            val texts = ResFileTexts(folder, locale)
            names.filter { name -> !mayMatchEnglish(folder, name) && texts.strings[name] == en.strings[name] }
                .map { name -> "$folder/$name" }
        }
        assertEquals(emptyList<String>(), leftInEnglish)
    }

    private fun mayMatchEnglish(folder: String, name: String): Boolean {
        val folders = sameSpelling[name] ?: return false
        return folders.isEmpty() || folder in folders
    }

    /** 英文文件里要翻的条目名;不翻的条目只在默认文件里。 */
    private fun englishNames(file: String): List<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/$file")).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
    }
}
