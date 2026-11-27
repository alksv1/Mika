package com.wangzan101.mika.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.delay

/** 一次页内定位请求:[id] 对应 [SettingsFocusKey],[nonce] 保证连点同一条也能再次触发。 */
data class FocusTarget(val id: String, val nonce: Long)

/**
 * 协调搜索结果跳转到子页后的定位与高亮:设置页在跳转前写入 [request],
 * 目标行真正进入组合并接手动画后通过 [consume] 消费,避免还没加载出来(如异步读取的块)就被提前清掉。
 */
class FocusTargetHost {
    var current by mutableStateOf<FocusTarget?>(null)
        private set
    private var seq = 0L

    fun request(id: String) {
        current = FocusTarget(id, ++seq)
    }

    fun clear() {
        current = null
    }

    fun consume(target: FocusTarget) {
        if (current == target) current = null
    }
}

val LocalFocusTarget = staticCompositionLocalOf<FocusTargetHost?> { null }

/**
 * 标记一行或一组设置可被搜索定位:当前 [LocalFocusTarget] 指向 [id] 时,
 * 先自动滚进视野(上下多留一点空隙免得贴边),再用强调色淡入高亮并慢慢淡出。
 * 要挂在卡片里面的行或内部容器上,不能挂在有实色背景的 `GroupCard` 外层,否则会被卡片底色盖住。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsFocusTarget(id: String): Modifier {
    val host = LocalFocusTarget.current ?: return this
    val pending = host.current?.takeIf { it.id == id }
    var activeNonce by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pending) {
        if (pending != null) {
            // 先记下 nonce 再消费:不要和动画共用一个以 pending 为 key 的 LaunchedEffect,否则消费后会立刻取消动画
            activeNonce = pending.nonce
            host.consume(pending)
        }
    }
    val highlight = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val reduceMotion = MikaTheme.reduceMotion
    LaunchedEffect(activeNonce) {
        if (activeNonce == 0L) return@LaunchedEffect
        // 等两帧,让首次测量和简约列表延后一帧展开的卡片脚注都排完版再滚
        withFrameNanos { }
        withFrameNanos { }
        val padPx = with(density) { 72.dp.toPx() }
        val rect = if (size.width > 0 && size.height > 0) {
            Rect(0f, -padPx, size.width.toFloat(), size.height.toFloat() + padPx)
        } else null
        requester.bringIntoView(rect)
        if (reduceMotion) {
            // 系统关了动画:直接亮起、停留后直接熄灭,不做淡入淡出
            highlight.snapTo(1f)
            delay(650L)
            highlight.snapTo(0f)
        } else {
            highlight.snapTo(0f)
            highlight.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
            delay(650L)
            highlight.animateTo(0f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
        }
    }
    val colors = MikaTheme.colors
    val peakAlpha = if (colors.isDark) 0.28f else 0.16f
    return this
        .onSizeChanged { size = it }
        .bringIntoViewRequester(requester)
        .drawBehind {
            val a = highlight.value
            if (a > 0f) drawRect(colors.accent.copy(alpha = peakAlpha * a))
        }
}

/**
 * 设置页内部条目的定位标识: 搜索索引([com.wangzan101.mika.settings.ui.SettingsSearchIndex])
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
