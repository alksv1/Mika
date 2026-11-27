package com.wangzan101.mika.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.wangzan101.mika.ui.theme.MikaTheme

/** App 里的页面。对话是根页面,其余都从它(或从设置)进去,返回时回到进来的那一页。 */
enum class Route { Chat, Personal, Settings, Memory, Plans, Browser, Files, Run, Source, PhoneSetup, Models, Usage, SpeechModel, VoiceModel, ImageModel, Welcome, ModelSetup, About, License, UpdateMirrors, Screens, Teach, Calls, Report, Playback, Persona, VoiceCatalog, RealtimeVoice, VoiceServices, CallMode, VoiceCall, VideoModel, VoicePacks }

/**
 * 页面栈:最底下永远是对话页。[push] 进一页,[back] 回上一页(已在对话页时返回 false,交给系统退出)。
 * 返回键和页面上的返回按钮走同一个 [back],所以两者总是一致。
 */
@Stable
class NavStack(initial: List<Route> = listOf(Route.Chat), fromDrawer: Boolean = false) {
    var stack: List<Route> by mutableStateOf(normalize(initial))
        private set

    /** 这一串页面是从侧栏进去的:退回对话页时要回到打开的侧栏。 */
    private var fromDrawer = fromDrawer && stack.size > 1

    /** 刚从侧栏进去的页面退回到了对话页,侧栏该重新打开;打开后调 [drawerReopened]。 */
    var reopenDrawer: Boolean by mutableStateOf(false)
        private set

    /** 每次外部跳转或系统分享回到对话页时递增,让已展开的侧栏一并收起。 */
    var resetSerial: Int by mutableStateOf(0)
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** 进一页;已经在这一页时不重复压栈。 */
    fun push(route: Route) {
        if (route == Route.Chat) return reset()
        if (current != route) stack = stack + route
    }

    /** 从侧栏进一页:之后一路退回对话页时,侧栏是打开的。 */
    fun pushFromDrawer(route: Route) {
        fromDrawer = true
        push(route)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        if (!canGoBack && fromDrawer) {
            fromDrawer = false
            reopenDrawer = true
        }
        return true
    }

    /** 把 [route] 摆到最上面:它已在栈里就退回到它(上面的页面关掉),不在就压进去。 */
    fun bringToTop(route: Route) {
        if (route == Route.Chat) return reset()
        val at = stack.indexOf(route)
        stack = if (at < 0) stack + route else stack.take(at + 1)
    }

    /** [route] 离开页面栈:在最上面时就是退一页,压在下面时把它抽掉;不在栈里不动。 */
    fun leave(route: Route) {
        if (route == Route.Chat || route !in stack) return
        if (current == route) back() else stack = stack - route
    }

    fun drawerReopened() {
        reopenDrawer = false
    }

    /** 只留对话页和 [route] 两层(首次上手的每一步用)。 */
    fun show(route: Route) {
        fromDrawer = false
        stack = normalize(listOf(route))
    }

    /** 回到对话页(打开某个对话时)。 */
    fun reset() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Chat)
        resetSerial++
    }

    companion object {
        private const val DRAWER_MARK = "fromDrawer"

        private fun normalize(list: List<Route>): List<Route> =
            listOf(Route.Chat) + list.filter { it != Route.Chat }

        val Saver: Saver<NavStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.stack.map(Route::name) + listOfNotNull(DRAWER_MARK.takeIf { _ -> it.fromDrawer })) },
            restore = { names ->
                NavStack(names.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }, fromDrawer = DRAWER_MARK in names)
            },
        )
    }
}

@Composable
fun rememberNavStack(first: Route = Route.Chat): NavStack =
    rememberSaveable(saver = NavStack.Saver) { NavStack(listOf(first)) }

/** 页面切换的动效参数:时长、视差比例(旧页挪四分之一)、曲线。 */
private object NavAnim {
    const val MS = 280
    const val PARALLAX = 4
    val EASE = FastOutSlowInEasing
}

/**
 * 换页时各页的界面状态(滚动位置、输入框、展开的子页……)。规则跟返回栈一致:
 * 页面还压在栈里(比当前页浅)时留着,按返回回去原样恢复;退出栈(返回到更浅的页、同层换成别的页)就丢掉,
 * 下次从入口重新进来是新的一页。[keep] 为真的页面一直留着(对话页、个人助理这类根页面来回切)。
 *
 * 由调用方 remember 在换页动效外面:页面框架(侧栏常驻/抽屉)换形态时 [NavSlide] 会重新组合,状态不能跟着丢。
 */
@Stable
class PageStates internal constructor(
    internal val holder: SaveableStateHolder,
    private val depths: HashMap<String, Int>,
    private val keep: (String) -> Boolean,
) {
    /** 进入 [key] 这一页(层级 [depth]):同层及更深的其他页已出栈,扔掉它们存着的状态。 */
    internal fun enter(key: String, depth: Int) {
        for (gone in stalePages(depths, key, depth, keep)) {
            depths -= gone
            holder.removeState(gone)
        }
        depths[key] = depth
    }

    internal companion object {
        /** 进入 [key]/[depth] 时该丢掉的页面:层级不比它浅、不是它自己、也不是常驻页。 */
        fun stalePages(known: Map<String, Int>, key: String, depth: Int, keep: (String) -> Boolean): List<String> =
            known.filter { (k, d) -> k != key && d >= depth && !keep(k) }.keys.toList()

        val DepthsSaver: Saver<HashMap<String, Int>, ArrayList<String>> = Saver(
            save = { m -> ArrayList(m.map { (k, d) -> "$d:$k" }) },
            restore = { list -> HashMap(list.associate { it.substringAfter(':') to it.substringBefore(':').toInt() }) },
        )
    }
}

@Composable
fun rememberPageStates(keep: (String) -> Boolean = { false }): PageStates {
    val holder = rememberSaveableStateHolder()
    val depths = rememberSaveable(saver = PageStates.DepthsSaver) { HashMap() }
    return remember(holder, depths) { PageStates(holder, depths, keep) }
}

/**
 * 页面切换的滑动推入过渡:[depth] 变深时新页从右滑入、旧页小幅左移(视差),变浅时反向。
 * 同级替换按推入处理;系统关了动画([MikaTheme.reduceMotion])时直接替换。
 * 内容里要用 [targetState] 的值(动画期间旧页还在),别读导航的实时状态。
 *
 * 给了 [states] 时每页的界面状态按 [pageKey] 存进去(规则见 [PageStates]),[pageKey] 相同视为同一页:
 * 只是页面数据变了不重演换页动效,也不会两份同名页同时在组合树里。
 */
@Composable
fun <T> NavSlide(
    targetState: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    states: PageStates? = null,
    pageKey: (T) -> String = { it.toString() },
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduce = MikaTheme.reduceMotion
    if (states != null) {
        val key = pageKey(targetState)
        val level = depth(targetState)
        SideEffect { states.enter(key, level) }
    }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val slide = tween<IntOffset>(NavAnim.MS, easing = NavAnim.EASE)
                if (depth(targetState) >= depth(initialState)) {
                    slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / NavAnim.PARALLAX }
                } else {
                    slideInHorizontally(slide) { -it / NavAnim.PARALLAX } togetherWith slideOutHorizontally(slide) { it }
                }
            }
        },
        label = "nav-slide",
        contentKey = { if (states != null) pageKey(it) else it },
    ) { page ->
        if (states != null) states.holder.SaveableStateProvider(pageKey(page)) { content(page) }
        else content(page)
    }
}
