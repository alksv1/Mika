package com.wangzan101.mika.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** App 里的页面。对话是根页面,其余都从它(或从设置)进去,返回时回到进来的那一页。 */
enum class Route { Chat, Personal, Settings, Memory, Plans, Browser, Files, Run, Source, PhoneSetup, Models, Usage, SpeechModel, VoiceModel, ImageModel, Welcome, ModelSetup, About, License, UpdateMirrors, Screens, Teach, Calls, Report, Playback, Persona, VoiceCatalog, RealtimeVoice, VoiceServices, CallMode, VoiceCall, VideoModel }

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

/** 页面切换的动效参数:视差比例(底层页挪四分之一)及统一退场交接参数。 */
private object NavAnim {
    const val PARALLAX = 4
    const val PUSH_MS = 280
    const val POP_EXIT_MS = 220
    const val POP_ENTER_MS = 240
    /** 预测性返回拖动时的最大水平偏移占屏宽比例。 */
    const val BACK_DRAG_SHIFT = 0.08f
    /** 预测性返回拖动时的最大缩放收缩量(最多缩至 0.94)。 */
    const val BACK_DRAG_SCALE = 0.06f
    /** 预测性返回松手后在拖动方向继续滑出的额外距离比例。 */
    const val BACK_EXIT_SHIFT = 0.34f
    const val BACK_COMMIT_EXIT_MS = 200
    const val BACK_COMMIT_FADE_MS = 160
    const val BACK_COMMIT_ENTER_MS = 220
}

/** 预测性返回松手瞬间冻结的姿态:让退场页从手指松开的位置顺势离场,不再先弹回原位。 */
private data class PredictiveBackPose(val progress: Float, val physicalSign: Float)

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
 * 页面切换的滑动推入过渡:[depth] 变深时新页从右滑入、旧页小幅左移并微缩淡压(视差),变浅时反向且旧页覆盖在返回页上方滑出。
 * 提供 [canGoBack] 与 [onBack] 时自带系统预测性返回手势;系统关了动画([MikaTheme.reduceMotion])时直接替换。
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
    canGoBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduce = MikaTheme.reduceMotion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val keyOf: (T) -> Any? = { if (states != null) pageKey(it) else it }
    val currentKey = keyOf(targetState)
    if (states != null) {
        val key = pageKey(targetState)
        val level = depth(targetState)
        SideEffect { states.enter(key, level) }
    }
    val backProgress = remember { Animatable(0f) }
    var backPhysicalSign by remember { mutableFloatStateOf(if (rtl) -1f else 1f) }
    val frozenBack = remember { mutableStateMapOf<Any?, PredictiveBackPose>() }
    val latestTargetKey by rememberUpdatedState(currentKey)
    val latestOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(currentKey) {
        frozenBack.remove(currentKey)
    }

    val backEnabled = canGoBack && onBack != null
    BackHandler(enabled = reduce && backEnabled) {
        latestOnBack?.invoke()
    }
    PredictiveBackHandler(enabled = !reduce && backEnabled) { progress ->
        val cancelSpring = spring<Float>(dampingRatio = 0.92f, stiffness = 520f)
        var receivedDragEvent = false
        try {
            progress.collect { event ->
                receivedDragEvent = true
                backPhysicalSign = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                backProgress.snapTo(event.progress)
            }
            val p = backProgress.value.coerceIn(0f, 1f)
            val committedKey = latestTargetKey
            val pose = if (receivedDragEvent || p > 0.01f) PredictiveBackPose(p, backPhysicalSign) else null
            if (pose != null) frozenBack[committedKey] = pose
            latestOnBack?.invoke()
            backProgress.snapTo(0f)
            if (pose != null) {
                // 若 onBack 仅弹确认框而未切换页面,平滑弹回原位并清理冻结姿态,避免下次点击返回误用。
                scope.launch {
                    withFrameNanos { }
                    if (latestTargetKey == committedKey &&
                        frozenBack.remove(committedKey) != null &&
                        backProgress.value == 0f &&
                        pose.progress > 0f
                    ) {
                        backProgress.snapTo(pose.progress)
                        backProgress.animateTo(0f, cancelSpring)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            scope.launch { backProgress.animateTo(0f, cancelSpring) }
            throw cancelled
        }
    }
    var prevState by remember { mutableStateOf(targetState) }
    var prevZ by remember { mutableFloatStateOf(0f) }
    val nextZ = remember(currentKey) {
        val z = if (targetState == prevState) prevZ
        else if (depth(targetState) >= depth(prevState)) prevZ + 1f
        else prevZ - 1f
        prevState = targetState
        prevZ = z
        z
    }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val pushMove = tween<IntOffset>(NavAnim.PUSH_MS, easing = MikaMotion.EmphasizedDecelerate)
                val underFade = tween<Float>(220, easing = MikaMotion.EmphasizedDecelerate)
                val underScale = tween<Float>(NavAnim.PUSH_MS, easing = MikaMotion.EmphasizedDecelerate)
                if (depth(targetState) >= depth(initialState)) {
                    (slideInHorizontally(pushMove) { it } togetherWith
                        (slideOutHorizontally(pushMove) { -it / NavAnim.PARALLAX } +
                            scaleOut(underScale, targetScale = 0.96f) +
                            fadeOut(underFade, targetAlpha = 0.78f)))
                        .apply { targetContentZIndex = nextZ }
                } else {
                    val frozen = frozenBack[keyOf(initialState)]
                    if (frozen != null) {
                        // 顺着手指拖动的物理方向继续平滑滑出、微缩并淡出,与松手前的冻结姿态零跳变衔接。
                        val layoutSign = frozen.physicalSign * (if (rtl) -1f else 1f)
                        val exitMove = tween<IntOffset>(NavAnim.BACK_COMMIT_EXIT_MS, easing = MikaMotion.Decelerate)
                        val exitScale = tween<Float>(NavAnim.BACK_COMMIT_EXIT_MS, easing = MikaMotion.Decelerate)
                        val exitFade = tween<Float>(NavAnim.BACK_COMMIT_FADE_MS, easing = MikaMotion.Decelerate)
                        val enterMove = tween<IntOffset>(NavAnim.BACK_COMMIT_ENTER_MS, easing = MikaMotion.EmphasizedDecelerate)
                        val enterScale = tween<Float>(NavAnim.BACK_COMMIT_ENTER_MS, easing = MikaMotion.EmphasizedDecelerate)
                        val enterFade = tween<Float>(180, easing = MikaMotion.EmphasizedDecelerate)
                        ((slideInHorizontally(enterMove) { (-layoutSign * it / 8f).roundToInt() } +
                            scaleIn(enterScale, initialScale = 0.96f) +
                            fadeIn(enterFade, initialAlpha = 0.72f)) togetherWith
                            (slideOutHorizontally(exitMove) { (layoutSign * it * NavAnim.BACK_EXIT_SHIFT).roundToInt() } +
                                scaleOut(exitScale, targetScale = 0.92f) +
                                fadeOut(exitFade)))
                            .apply { targetContentZIndex = nextZ }
                    } else {
                        val popExit = tween<IntOffset>(NavAnim.POP_EXIT_MS, easing = MikaMotion.Decelerate)
                        val popEnter = tween<IntOffset>(NavAnim.POP_ENTER_MS, easing = MikaMotion.EmphasizedDecelerate)
                        val popEnterScale = tween<Float>(NavAnim.POP_ENTER_MS, easing = MikaMotion.EmphasizedDecelerate)
                        val popEnterFade = tween<Float>(200, easing = MikaMotion.EmphasizedDecelerate)
                        ((slideInHorizontally(popEnter) { -it / NavAnim.PARALLAX } +
                            scaleIn(popEnterScale, initialScale = 0.96f) +
                            fadeIn(popEnterFade, initialAlpha = 0.78f)) togetherWith
                            slideOutHorizontally(popExit) { it })
                            .apply { targetContentZIndex = nextZ }
                    }
                }
            }
        },
        label = "nav-slide",
        contentKey = { keyOf(it) },
    ) { state ->
        val stateKey = keyOf(state)
        DisposableEffect(stateKey) {
            onDispose { frozenBack.remove(stateKey) }
        }
        Box(
            Modifier.graphicsLayer {
                val pose = frozenBack[stateKey]
                val p = pose?.progress ?: if (stateKey == latestTargetKey) backProgress.value.coerceIn(0f, 1f) else 0f
                if (p > 0f) {
                    val sign = pose?.physicalSign ?: backPhysicalSign
                    translationX = sign * size.width * NavAnim.BACK_DRAG_SHIFT * p
                    val s = 1f - NavAnim.BACK_DRAG_SCALE * p
                    scaleX = s
                    scaleY = s
                    shape = RoundedCornerShape(24.dp * p)
                    clip = true
                    shadowElevation = if (pose == null) 8.dp.toPx() * p else 0f
                }
            },
        ) {
            if (states != null) {
                states.holder.SaveableStateProvider(pageKey(state)) {
                    this@AnimatedContent.content(state)
                }
            } else {
                this@AnimatedContent.content(state)
            }
        }
    }
}
