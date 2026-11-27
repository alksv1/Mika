package com.wangzan101.mika.cap.phone.shell

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.Keep
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * 只读读屏的 shell 入口:电脑经已授权的 ADB 运行
 * `CLASSPATH=<Mika base.apk> app_process /system/bin com.wangzan101.mika.shell.ScreenInspectMain`
 * (稳定入口,只转到这里;CLI 只认那个全名),
 * 标准输出打一行 JSON(各显示屏的窗口和控件树,每个窗口带 `display_id`)。
 *
 * 只在 shell 身份(uid 2000)下运行。`UiAutomation` 一律用 [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES] 连接:
 * 默认的连接(flags = 0,也就是系统自带的 `uiautomator dump`)会把 Mika 等其他无障碍服务断开。
 * 拿不到带 flags 的连接方式时报 `unsupported`,绝不退回默认连接。不点击、不输入、不改设置;
 * 密码框不输出文字和描述,控件数和深度有上限(见 [ScreenInspectTree])。整个过程不超过 [WATCHDOG_MS]。
 * 不写日志:屏幕内容只出现在这一行输出里。
 */
@Keep
object ScreenInspect {
    private const val WATCHDOG_MS = 15_000L
    private const val IDLE_MS = 300L
    private const val IDLE_TIMEOUT_MS = 2_000L

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != ShellContext.SHELL_UID) {
            finish(failure("shell_required"), 2)
        }
        val done = AtomicBoolean(false)
        // binder 调用卡住(系统忙、应用不回话)时也按时退出,不留进程
        Thread {
            try {
                Thread.sleep(WATCHDOG_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (done.compareAndSet(false, true)) finish(failure("timeout"), 3)
        }.apply { isDaemon = true; name = "mika-screen-inspect-watchdog" }.start()
        val thread = HandlerThread("mika-screen-inspect").apply { start() }
        val result = try {
            inspect(thread.looper)
        } catch (e: Throwable) {
            // 异常文字可能带着屏幕内容,只报类别;个别系统缺方法时是 LinkageError,也算读不了
            failure("screen_unavailable")
        } finally {
            thread.quitSafely()
        }
        val ok = (result["status"] as? JsonPrimitive)?.content == "ok"
        if (done.compareAndSet(false, true)) finish(result, if (ok) 0 else 1)
        // 看门狗已经抢先输出,正在退出
        Thread.sleep(Long.MAX_VALUE)
    }

    private fun inspect(looper: Looper): JsonObject {
        val access = UiAutomationAccess.detect() ?: return failure("unsupported")
        val automation = try {
            access.create(looper)
        } catch (e: Throwable) {
            return failure("unsupported")
        }
        try {
            try {
                access.connect(automation)
            } catch (e: Exception) {
                // 另一个 UiAutomation 正连着,或系统拒绝;不换成会压制无障碍服务的默认连接重试
                return failure("connect_failed")
            }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            try {
                automation.waitForIdle(IDLE_MS, IDLE_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                // 一直在动的界面也照读当前的样子
            }
            val budget = InspectBudget()
            val displays = windowsByDisplay(automation)
            val windows = buildJsonArray {
                // 获得焦点的、活动的窗口先读,总预算不够时截掉的是其余窗口
                val all = displays.flatMap { (displayId, list) -> list.map { displayId to it } }
                for ((displayId, window) in ScreenInspectTree.windowOrder(all, { it.second.isFocused }, { it.second.isActive })) {
                    add(window(displayId, window, budget))
                }
            }
            return buildJsonObject {
                put("status", "ok")
                put("display_ids", buildJsonArray { for ((id, _) in displays) add(id) })
                put("accessibility_services_suppressed", false)
                put("captured_at_uptime_ms", SystemClock.uptimeMillis())
                put("node_count", budget.nodes)
                put("truncated", budget.truncated)
                put("truncated_by", buildJsonArray { for (reason in budget.truncatedBy) add(reason) })
                put("windows", windows)
            }
        } finally {
            access.disconnect(automation)
        }
    }

    /** 安卓 11(API 30)起有公开的 getWindowsOnAllDisplays,能读到 Mika 的虚拟屏;更早只有默认屏。 */
    private fun windowsByDisplay(automation: UiAutomation): List<Pair<Int, List<AccessibilityWindowInfo>>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val all = automation.windowsOnAllDisplays
            (0 until all.size()).map { all.keyAt(it) to all.valueAt(it).orEmpty() }
        } else {
            listOf(Display.DEFAULT_DISPLAY to automation.windows.orEmpty())
        }

    private fun window(displayId: Int, window: AccessibilityWindowInfo, budget: InspectBudget): JsonObject {
        val rect = Rect().also(window::getBoundsInScreen)
        return buildJsonObject {
            put("display_id", displayId)
            put("id", window.id)
            put("type", window.type)
            val title = ScreenInspectTree.plain(window.title, budget)
            put("title", title)
            put("bounds", ScreenInspectTree.bounds(intArrayOf(rect.left, rect.top, rect.right, rect.bottom)))
            put("active", window.isActive)
            put("focused", window.isFocused)
            // 窗口本身也算进总输出;超了就只留窗口信息,不读控件树
            val room = budget.charge(ScreenInspectTree.NODE_OVERHEAD + ((title as? JsonPrimitive)?.contentOrNull?.length ?: 0))
            val tree = if (room) window.root?.let { ScreenInspectTree.node(A11yInspectNode(it), budget) } else null
            put("root", tree ?: JsonNull)
        }
    }

    private fun failure(code: String) = buildJsonObject {
        put("status", "error")
        put("code", code)
    }

    private fun finish(result: JsonObject, code: Int): Nothing {
        println(result.toString())
        System.out.flush()
        // app_process 的 binder 线程和 HandlerThread 不会自己结束
        exitProcess(code)
    }
}

/**
 * 在 shell 进程里建一个 [UiAutomation] 并带 flags 连上。系统没有给 shell 进程的公开构造方法,
 * 这几处都是隐藏 API,逐个按能力探测,缺了就整个不可用(由调用方报 `unsupported`),不改走别的连接方式:
 * - `android.app.UiAutomationConnection` 无参构造、`android.app.IUiAutomationConnection`:安卓 4.3(API 18)起;
 * - `UiAutomation(Looper, IUiAutomationConnection)`:API 18 起,AOSP 里 `uiautomator` 的 shell 包装就这样建(安卓 10–15 的源码里都在,
 *   各版本真机仍需验收);找不到时试安卓 14(API 34)加的 `UiAutomation(Context, IUiAutomationConnection)`,
 *   Context 的主线程换成我们的 [Looper](连接回调排在这条线程上,不能用没在转的主线程);
 * - `connect(int flags)`:安卓 7.0(API 24)起;Mika 最低 API 29,正常都有;没有时绝不调无参的 `connect()`(会压制无障碍服务);
 * - `disconnect()`:API 18 起。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
private class UiAutomationAccess(
    private val connection: Class<*>,
    private val connectionInterface: Class<*>,
    private val connect: java.lang.reflect.Method,
    private val disconnect: java.lang.reflect.Method?,
) {
    fun create(looper: Looper): UiAutomation {
        val instance = connection.getConstructor().newInstance()
        runCatching { UiAutomation::class.java.getConstructor(Looper::class.java, connectionInterface) }.getOrNull()
            ?.let { return it.newInstance(looper, instance) as UiAutomation }
        val byContext = UiAutomation::class.java.getConstructor(Context::class.java, connectionInterface)
        // ShellContext 自建 ActivityThread 时要有主线程 Looper;这里在 main 线程上
        @Suppress("DEPRECATION")
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val context = object : ContextWrapper(ShellContext.create()) {
            override fun getMainLooper(): Looper = looper
        }
        return byContext.newInstance(context, instance) as UiAutomation
    }

    fun connect(automation: UiAutomation) {
        try {
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    fun disconnect(automation: UiAutomation) {
        runCatching { disconnect?.invoke(automation) }
    }

    companion object {
        fun detect(): UiAutomationAccess? = runCatching {
            val connection = Class.forName("android.app.UiAutomationConnection")
            val connectionInterface = Class.forName("android.app.IUiAutomationConnection")
            val connect = UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
            val disconnect = runCatching { UiAutomation::class.java.getMethod("disconnect") }.getOrNull()
            UiAutomationAccess(connection, connectionInterface, connect, disconnect)
        }.getOrNull()
    }
}

/** 把 [AccessibilityNodeInfo] 交给 [ScreenInspectTree]。 */
internal class A11yInspectNode(private val info: AccessibilityNodeInfo) : InspectNode {
    override val packageName: CharSequence? get() = info.packageName
    override val className: CharSequence? get() = info.className
    override val resourceId: String? get() = info.viewIdResourceName
    override val text: CharSequence? get() = info.text
    override val description: CharSequence? get() = info.contentDescription
    override val isPassword: Boolean get() = info.isPassword
    override val bounds: IntArray get() = Rect().also(info::getBoundsInScreen).let { intArrayOf(it.left, it.top, it.right, it.bottom) }
    override val isClickable: Boolean get() = info.isClickable
    override val isEditable: Boolean get() = info.isEditable
    override val isEnabled: Boolean get() = info.isEnabled
    override val isVisible: Boolean get() = info.isVisibleToUser
    override val childCount: Int get() = info.childCount
    override fun child(index: Int): InspectNode? = info.getChild(index)?.let(::A11yInspectNode)

    @Suppress("DEPRECATION") // API 33 起回收是空操作;更早的系统要回收
    override fun release() = info.recycle()
}
