package com.wangzan101.mika.settings.ui

/**
 * 设置页内部条目的定位标识: 搜索索引([SettingsSearchIndex])
 * 和各设置子页的定位高亮共用这里的常量,避免字符串手写拼错。
 */
object SettingsFocusKey {
    // 首页
    const val PERSONAL_START = "personal_start"

    // 任务运行
    const val AUTONOMOUS = "autonomous"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val POCKET_GUARD = "pocket_guard"
    const val POCKET_COMPLETION_LOCK = "pocket_completion_lock"
    const val MUTE_TARGET = "mute_target"
    const val STEP_LIMIT = "step_limit"
    const val TRIGGER_RUN_LIMIT = "trigger_run_limit"
    const val SANDBOX_BUDGET = "sandbox_budget"
    const val ATTACHMENT_LIMIT = "attachment_limit"
    const val WORKSPACE_LIMIT = "workspace_limit"
    const val TOKEN_BUDGET = "token_budget"
    const val SHOW_TASK_COST = "show_task_cost"
    const val PARALLEL_TASKS = "parallel_tasks"
    const val LOCK_SCREEN_TASKS = "lock_screen_tasks"
    const val LOCK_SCREEN_CREDENTIAL = "lock_screen_credential"
    const val SELF_CONFIG = "self_config"
    const val SELF_OPERATE = "self_operate"
    const val PIP_OPERATE = "pip_operate"
    const val EXTERNAL_SCHEDULE = "external_schedule"
    const val OPERATING_PACE = "operating_pace"

    // 手机操作
    const val ACCESSIBILITY = "accessibility"
    const val SHIZUKU = "shizuku"
    const val SHELL_TRANSPORT = "shell_transport"

    // 语言与外观
    const val APPEARANCE = "appearance"
    const val ACCENT_COLOR = "accent_color"
    const val BACKGROUND_SKIN = "background_skin"
    const val APP_LANGUAGE = "app_language"

    // 悬浮胶囊
    const val CAPSULE_IDLE_OPACITY = "capsule_idle_opacity"
    const val CAPSULE_CARD_SIZE = "capsule_card_size"
    const val CAPSULE_HIDE_AT_EDGE = "capsule_hide_at_edge"
    const val CAPSULE_HIDE_FULLSCREEN = "capsule_hide_fullscreen"
    const val CAPSULE_QUICK_SETTINGS = "capsule_quick_settings"

    // 通知
    const val SCHEDULED_QUIET = "scheduled_quiet"
    const val LIVE_TASK_UPDATES = "live_task_updates"

    // 恢复与修复
    const val RECENTS_EXCLUSION = "recents_exclusion"
    const val RECOVERY_A11Y = "recovery_accessibility"
    const val RECOVERY_BATTERY = "recovery_battery"
    const val RECOVERY_BOOT = "recovery_boot"
    const val RECOVERY_AUTO_WIRELESS = "recovery_auto_wireless"

    // 文件与计算环境
    const val SANDBOX_NETWORK = "sandbox_network"
    const val SANDBOX_USAGE = "sandbox_usage"
    const val SANDBOX_RESET = "sandbox_reset"

    // 连接电脑
    const val MCP_HISTORY = "mcp_history"
    const val REMOTE_DEVICE_ACCESS = "remote_device_access"

    /** 全部页内定位标识,供单测核对索引没有漏标或拼错。 */
    val ALL: Set<String> = setOf(
        PERSONAL_START,
        AUTONOMOUS,
        KEEP_SCREEN_ON,
        POCKET_GUARD,
        POCKET_COMPLETION_LOCK,
        MUTE_TARGET,
        STEP_LIMIT,
        TRIGGER_RUN_LIMIT,
        SANDBOX_BUDGET,
        ATTACHMENT_LIMIT,
        WORKSPACE_LIMIT,
        TOKEN_BUDGET,
        SHOW_TASK_COST,
        PARALLEL_TASKS,
        LOCK_SCREEN_TASKS,
        LOCK_SCREEN_CREDENTIAL,
        SELF_CONFIG,
        SELF_OPERATE,
        PIP_OPERATE,
        EXTERNAL_SCHEDULE,
        OPERATING_PACE,
        ACCESSIBILITY,
        SHIZUKU,
        SHELL_TRANSPORT,
        APPEARANCE,
        ACCENT_COLOR,
        BACKGROUND_SKIN,
        APP_LANGUAGE,
        CAPSULE_IDLE_OPACITY,
        CAPSULE_CARD_SIZE,
        CAPSULE_HIDE_AT_EDGE,
        CAPSULE_HIDE_FULLSCREEN,
        CAPSULE_QUICK_SETTINGS,
        SCHEDULED_QUIET,
        LIVE_TASK_UPDATES,
        RECENTS_EXCLUSION,
        RECOVERY_A11Y,
        RECOVERY_BATTERY,
        RECOVERY_BOOT,
        RECOVERY_AUTO_WIRELESS,
        SANDBOX_NETWORK,
        SANDBOX_USAGE,
        SANDBOX_RESET,
        MCP_HISTORY,
        REMOTE_DEVICE_ACCESS,
    )
}
