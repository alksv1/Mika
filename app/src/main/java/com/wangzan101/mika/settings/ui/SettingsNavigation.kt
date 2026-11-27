package com.wangzan101.mika.settings.ui

/** 设置里能进入的子页面;[Home] 是设置首页,[Advanced] 集中放置连接、维护和工程能力。 */
internal enum class SettingsPage { Home, Advanced, AppAccess, SmsLogin, PhoneOps, Calls, Wechat, DuringWork, AppPace, Capsule, Language, Notifications, Recommendations, Checklists, Workflows, Sandbox, Plugins, EnvVars, Recovery, SelfCheck, ComputerAccess, McpServers, Root, Developer, DeviceDirectories, RunStats, Proactive, Backup, Pause }

/**
 * 子页的上一层。顶栏返回按钮和系统返回键都按这张表走,不要在各子页各记一遍——
 * 两处各写一套就会漏,返回时直接跳回设置首页。[Home] 没有上一层,返回自己。
 */
internal fun SettingsPage.parent(): SettingsPage = when (this) {
    SettingsPage.Home -> SettingsPage.Home
    SettingsPage.Advanced, SettingsPage.AppAccess, SettingsPage.SmsLogin, SettingsPage.PhoneOps,
    SettingsPage.Calls, SettingsPage.Wechat, SettingsPage.DuringWork, SettingsPage.Capsule,
    SettingsPage.Language, SettingsPage.Notifications, SettingsPage.Recommendations,
    SettingsPage.Proactive, SettingsPage.Backup, SettingsPage.Pause -> SettingsPage.Home
    SettingsPage.AppPace -> SettingsPage.DuringWork
    SettingsPage.Checklists, SettingsPage.Workflows -> SettingsPage.Recommendations
    SettingsPage.Sandbox, SettingsPage.Plugins, SettingsPage.EnvVars, SettingsPage.Recovery,
    SettingsPage.ComputerAccess, SettingsPage.McpServers, SettingsPage.Root, SettingsPage.Developer -> SettingsPage.Advanced
    SettingsPage.SelfCheck -> SettingsPage.Recovery
    SettingsPage.DeviceDirectories, SettingsPage.RunStats -> SettingsPage.Developer
}
