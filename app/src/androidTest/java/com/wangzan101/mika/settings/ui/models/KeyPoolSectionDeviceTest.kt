package com.wangzan101.mika.settings.ui.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 服务商详情里的密钥区:标题、每把 Key、添加行和脚注都按池里 Key 的数量说话,而且跟着界面语言走
 * (断言写的是简体中文;这一块以前是写死的英文)。
 */
@RunWith(AndroidJUnit4::class)
class KeyPoolSectionDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private val removed = mutableListOf<String>()
    private var added = 0

    private fun show(keys: List<String>) {
        compose.setContent {
            MikaTheme {
                KeyPoolSection(keys, showKeys = false, onToggleShow = {}, onRemove = { removed += it }, onAdd = { added++ })
            }
        }
    }

    @Test
    fun withoutKeysTheSectionSaysSoAndOffersToAddOne() {
        show(emptyList())
        compose.onNodeWithText("API Key").assertIsDisplayed()
        compose.onNodeWithText("还没有填 Key").assertIsDisplayed()
        compose.onNodeWithText("添加一个 Key 才能连接这家服务商").assertIsDisplayed()
        compose.onNodeWithText("必须填").assertIsDisplayed()
        compose.onNodeWithText("添加 API Key").assertIsDisplayed()
        compose.onNodeWithText("粘贴你的 Key 来连接").assertIsDisplayed()
        // 没配 Key 时没有显隐切换,也没有脚注
        compose.onNodeWithTag("toggle-show-keys").assertDoesNotExist()
        compose.onNodeWithText("再添加几个 Key，就能自动故障转移轮换。").assertDoesNotExist()
    }

    @Test
    fun oneKeyIsListedMaskedWithANudgeToAddMore() {
        show(listOf("key-one-0123456789"))
        compose.onNodeWithText("API Key 池 · 1 个 Key").assertIsDisplayed()
        compose.onNodeWithText("Key 1").assertIsDisplayed()
        // 默认遮住中间;明文不出现
        compose.onNodeWithText("key-one-…6789").assertIsDisplayed()
        compose.onNodeWithText("key-one-0123456789").assertDoesNotExist()
        compose.onNodeWithText("生效中").assertIsDisplayed()
        compose.onNodeWithText("再添加一个 Key 到轮换池").assertIsDisplayed()
        compose.onNodeWithText("再添加几个 Key，就能自动故障转移轮换。").assertIsDisplayed()
        // 只有一把时不给单独移除
        compose.onNodeWithText("移除").assertDoesNotExist()
    }

    @Test
    fun showKeysToggleRevealsTheKeysInPlain() {
        var showKeys by mutableStateOf(false)
        compose.setContent {
            MikaTheme {
                KeyPoolSection(listOf("key-one-0123456789"), showKeys, onToggleShow = { showKeys = !showKeys }, onRemove = {}, onAdd = {})
            }
        }
        compose.onNodeWithText("显示 Key").assertIsDisplayed()
        compose.onNodeWithTag("toggle-show-keys").performClick()
        compose.onNodeWithText("key-one-0123456789").assertIsDisplayed()
        compose.onNodeWithText("隐藏 Key").assertIsDisplayed()
    }

    @Test
    fun severalKeysAreEachRemovableAndTheFooterExplainsRotation() {
        show(listOf("key-one-0123456789", "key-two-0123456789", "key-abc-0123456789"))
        compose.onNodeWithText("API Key 池 · 3 个 Key · 自动轮换").assertIsDisplayed()
        compose.onNodeWithText("Key 2").assertIsDisplayed()
        compose.onNodeWithText("Key 会自动轮换。遇到 429 限流或超时的 Key 会被跳过，等它恢复再用，任务不中断。").assertIsDisplayed()
        compose.onNodeWithTag("remove-key-0").assertIsDisplayed()
        compose.onNodeWithTag("remove-key-2").assertIsDisplayed()
        compose.onNodeWithTag("remove-key-1").performClick()
        assertEquals(listOf("key-two-0123456789"), removed)
        compose.onNodeWithText("添加 API Key").performClick()
        assertEquals(1, added)
    }
}
