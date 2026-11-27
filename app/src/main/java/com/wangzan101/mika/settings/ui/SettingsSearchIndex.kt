package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.settings.ui.SettingsDestination.Page

/**
 * 设置搜索能找到的页面。顺序就是同分时的先后,按设置首页从上到下、再到各级子页排。
 *
 * 加了新的设置子页([SettingsPage]),在这里登记一条,或者在 [unindexedPages] 写明为什么不登记;
 * `SettingsSearchIndexTest` 会核对两边。条目的标题和所在位置都用设置里那一行、那一组本来的字符串资源,
 * 所以 13 种界面语言自动跟上,不另写关键词。
 */
internal object SettingsSearchIndex {
    private val MODEL = R.string.settings_model
    private val CAPABILITIES = R.string.settings_capabilities
    private val PREFERENCES = R.string.settings_preferences
    private val DATA = R.string.settings_data
    private val ADVANCED = R.string.settings_advanced

    val entries: List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry("models", SettingsDestination.Models, MODEL),

        SettingsSearchEntry("phone_ops", Page(SettingsPage.PhoneOps), R.string.settings_phone_ops, listOf(CAPABILITIES)),
        SettingsSearchEntry("pause", Page(SettingsPage.Pause), R.string.pause_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("plans", SettingsDestination.Plans, R.string.drawer_plans, listOf(CAPABILITIES)),
        SettingsSearchEntry("files", SettingsDestination.Files, R.string.file_library_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("wechat", Page(SettingsPage.Wechat), R.string.wechat_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("proactive", Page(SettingsPage.Proactive), R.string.proactive_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("personal", SettingsDestination.Personal, R.string.drawer_personal, listOf(CAPABILITIES), SettingsRequirement.PersonalStart),

        SettingsSearchEntry("during_work", Page(SettingsPage.DuringWork), R.string.settings_during_work, listOf(PREFERENCES)),
        SettingsSearchEntry("app_pace", Page(SettingsPage.AppPace), R.string.settings_pace_per_app, listOf(PREFERENCES, R.string.settings_during_work)),
        SettingsSearchEntry("capsule", Page(SettingsPage.Capsule), R.string.capsule_settings_title, listOf(PREFERENCES)),
        SettingsSearchEntry("language", Page(SettingsPage.Language), R.string.settings_language_appearance, listOf(PREFERENCES)),
        SettingsSearchEntry("personas", SettingsDestination.Personas, R.string.persona_title, listOf(PREFERENCES), SettingsRequirement.Personas),
        SettingsSearchEntry("notifications", Page(SettingsPage.Notifications), R.string.notifications_title, listOf(PREFERENCES)),

        SettingsSearchEntry("memory", SettingsDestination.Memory, R.string.memory_and_skills, listOf(DATA), SettingsRequirement.Memory),
        SettingsSearchEntry("backup", Page(SettingsPage.Backup), R.string.backup_title, listOf(DATA)),
        SettingsSearchEntry("auto_archive", SettingsDestination.AutoArchive, R.string.settings_auto_archive, listOf(DATA)),
        SettingsSearchEntry("recommendations", Page(SettingsPage.Recommendations), R.string.recommendations_title, listOf(DATA)),
        SettingsSearchEntry("checklists", Page(SettingsPage.Checklists), R.string.checklists_title, listOf(DATA, R.string.recommendations_title)),
        SettingsSearchEntry("workflows", Page(SettingsPage.Workflows), R.string.workflows_title, listOf(DATA, R.string.recommendations_title)),
        SettingsSearchEntry("app_access", Page(SettingsPage.AppAccess), R.string.appaccess_title, listOf(DATA)),
        SettingsSearchEntry("sms_login", Page(SettingsPage.SmsLogin), R.string.sms_login_title, listOf(DATA)),

        SettingsSearchEntry("advanced", Page(SettingsPage.Advanced), ADVANCED),
        SettingsSearchEntry("computer_access", Page(SettingsPage.ComputerAccess), R.string.mcp_title, listOf(ADVANCED, R.string.settings_extensions)),
        SettingsSearchEntry("mcp_servers", Page(SettingsPage.McpServers), R.string.mcp_servers_title, listOf(ADVANCED, R.string.settings_extensions), SettingsRequirement.McpServers),
        SettingsSearchEntry("plugins", Page(SettingsPage.Plugins), R.string.plugins_title, listOf(ADVANCED, R.string.settings_extensions)),
        SettingsSearchEntry("root", Page(SettingsPage.Root), R.string.phone_root_group, listOf(ADVANCED, R.string.settings_extensions), SettingsRequirement.Root),
        SettingsSearchEntry("env_vars", Page(SettingsPage.EnvVars), R.string.env_vars_title, listOf(ADVANCED)),
        SettingsSearchEntry("sandbox", Page(SettingsPage.Sandbox), R.string.settings_sandbox, listOf(ADVANCED, R.string.settings_runtime), SettingsRequirement.Sandbox),
        SettingsSearchEntry("recovery", Page(SettingsPage.Recovery), R.string.settings_recovery, listOf(ADVANCED, R.string.settings_maintenance), SettingsRequirement.Recovery),
        SettingsSearchEntry("self_check", Page(SettingsPage.SelfCheck), R.string.selfcheck_title, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        SettingsSearchEntry("developer", Page(SettingsPage.Developer), R.string.settings_developer, listOf(ADVANCED, R.string.settings_developer_group), SettingsRequirement.Developer),
        SettingsSearchEntry("device_directories", Page(SettingsPage.DeviceDirectories), R.string.device_dirs_entry, listOf(ADVANCED, R.string.settings_developer), SettingsRequirement.Developer),
        SettingsSearchEntry("run_stats", Page(SettingsPage.RunStats), R.string.metrics_title, listOf(ADVANCED, R.string.settings_developer), SettingsRequirement.Developer),

        SettingsSearchEntry("about", SettingsDestination.About, R.string.settings_about),
    )

    /** 设置里有、但不进搜索的子页,和不登记的原因。 */
    val unindexedPages: Map<SettingsPage, String> = mapOf(
        SettingsPage.Home to "设置首页本身,搜索框就在这一页",
        SettingsPage.Calls to "设置首页已没有这一行,通话设置从「实时语音」进入;搜索不另开一条到不了的旧路",
    )

    /** 在 [have] 这些能力下设置里看得到的条目。 */
    fun available(have: Set<SettingsRequirement>): List<SettingsSearchEntry> =
        entries.filter { e -> e.requires.let { it == null || it in have } }

    /** 按当前界面语言取字([text] 由字符串资源编号取字)。 */
    fun resolve(entries: List<SettingsSearchEntry>, text: (Int) -> String): List<SettingsSearchItem> =
        entries.map { e -> SettingsSearchItem(e, text(e.title), e.path.joinToString(PATH_SEPARATOR) { text(it) }) }

    private const val PATH_SEPARATOR = " › "
}
