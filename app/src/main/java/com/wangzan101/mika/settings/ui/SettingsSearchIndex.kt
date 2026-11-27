package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.settings.ui.SettingsDestination.ModelRoute
import com.wangzan101.mika.settings.ui.SettingsDestination.Page

/**
 * 设置搜索能找到的页面和页内条目。顺序就是同分时的先后,按设置首页从上到下、再到各级子页排。
 *
 * 加了新的设置子页([SettingsPage]),在这里登记一条,或者在 [unindexedPages] 写明为什么不登记;
 * `SettingsSearchIndexTest` 会核对两边。条目的标题、所在位置和同义选项都用设置里本来的字符串资源,
 * 所以 13 种界面语言自动跟上,不另写关键词。
 */
internal object SettingsSearchIndex {
    private val MODEL = R.string.settings_model
    private val CAPABILITIES = R.string.settings_capabilities
    private val PREFERENCES = R.string.settings_preferences
    private val DATA = R.string.settings_data
    private val ADVANCED = R.string.settings_advanced

    private fun pageItem(
        page: SettingsPage,
        key: String,
        title: Int,
        path: List<Int>,
        requires: SettingsRequirement? = null,
        aliases: List<Int> = emptyList(),
    ) = SettingsSearchEntry(key, Page(page, key), title, path, requires, aliases)

    private fun modelRoute(
        id: String,
        route: SettingsModelRoute,
        title: Int,
        path: List<Int> = listOf(MODEL),
    ) = SettingsSearchEntry(id, ModelRoute(route), title, path, SettingsRequirement.ModelSubPages)

    val entries: List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry("models", SettingsDestination.Models, MODEL),
        modelRoute("usage", SettingsModelRoute.Usage, R.string.usage_title),
        modelRoute("voice_stt", SettingsModelRoute.Speech, R.string.voice_hub_stt, listOf(MODEL, R.string.voice_hub_group)),
        modelRoute("voice_tts", SettingsModelRoute.Voice, R.string.voice_hub_tts, listOf(MODEL, R.string.voice_hub_group)),
        modelRoute("call_mode", SettingsModelRoute.CallMode, R.string.voice_hub_call_mode, listOf(MODEL, R.string.voice_hub_group)),
        modelRoute("voice_services", SettingsModelRoute.VoiceServices, R.string.voice_hub_services, listOf(MODEL, R.string.voice_hub_group)),
        modelRoute("image_model", SettingsModelRoute.Image, R.string.role_image),
        modelRoute("video_model", SettingsModelRoute.Video, R.string.role_video),

        SettingsSearchEntry("phone_ops", Page(SettingsPage.PhoneOps), R.string.settings_phone_ops, listOf(CAPABILITIES)),
        pageItem(SettingsPage.PhoneOps, SettingsFocusKey.ACCESSIBILITY, R.string.settings_a11y, listOf(CAPABILITIES, R.string.settings_phone_ops)),
        pageItem(SettingsPage.PhoneOps, SettingsFocusKey.SHIZUKU, R.string.phone_shizuku_group, listOf(CAPABILITIES, R.string.settings_phone_ops), SettingsRequirement.Recovery),
        pageItem(
            SettingsPage.PhoneOps,
            SettingsFocusKey.SHELL_TRANSPORT,
            R.string.phone_transport_title,
            listOf(CAPABILITIES, R.string.settings_phone_ops),
            SettingsRequirement.Recovery,
            aliases = listOf(R.string.phone_transport_wireless),
        ),
        SettingsSearchEntry("pause", Page(SettingsPage.Pause), R.string.pause_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("plans", SettingsDestination.Plans, R.string.drawer_plans, listOf(CAPABILITIES)),
        SettingsSearchEntry("files", SettingsDestination.Files, R.string.file_library_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("wechat", Page(SettingsPage.Wechat), R.string.wechat_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("proactive", Page(SettingsPage.Proactive), R.string.proactive_title, listOf(CAPABILITIES)),
        SettingsSearchEntry("personal", SettingsDestination.Personal, R.string.drawer_personal, listOf(CAPABILITIES), SettingsRequirement.PersonalStart),

        SettingsSearchEntry("during_work", Page(SettingsPage.DuringWork), R.string.settings_during_work, listOf(PREFERENCES)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.AUTONOMOUS, R.string.dev_autonomy, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.KEEP_SCREEN_ON, R.string.settings_keep_on, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.POCKET_GUARD, R.string.pocket_guard_title, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.POCKET_COMPLETION_LOCK, R.string.pocket_completion_lock_title, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.MUTE_TARGET, R.string.settings_mute, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.STEP_LIMIT, R.string.settings_step_limit, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.TRIGGER_RUN_LIMIT, R.string.settings_trigger_run_limit, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.SANDBOX_BUDGET, R.string.settings_sandbox_budget, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.ATTACHMENT_LIMIT, R.string.settings_attachment_file_limit, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.WORKSPACE_LIMIT, R.string.settings_workspace_size_limit, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.TOKEN_BUDGET, R.string.settings_task_token_budget, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.SHOW_TASK_COST, R.string.settings_show_task_cost, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.PARALLEL_TASKS, R.string.settings_parallel, listOf(PREFERENCES, R.string.settings_during_work), SettingsRequirement.ParallelTasks),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.LOCK_SCREEN_TASKS, R.string.device_local_title, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.LOCK_SCREEN_CREDENTIAL, R.string.device_access_credential, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.SELF_CONFIG, R.string.settings_self_config, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.SELF_OPERATE, R.string.settings_self_operate, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.PIP_OPERATE, R.string.settings_pip_operate, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.EXTERNAL_SCHEDULE, R.string.settings_external_schedule, listOf(PREFERENCES, R.string.settings_during_work)),
        pageItem(SettingsPage.DuringWork, SettingsFocusKey.OPERATING_PACE, R.string.settings_pace, listOf(PREFERENCES, R.string.settings_during_work)),
        SettingsSearchEntry("app_pace", Page(SettingsPage.AppPace), R.string.settings_pace_per_app, listOf(PREFERENCES, R.string.settings_during_work)),

        SettingsSearchEntry("capsule", Page(SettingsPage.Capsule), R.string.capsule_settings_title, listOf(PREFERENCES)),
        pageItem(SettingsPage.Capsule, SettingsFocusKey.CAPSULE_IDLE_OPACITY, R.string.capsule_idle_opacity, listOf(PREFERENCES, R.string.capsule_settings_title)),
        pageItem(SettingsPage.Capsule, SettingsFocusKey.CAPSULE_CARD_SIZE, R.string.capsule_card_size, listOf(PREFERENCES, R.string.capsule_settings_title)),
        pageItem(SettingsPage.Capsule, SettingsFocusKey.CAPSULE_HIDE_AT_EDGE, R.string.capsule_hide_at_edge, listOf(PREFERENCES, R.string.capsule_settings_title)),
        pageItem(SettingsPage.Capsule, SettingsFocusKey.CAPSULE_HIDE_FULLSCREEN, R.string.capsule_hide_fullscreen, listOf(PREFERENCES, R.string.capsule_settings_title)),
        pageItem(SettingsPage.Capsule, SettingsFocusKey.CAPSULE_QUICK_SETTINGS, R.string.capsule_tile_enabled, listOf(PREFERENCES, R.string.capsule_settings_title)),

        SettingsSearchEntry("language", Page(SettingsPage.Language), R.string.settings_language_appearance, listOf(PREFERENCES)),
        pageItem(
            SettingsPage.Language,
            SettingsFocusKey.APPEARANCE,
            R.string.appearance_group,
            listOf(PREFERENCES, R.string.settings_language_appearance),
            aliases = listOf(R.string.appearance_light, R.string.appearance_dark),
        ),
        pageItem(SettingsPage.Language, SettingsFocusKey.ACCENT_COLOR, R.string.accent_group, listOf(PREFERENCES, R.string.settings_language_appearance)),
        pageItem(SettingsPage.Language, SettingsFocusKey.BACKGROUND_SKIN, R.string.skin_group, listOf(PREFERENCES, R.string.settings_language_appearance)),
        pageItem(SettingsPage.Language, SettingsFocusKey.APP_LANGUAGE, R.string.language_title, listOf(PREFERENCES, R.string.settings_language_appearance)),

        SettingsSearchEntry("personas", SettingsDestination.Personas, R.string.persona_title, listOf(PREFERENCES), SettingsRequirement.Personas),
        SettingsSearchEntry("notifications", Page(SettingsPage.Notifications), R.string.notifications_title, listOf(PREFERENCES)),
        pageItem(SettingsPage.Notifications, SettingsFocusKey.SCHEDULED_QUIET, R.string.settings_scheduled_quiet, listOf(PREFERENCES, R.string.notifications_title)),
        pageItem(SettingsPage.Notifications, SettingsFocusKey.LIVE_TASK_UPDATES, R.string.settings_live_task_updates, listOf(PREFERENCES, R.string.notifications_title), SettingsRequirement.LiveTaskUpdates),
        pageItem(SettingsPage.Home, SettingsFocusKey.PERSONAL_START, R.string.settings_personal_start, listOf(PREFERENCES)),

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
        pageItem(SettingsPage.ComputerAccess, SettingsFocusKey.MCP_HISTORY, R.string.mcp_history_title, listOf(ADVANCED, R.string.mcp_title)),
        pageItem(SettingsPage.ComputerAccess, SettingsFocusKey.REMOTE_DEVICE_ACCESS, R.string.device_access_title, listOf(ADVANCED, R.string.mcp_title)),
        SettingsSearchEntry("mcp_servers", Page(SettingsPage.McpServers), R.string.mcp_servers_title, listOf(ADVANCED, R.string.settings_extensions), SettingsRequirement.McpServers),
        SettingsSearchEntry("plugins", Page(SettingsPage.Plugins), R.string.plugins_title, listOf(ADVANCED, R.string.settings_extensions)),
        SettingsSearchEntry("root", Page(SettingsPage.Root), R.string.phone_root_group, listOf(ADVANCED, R.string.settings_extensions), SettingsRequirement.Root),
        SettingsSearchEntry("env_vars", Page(SettingsPage.EnvVars), R.string.env_vars_title, listOf(ADVANCED)),
        SettingsSearchEntry("proxy", SettingsDestination.Proxy, R.string.net_proxy, listOf(ADVANCED, R.string.settings_network)),
        SettingsSearchEntry("web_search", SettingsDestination.WebSearch, R.string.web_search_title, listOf(ADVANCED, R.string.settings_network)),
        SettingsSearchEntry("sandbox", Page(SettingsPage.Sandbox), R.string.settings_sandbox, listOf(ADVANCED, R.string.settings_runtime), SettingsRequirement.Sandbox),
        pageItem(SettingsPage.Sandbox, SettingsFocusKey.SANDBOX_NETWORK, R.string.sandbox_network, listOf(ADVANCED, R.string.settings_sandbox), SettingsRequirement.Sandbox),
        pageItem(SettingsPage.Sandbox, SettingsFocusKey.SANDBOX_USAGE, R.string.sandbox_usage, listOf(ADVANCED, R.string.settings_sandbox), SettingsRequirement.Sandbox),
        pageItem(SettingsPage.Sandbox, SettingsFocusKey.SANDBOX_RESET, R.string.sandbox_reset, listOf(ADVANCED, R.string.settings_sandbox), SettingsRequirement.Sandbox),
        SettingsSearchEntry("recovery", Page(SettingsPage.Recovery), R.string.settings_recovery, listOf(ADVANCED, R.string.settings_maintenance), SettingsRequirement.Recovery),
        pageItem(SettingsPage.Recovery, SettingsFocusKey.RECENTS_EXCLUSION, R.string.rec_exclude_recents, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        pageItem(SettingsPage.Recovery, SettingsFocusKey.RECOVERY_A11Y, R.string.rec_a11y, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        pageItem(SettingsPage.Recovery, SettingsFocusKey.RECOVERY_BATTERY, R.string.rec_battery, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        pageItem(SettingsPage.Recovery, SettingsFocusKey.RECOVERY_BOOT, R.string.rec_boot, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        pageItem(SettingsPage.Recovery, SettingsFocusKey.RECOVERY_AUTO_WIRELESS, R.string.rec_auto_wireless, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        SettingsSearchEntry("self_check", Page(SettingsPage.SelfCheck), R.string.selfcheck_title, listOf(ADVANCED, R.string.settings_recovery), SettingsRequirement.Recovery),
        SettingsSearchEntry("developer", Page(SettingsPage.Developer), R.string.settings_developer, listOf(ADVANCED, R.string.settings_developer_group), SettingsRequirement.Developer),
        SettingsSearchEntry("device_directories", Page(SettingsPage.DeviceDirectories), R.string.device_dirs_entry, listOf(ADVANCED, R.string.settings_developer), SettingsRequirement.Developer),
        SettingsSearchEntry("run_stats", Page(SettingsPage.RunStats), R.string.metrics_title, listOf(ADVANCED, R.string.settings_developer), SettingsRequirement.Developer),
        SettingsSearchEntry("quit", SettingsDestination.Quit, R.string.quit_title, listOf(ADVANCED)),

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
        entries.map { e ->
            SettingsSearchItem(e, text(e.title), e.path.joinToString(PATH_SEPARATOR) { text(it) }, e.aliases.map(text))
        }

    private const val PATH_SEPARATOR = " › "
}
