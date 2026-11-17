package com.wangzan101.mika.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * 列表上滑脱离末尾后浮出的圆形磨砂玻璃「回到底部」按钮。
 */
@Composable
fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = MikaTheme.reduceMotion,
    buttonSize: Dp = MikaSize.IconButton,
    iconSize: Dp = 20.dp,
    tag: String = "scroll-to-bottom",
) {
    val c = MikaTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(150)) else fadeIn(tween(180)) + scaleIn(tween(180, easing = MikaMotion.Decelerate), initialScale = 0.85f),
        exit = if (reduceMotion) fadeOut(tween(120)) else fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
    ) {
        val label = stringResource(R.string.chat_scroll_to_bottom)
        GlassSurface(
            modifier = Modifier.size(buttonSize).testTag(tag),
            backdrop = backdrop,
            shape = CircleShape,
        ) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Down, label, Modifier.size(iconSize), tint = c.ink)
            }
        }
    }
}

/** 滚回列表真正的末尾：长消息超过一屏时补齐到最后一项底部，动画途中若新增了行再追一次。 */
internal suspend fun scrollListToBottom(listState: LazyListState, reduceMotion: Boolean) {
    repeat(2) {
        val end = listState.layoutInfo.totalItemsCount - 1
        if (end < 0) return
        if (reduceMotion) listState.scrollToItem(end) else listState.animateScrollToItem(end)
        val layout = listState.layoutInfo
        if (layout.totalItemsCount - 1 != end) return@repeat
        val tail = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return@repeat
        val remaining = tail.offset + tail.size - (layout.viewportEndOffset - layout.afterContentPadding)
        if (remaining > 0) {
            if (reduceMotion) listState.scrollBy(remaining.toFloat())
            else listState.animateScrollBy(remaining.toFloat())
        }
        if (!listState.canScrollForward) return
    }
}
