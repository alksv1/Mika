package com.wangzan101.mika.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设置首页的搜索:按界面语言(这里是简体中文)的标题找,点结果去对应的页,能力不在的入口不出现。 */
@RunWith(AndroidJUnit4::class)
class SettingsSearchScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val opened = mutableListOf<SettingsDestination>()
    private var query by mutableStateOf("")

    private fun home(available: Set<SettingsRequirement> = emptySet()) {
        compose.setContent {
            SettingsContent(
                status = ready, androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                searchQuery = query, onSearchQuery = { query = it },
                searchAvailable = available, onOpenSearchResult = { opened += it },
            )
        }
    }

    @Test
    fun typingShowsLocalizedMatchesWithWhereTheyAre() {
        home(setOf(SettingsRequirement.Sandbox))
        compose.onNodeWithTag("settings-search-input").performTextInput("运行环境")
        compose.onNodeWithTag("settings-search-sandbox").assertIsDisplayed()
        // 搜索时首页的状态卡和各组收起来
        compose.onNodeWithTag("settings-status").assertDoesNotExist()
        compose.onNodeWithTag("settings-search-sandbox").performClick()
        assertEquals(listOf<SettingsDestination>(SettingsDestination.Page(SettingsPage.Sandbox)), opened)
    }

    @Test
    fun entriesOutsideSettingsGoToTheirOwnPages() {
        home()
        compose.onNodeWithTag("settings-search-input").performTextInput("定时")
        compose.onNodeWithTag("settings-search-plans").performClick()
        assertEquals(listOf<SettingsDestination>(SettingsDestination.Plans), opened)
    }

    @Test
    fun hiddenCapabilitiesStayHidden() {
        home()
        compose.onNodeWithTag("settings-search-input").performTextInput("开发者")
        compose.onNodeWithTag("settings-search-developer").assertDoesNotExist()
        compose.onNodeWithTag("settings-search-empty").assertIsDisplayed()
    }

    @Test
    fun englishKeysDoNotMatchInAChineseUi() {
        home()
        compose.onNodeWithTag("settings-search-input").performTextInput("Scheduled")
        compose.onNodeWithTag("settings-search-empty").assertIsDisplayed()
        compose.onNodeWithText("没有匹配的设置项").assertIsDisplayed()
    }

    @Test
    fun clearAndBackReturnToTheHomePage() {
        home()
        compose.onNodeWithTag("settings-search-input").performTextInput("通知")
        compose.onNodeWithTag("settings-search-notifications").assertIsDisplayed()
        compose.onNodeWithTag("settings-search-clear").performClick()
        compose.onNodeWithTag("settings-status").assertIsDisplayed()
        assertEquals("", query)

        compose.onNodeWithTag("settings-search-input").performTextInput("通知")
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithTag("settings-status").assertIsDisplayed()
        assertEquals("", query)
    }
}
