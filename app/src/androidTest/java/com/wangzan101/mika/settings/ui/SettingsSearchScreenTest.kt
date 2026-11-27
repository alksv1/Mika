package com.wangzan101.mika.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.ui.components.FocusTargetHost
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.LocalFocusTarget
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.focusHighlightTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun directDialogsInPageItemsAndAliasesOpenExpectedDestinations() {
        home()
        compose.onNodeWithTag("settings-search-input").performTextInput("网络代理")
        compose.onNodeWithTag("settings-search-proxy").performClick()
        assertEquals(listOf<SettingsDestination>(SettingsDestination.Proxy), opened)

        query = ""
        compose.waitForIdle()
        compose.onNodeWithTag("settings-search-input").performTextInput("步数上限")
        compose.onNodeWithTag("settings-search-${SettingsFocusKey.STEP_LIMIT}").performClick()
        assertEquals(
            listOf(
                SettingsDestination.Proxy,
                SettingsDestination.Page(SettingsPage.DuringWork, SettingsFocusKey.STEP_LIMIT),
            ),
            opened,
        )

        query = ""
        compose.waitForIdle()
        compose.onNodeWithTag("settings-search-input").performTextInput("深色")
        compose.onNodeWithTag("settings-search-${SettingsFocusKey.APPEARANCE}").performClick()
        assertEquals(
            listOf(
                SettingsDestination.Proxy,
                SettingsDestination.Page(SettingsPage.DuringWork, SettingsFocusKey.STEP_LIMIT),
                SettingsDestination.Page(SettingsPage.Language, SettingsFocusKey.APPEARANCE),
            ),
            opened,
        )
    }

    @Test
    fun focusTargetScrollsOffscreenRowIntoViewAndConsumesRequest() {
        val host = FocusTargetHost()
        val scrollState = ScrollState(0)
        host.request("target_bottom")
        compose.setContent {
            CompositionLocalProvider(LocalFocusTarget provides host) {
                Box(Modifier.height(240.dp)) {
                    PageScaffold("测试", onBack = {}, scrollState = scrollState) {
                        GroupCard {
                            repeat(20) { i ->
                                ListRow(null, "条目 $i", first = i == 0)
                            }
                            ListRow(
                                null, "目标设置项",
                                modifier = Modifier.testTag("focus-target-row").focusHighlightTarget("target_bottom"),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("目标行应自动滚入视野", scrollState.value > 0)
        compose.onNodeWithTag("focus-target-row").assertIsDisplayed()
        assertNull(host.current)
    }
}
