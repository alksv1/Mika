package com.wangzan101.mika.core.sensitive

/**
 * 用户自己标为敏感的 App 名单。默认空:名单为空时整个功能不启用,任务行为不变。
 * 只存包名,不存屏幕内容,也不进模型上下文。
 */
data class SensitiveAppsState(
    val packages: Set<String> = emptySet(),
    /** 设置文件读不出来时为 false:按空名单处理,不因为读不出就拦住任务。 */
    val available: Boolean = true,
) {
    /** 名单为空(或读不出)时功能不启用。 */
    val enabled: Boolean get() = available && packages.isNotEmpty()

    /** [packageName] 在这个名单里;空包名、读不出设置时都不算。 */
    fun isSensitive(packageName: String?): Boolean =
        enabled && !packageName.isNullOrBlank() && packageName in packages
}

/** 每次检查都读最新名单,不在任务开始时冻结。 */
fun interface SensitiveAppsPolicy {
    fun current(): SensitiveAppsState

    companion object {
        val NONE = SensitiveAppsPolicy { SensitiveAppsState() }
    }
}

/** 一次检查要拿这个 App 做什么;被拒绝后哪些动作仍然放行见 [Escape]。 */
enum class SensitiveAction {
    /** 按目标打开:openApp、分享、指定了 App 的链接。 */
    Open,
    /** 读屏、截图、读可见文字。 */
    Read,
    /** 在当前屏上点、打字、滑动等。 */
    Act,
    /** 离开敏感 App 的动作:返回、回主屏。被拒绝后仍放行,免得任务被困在里面。 */
    Escape,
}

/**
 * 任务这一侧对敏感名单的检查。端口在每次要打开/读/写一个 App 之前和之后调用 [check];
 * 返回 false 表示用户没有允许,端口抛 [SensitiveAppsDenied],这次工具不执行(或结果不返回)。
 * [active] 为 false(名单为空)时端口连窗口探针都不用做。
 */
fun interface SensitiveAppsGuard {
    suspend fun check(packageName: String?, action: SensitiveAction): Boolean

    /** 名单为空时为 false,端口据此跳过多余的窗口核对。 */
    val active: Boolean get() = true

    companion object {
        /** 名单为空时的默认关卡:什么也不查。 */
        val NONE: SensitiveAppsGuard = object : SensitiveAppsGuard {
            override val active: Boolean get() = false
            override suspend fun check(packageName: String?, action: SensitiveAction): Boolean = true
        }
    }
}

/**
 * 敏感 App 关卡拒绝了一个动作。和 [com.wangzan101.mika.core.access.AppAccessDenied] 一样,
 * 这是一个可恢复的工具错误,不是任务异常:模型据此换一条路或问用户。
 */
class SensitiveAppsDenied(val code: String, val packageName: String?) : RuntimeException(
    when (code) {
        TIMEOUT ->
            "The user did not answer in time, so this app was not used. The action was not performed. " +
                "Do not retry it in this run. Leave the app if you are in it (back or home) and continue another way, or ask the user."
        else ->
            "The user did not allow Mika to use this app, so the action was not performed. " +
                "Do not retry it, read it again, or try to bypass this with shell or another app. " +
                "Leave the app if you are in it (back or home), continue with another route, or ask the user."
    },
) {
    companion object {
        const val DENIED = "sensitive_app_denied"
        const val TIMEOUT = "sensitive_app_timeout"

        /** 这些错误码要交给模型,不能当成任务异常。 */
        fun isSensitiveCode(code: String): Boolean = code == DENIED || code == TIMEOUT
    }
}
