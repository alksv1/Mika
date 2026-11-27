package com.wangzan101.mika.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置子页的上一层:顶栏返回按钮和系统返回键共用 [parent] 这一张表。
 * 以前两处各写一套,插件、清单和工作流在返回时跳过了上一层,直接回到设置首页。
 */
class SettingsNavigationTest {
    @Test
    fun subPagesReturnToThePageTheyCameFrom() {
        assertEquals(SettingsPage.Home, SettingsPage.Home.parent())

        for (page in listOf(
            SettingsPage.Advanced, SettingsPage.AppAccess, SettingsPage.SmsLogin, SettingsPage.PhoneOps,
            SettingsPage.Calls, SettingsPage.Wechat, SettingsPage.DuringWork, SettingsPage.Capsule,
            SettingsPage.Language, SettingsPage.Notifications, SettingsPage.Recommendations,
            SettingsPage.Proactive, SettingsPage.Backup, SettingsPage.Pause,
        )) assertEquals("$page 应返回设置首页", SettingsPage.Home, page.parent())

        assertEquals(SettingsPage.DuringWork, SettingsPage.AppPace.parent())
        assertEquals(SettingsPage.Recommendations, SettingsPage.Checklists.parent())
        assertEquals(SettingsPage.Recommendations, SettingsPage.Workflows.parent())

        for (page in listOf(
            SettingsPage.Sandbox, SettingsPage.Plugins, SettingsPage.EnvVars, SettingsPage.Recovery,
            SettingsPage.ComputerAccess, SettingsPage.McpServers, SettingsPage.Root, SettingsPage.Developer,
        )) assertEquals("$page 应返回高级与其他", SettingsPage.Advanced, page.parent())

        assertEquals(SettingsPage.Recovery, SettingsPage.SelfCheck.parent())
        assertEquals(SettingsPage.Developer, SettingsPage.DeviceDirectories.parent())
        assertEquals(SettingsPage.Developer, SettingsPage.RunStats.parent())
    }

    /** 每一页都能顺着上一层回到设置首页,没有环,也不会卡在中间。 */
    @Test
    fun everyPageReachesHomeWithoutCycles() {
        for (page in SettingsPage.entries) {
            var current = page
            val seen = mutableSetOf<SettingsPage>()
            while (current != SettingsPage.Home) {
                assertTrue("$page 的返回链有环:$seen", seen.add(current))
                current = current.parent()
            }
        }
    }
}
