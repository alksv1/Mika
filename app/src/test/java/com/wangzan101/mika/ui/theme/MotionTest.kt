package com.wangzan101.mika.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动效规格:开启系统「移除动画」时全部立即到位;正常模式下弹簧阻尼与无过冲曲线符合预设边界。 */
class MotionTest {
    @Test fun reduceMotionSnapsEverySpec() {
        assertTrue(MikaMotion.page<Float>(true) is SnapSpec<*>)
        assertTrue(MikaMotion.switch<Float>(true) is SnapSpec<*>)
        assertTrue(MikaMotion.tactile<Float>(true) is SnapSpec<*>)
        assertTrue(MikaMotion.expressive<Float>(true) is SnapSpec<*>)
        assertTrue(MikaMotion.spatial<Float>(true) is SnapSpec<*>)
        assertTrue(MikaMotion.spatialOffset(true) is SnapSpec<*>)
        assertTrue(MikaMotion.spatialSize(true) is SnapSpec<*>)
        assertTrue(MikaMotion.effects<Float>(true) is SnapSpec<*>)
    }

    @Test fun springSpecsUseExpectedPhysicsAndThresholds() {
        for (spec in listOf(
            MikaMotion.tactile<Float>(false),
            MikaMotion.expressive<Float>(false),
            MikaMotion.spatial<Float>(false),
            MikaMotion.switch<Float>(false),
        )) {
            assertTrue("Expected SpringSpec but got $spec", spec is SpringSpec<*>)
            val spring = spec as SpringSpec<*>
            assertTrue("Unexpected dampingRatio=${spring.dampingRatio}", spring.dampingRatio in 0.5f..1f)
            assertTrue("Unexpected stiffness=${spring.stiffness}", spring.stiffness > 0f)
        }

        val offsetSpec = MikaMotion.spatialOffset(false)
        assertTrue(offsetSpec is SpringSpec<*>)
        assertEquals(IntOffset.VisibilityThreshold, (offsetSpec as SpringSpec<IntOffset>).visibilityThreshold)

        val sizeSpec = MikaMotion.spatialSize(false)
        assertTrue(sizeSpec is SpringSpec<*>)
        assertEquals(IntSize.VisibilityThreshold, (sizeSpec as SpringSpec<IntSize>).visibilityThreshold)
    }

    @Test fun effectsAndPageUseTweenWithoutOvershoot() {
        val effectsSpec = MikaMotion.effects<Float>(false)
        assertTrue(effectsSpec is TweenSpec<*>)
        effectsSpec as TweenSpec<*>
        assertEquals(MikaMotion.EFFECTS_MS, effectsSpec.durationMillis)
        assertEquals(MikaMotion.EmphasizedDecelerate, effectsSpec.easing)

        val themeSpec = MikaMotion.effects<Float>(false, MikaMotion.THEME_MS)
        assertTrue(themeSpec is TweenSpec<*>)
        assertEquals(MikaMotion.THEME_MS, (themeSpec as TweenSpec<*>).durationMillis)

        val pageSpec = MikaMotion.page<Float>(false)
        assertTrue(pageSpec is TweenSpec<*>)
        pageSpec as TweenSpec<*>
        assertEquals(MikaMotion.PAGE_MS, pageSpec.durationMillis)
        assertEquals(MikaMotion.Decelerate, pageSpec.easing)
    }
}
