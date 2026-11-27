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

/** 一次页内定位请求:[id] 是调用方约定的条目标识(设置里见 `SettingsFocusKey`),[nonce] 保证连点同一条也能再次触发。 */
data class FocusTarget(val id: String, val nonce: Long)

/**
 * 协调「跳到某页的某一条」的定位与高亮(目前用于设置搜索):跳转前写入 [request],
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
fun Modifier.focusHighlightTarget(id: String): Modifier {
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
