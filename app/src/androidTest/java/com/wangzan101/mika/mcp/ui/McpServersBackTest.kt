package com.wangzan101.mika.mcp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.McpServerStore
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** MCP 服务器页的返回:页内逐级回上一层,到了服务列表才离开这一页(以前系统返回键直接退出整页)。 */
@RunWith(AndroidJUnit4::class)
class McpServersBackTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun systemBackReturnsOneLevelInsideTheMcpPageThenLeaves() {
        val store = McpServerStore(tmp.root, TrivialCipher(), OkHttpClient(), catalog = McpCatalog.Empty)
        var left = 0
        compose.setContent { McpServersScreen(store, onBack = { left++ }) }

        compose.onNodeWithText("MCP 服务器").assertIsDisplayed()
        compose.onNodeWithText("浏览服务目录").performClick()
        compose.onNodeWithText("MCP 服务").assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithText("MCP 服务器").assertIsDisplayed()
        assertEquals(0, left)

        Espresso.pressBack()
        assertEquals(1, left)
    }

    private class TrivialCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
}
