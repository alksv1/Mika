package com.wangzan101.mika.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ThemeAccent
import com.wangzan101.mika.settings.ThemeMode

/** 动效:时长、弹簧物理和曲线。系统关了动画时([MikaTheme.reduceMotion])直接到位。 */
object MikaMotion {
    const val PAGE_MS = 220
    const val SWITCH_MS = 200
    const val EFFECTS_MS = 180
    const val THEME_MS = 220
    const val EXIT_MS = 150
    /** 减速曲线:起步快、落地慢。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    fun <T> page(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(PAGE_MS, easing = Decelerate)
    fun <T> switch(reduceMotion: Boolean): AnimationSpec<T> = expressive(reduceMotion)

    /** 轻触按压、圆点弹出、角标等干脆微弹。 */
    fun <T> tactile(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.65f, stiffness = 900f)

    /** 开关、分段滑块、长按浮起、小窗等 M3 Expressive 弹性交互。 */
    fun <T> expressive(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 520f)

    /** 抽屉、面板、容器展开等空间位移。 */
    fun <T> spatial(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.84f, stiffness = 420f)

    fun spatialOffset(reduceMotion: Boolean): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.88f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)

    fun spatialSize(reduceMotion: Boolean): FiniteAnimationSpec<IntSize> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.84f, stiffness = 450f, visibilityThreshold = IntSize.VisibilityThreshold)

    /** 颜色与透明度变化只用无过冲曲线。 */
    fun <T> effects(reduceMotion: Boolean, ms: Int = EFFECTS_MS): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(ms, easing = EmphasizedDecelerate)
}

/** 系统「移除动画」(动画时长缩放为 0)时为真。 */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

val LocalReduceMotion = staticCompositionLocalOf { false }

object MikaTheme {
    val colors: MikaColors
        @Composable @ReadOnlyComposable get() = LocalMikaColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * 设置里的外观:跟随系统时看系统,一直浅色/深色时照设置。设置一改(设置页或任务里改的)当场换,不重建界面,
 * 所以页面、输入、滚动位置都不丢。
 */
@Composable
fun appearanceDark(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val mode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** 设置里的强调色(见 [ThemeAccent]);和外观一样,改了当场换、不重建界面。 */
@Composable
fun appearanceAccent(): ThemeAccent {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val accent by remember(flags) { flags.themeAccentChanges() }.collectAsState(flags.themeAccent())
    return accent
}

/** 状态栏、导航栏的图标深浅跟界面一致:界面强制浅色而系统是深色时,图标也得是深色的才看得见。 */
fun ComponentActivity.systemBarsFor(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    // 旧系统上导航栏不能全透明,垫一层半透明的底;取值与 enableEdgeToEdge 默认的一致
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
    )
}

/**
 * 深浅色按设置里的外观([appearanceDark]),强调色按设置里的强调色([appearanceAccent],背景图片要求跟随图片时用图上的主色);
 * 同时把 Material 组件(弹窗、输入框、底部面板)的颜色对上。
 */
@Composable
fun MikaTheme(
    dark: Boolean = appearanceDark(),
    accent: ThemeAccent = appearanceAccent(),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 背景图片设了「强调色跟随图片」时,用图上的主色代替设置里选的强调色
    val imageHue = appearanceSkin().accentHue
    val colors = remember(dark, accent, imageHue) {
        val base = palette(dark, accent)
        if (imageHue == null) base else SkinAccent.tone(imageHue, dark).let { (color, on) -> base.withAccent(color, on) }
    }
    val animatedColors = animatedPalette(colors, reduceMotion)
    val materialColors = remember(animatedColors) { scheme(animatedColors) }
    val layoutDirection = if (LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalMikaColors provides animatedColors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = materialColors) {
            CompositionLocalProvider(LocalContentColor provides animatedColors.ink, content = content)
        }
    }
}

/** 主题色切换（深浅色、强调色、图片主题）时用短促无过冲渐变过渡，关闭动画时直接替换。 */
@Composable
internal fun animatedPalette(target: MikaColors, reduceMotion: Boolean): MikaColors {
    if (reduceMotion) return target
    val spec = MikaMotion.effects<Color>(false, MikaMotion.THEME_MS)
    val bg by animateColorAsState(target.bg, spec, label = "theme-bg")
    val surface by animateColorAsState(target.surface, spec, label = "theme-surface")
    val fill by animateColorAsState(target.fill, spec, label = "theme-fill")
    val ink by animateColorAsState(target.ink, spec, label = "theme-ink")
    val ink2 by animateColorAsState(target.ink2, spec, label = "theme-ink2")
    val ink3 by animateColorAsState(target.ink3, spec, label = "theme-ink3")
    val inkSoft by animateColorAsState(target.inkSoft, spec, label = "theme-inkSoft")
    val hair by animateColorAsState(target.hair, spec, label = "theme-hair")
    val edge by animateColorAsState(target.edge, spec, label = "theme-edge")
    val accent by animateColorAsState(target.accent, spec, label = "theme-accent")
    val accentSoft by animateColorAsState(target.accentSoft, spec, label = "theme-accentSoft")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "theme-onAccent")
    val neutralSoft by animateColorAsState(target.neutralSoft, spec, label = "theme-neutralSoft")
    val answer by animateColorAsState(target.answer, spec, label = "theme-answer")
    val greySoft by animateColorAsState(target.greySoft, spec, label = "theme-greySoft")
    val glass by animateColorAsState(target.glass, spec, label = "theme-glass")
    val glassEdge by animateColorAsState(target.glassEdge, spec, label = "theme-glassEdge")
    val cardShadow by animateColorAsState(target.cardShadow, spec, label = "theme-cardShadow")
    return remember(
        bg, surface, fill, ink, ink2, ink3, inkSoft, hair, edge,
        accent, accentSoft, onAccent, neutralSoft, answer, greySoft,
        glass, glassEdge, cardShadow, target,
    ) {
        MikaColors(
            bg = bg, surface = surface, fill = fill, ink = ink, ink2 = ink2, ink3 = ink3, inkSoft = inkSoft,
            hair = hair, edge = edge, accent = accent, accentSoft = accentSoft, onAccent = onAccent,
            ok = target.ok, okSoft = target.okSoft, warn = target.warn, warnSolid = target.warnSolid, warnSoft = target.warnSoft,
            bad = target.bad, badSoft = target.badSoft, neutralSoft = neutralSoft, answer = answer, greySoft = greySoft,
            glass = glass, glassEdge = glassEdge, cardShadow = cardShadow, floatShadow = target.floatShadow,
            thumb = target.thumb, series = target.series, seriesOther = target.seriesOther, grid = target.grid, isDark = target.isDark,
        )
    }
}

private fun scheme(c: MikaColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceTint = c.surface,
        outline = c.ink3, outlineVariant = c.hair,
        // 出错色上的字不跟着强调色变(深色石墨的强调色上是深色字)
        error = c.bad, onError = Color.White,
    )
}
