package com.wangzan101.mika.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SystemStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** 设置子页的返回:回到进入时的那一层,而不是直接跳回设置首页。 */
@RunWith(AndroidJUnit4::class)
class SettingsBackTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val tmp = TemporaryFolder()

    private val status = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun settings() {
        val store = ProviderStore(tmp.root, TrivialCipher())
        compose.setContent {
            SettingsScreen(store, status, androidRelease = "14", onFix = {}, onBack = {}, onOpenModels = {})
        }
    }

    /** 点一行:同名的组标题没有点击动作,跳过它。 */
    private fun open(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
    }

    @Test
    fun pluginBackReturnsToAdvanced() {
        settings()
        open("高级")
        open("插件")
        compose.onNodeWithText("安装插件").assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithText("安装插件").assertDoesNotExist()
        compose.onNodeWithText("高级").assertIsDisplayed()
    }

    @Test
    fun checklistsAndWorkflowsBackReturnToRecommendations() {
        settings()
        open("常用任务")

        open("已存清单")
        Espresso.pressBack()
        compose.onNodeWithText("流程").assertIsDisplayed()

        open("流程")
        Espresso.pressBack()
        compose.onNodeWithText("已存清单").assertIsDisplayed()
    }

    private class TrivialCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
}
