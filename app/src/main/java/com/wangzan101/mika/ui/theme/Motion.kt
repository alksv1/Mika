package com.wangzan101.mika.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 轻触觉反馈:走系统原生常量，自动尊重系统的触感反馈开关。
 */
object MikaHaptics {
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun tick(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun toggle(view: View, on: Boolean) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun confirm(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(code)
    }

    fun pop(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_START
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }
}

/**
 * 按下微缩回弹:只改 [graphicsLayer]，不触发重排，与现有 [InteractionSource] 共用不抢手势。
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    targetScale: Float = 0.96f,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = MikaTheme.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduce) targetScale else 1f,
        animationSpec = MikaMotion.tactile(reduce),
        label = "press-scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
