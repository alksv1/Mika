package com.wangzan101.mika.task.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.DEEPSEEK_OFF_PEAK_WINDOWS
import com.wangzan101.mika.core.trigger.ExecutionWindow
import com.wangzan101.mika.core.trigger.toPlanDraft
import com.wangzan101.mika.core.trigger.newPlanDraft
import com.wangzan101.mika.i18n.uiLocale
import kotlin.system.measureTimeMillis
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.task.FileTriggerStore
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import com.wangzan101.mika.settings.ui.models.pick
import com.wangzan101.mika.settings.ui.models.use

@RunWith(AndroidJUnit4::class)
class PlanEditorDialogTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private fun show(original: TriggerDefinition? = null, saved: MutableList<TriggerDefinition>) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlanEditorDialog(original, "new-thread", onDismiss = {}, onSave = saved::add, use24Hour = use24Hour)
            }
        }
    }

    private var use24Hour = true

    @Test fun multipleDailyClocksShowThreeOccurrencesAndSurviveEditing() {
        val original = TriggerDefinition("multi", "owner", "每天提醒", System.currentTimeMillis() + 3_600_000,
            calendarTime = "08:00", calendarZone = "Asia/Shanghai", reminderText = "每天提醒")
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)
        // Add two more daily times via the time picker
        compose.onNodeWithTag("plan-editor-clock-add").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-time-ok").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock-add").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-time-ok").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-preview-2").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "schedule357-preview.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("multi", saved.single().id)
        assertEquals("owner", saved.single().threadId)
        assertEquals(listOf("08:00", "09:00", "10:00"), saved.single().calendarClocks())
    }

    @Test fun createsFromVisibleFormWithThreePreviewTimes() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)

        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-preview-0").assertExists()
        compose.onNodeWithTag("plan-editor-interval").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-interval-minutes").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-interval-minutes").performTextInput("90")
        compose.onNodeWithTag("plan-editor-preview-2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        val plan = saved.single()
        assertEquals("检查天气", plan.goal)
        assertEquals("new-thread", plan.threadId)
        assertNotEquals("new-thread", plan.id)
        assertEquals(90 * 60_000L, plan.intervalMs)
    }

    @Test fun editsExistingPlanWithoutChangingOwnership() {
        val original = TriggerDefinition(
            "existing-id", "existing-thread", "旧任务",
            Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            conditionGoal = "已经准备好",
        )
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)

        compose.onNodeWithTag("plan-editor-content").performTextClearance()
        compose.onNodeWithTag("plan-editor-content").performTextInput("新任务")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        val plan = saved.single()
        assertEquals("existing-id", plan.id)
        assertEquals("existing-thread", plan.threadId)
        assertEquals("新任务", plan.goal)
        assertEquals("09:00", plan.calendarTime)
        assertEquals("已经准备好", plan.conditionGoal)
    }

    @Test fun switchingToFixedReminderRemovesCondition() {
        val original = TriggerDefinition(
            "existing-id", "existing-thread", "旧任务",
            Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            conditionGoal = "已经准备好",
        )
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)

        compose.onNodeWithTag("plan-editor-reminder").performClick()
        compose.onNodeWithTag("plan-editor-condition").assertDoesNotExist()
        compose.onNodeWithTag("plan-editor-save").performClick()

        val plan = saved.single()
        assertEquals("旧任务", plan.reminderText)
        assertEquals("旧任务", plan.goal)
        assertNull(plan.conditionGoal)
    }

    @Test fun deepSeekOffPeakCanBeSelectedAndSaved() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-window-off-peak").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals("UTC", saved.single().executionWindowZone)
        assertEquals(DEEPSEEK_OFF_PEAK_WINDOWS, saved.single().executionWindows)
    }

    @Test fun customExecutionWindowCanCrossMidnight() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-window-custom").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-window-zone").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-window-zone").performTextInput("Asia/Shanghai")
        compose.onNodeWithTag("plan-editor-window-start").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-window-start").performTextInput("23:00")
        compose.onNodeWithTag("plan-editor-window-end").performTextClearance()
        compose.onNodeWithTag("plan-editor-window-end").performTextInput("03:00")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(ExecutionWindow(23 * 60, 3 * 60)), saved.single().executionWindows)
        assertEquals("Asia/Shanghai", saved.single().executionWindowZone)
        assertEquals(saved.single().executionWindowZone, saved.single().toPlanDraft().zone)
    }

    /**
     * 新建(或复制出来)的一次性计划时间已过:不让保存,提示可改到明天同一时间,改完能保存。
     * 只对新时间把关:编辑已有计划而时间没动时,核心特意放行(见下一条),所以这里用新建的草稿。
     */
    @Test fun pastOneShotTimeStaysInEditorUntilMovedToTomorrow() {
        val past = newPlanDraft(System.currentTimeMillis() - 3 * 3_600_000).copy(content = "测试")
        val saved = mutableListOf<TriggerDefinition>()
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlanEditorDialog(null, "new-thread", onDismiss = {}, onSave = saved::add, initialDraft = past, use24Hour = true)
            }
        }
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-error").performScrollTo().assertExists()
        assertTrue(saved.isEmpty())

        compose.onNodeWithTag("plan-editor-use-tomorrow").performScrollTo().performClick()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        val plan = saved.single()
        assertTrue(plan.nextAtMs > System.currentTimeMillis())
        assertEquals("new-thread", plan.threadId)
    }

    /** 编辑一条已过时的一次性计划、时间没改:照常能存(核心对「时间未改的编辑」不查是否在将来),线程不变。 */
    @Test fun editingPastOneShotWithoutChangingTimeStillSaves() {
        val past = System.currentTimeMillis() - 3_600_000
        val original = TriggerDefinition("past-id", "past-thread", "测试", past)
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)
        compose.onNodeWithTag("plan-editor-use-tomorrow").performScrollTo().assertExists()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        val plan = saved.single()
        assertEquals(past, plan.nextAtMs)
        assertEquals("past-thread", plan.threadId)
    }

    /** 快捷时间从点下去那一刻算:「15 分钟后」落在现在之后 14～16 分钟。 */
    @Test fun quickChipUsesTheTimeOfTheTap() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        val before = System.currentTimeMillis()
        compose.onNodeWithTag("plan-quick-15m").performScrollTo().performClick()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        val delta = saved.single().nextAtMs - before
        assertTrue("$delta", delta in 14 * 60_000L..16 * 60_000L)
    }

    /** 新增时刻不用打字:默认从最后一个时刻往后一小时,加完点叉还能删掉。 */
    @Test fun clockChipsAddAndRemoveTimesWithoutTyping() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock-add").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-ok").performClick()
        compose.onNodeWithTag("plan-editor-clock-0").assertTextContains("09:00", substring = true)
        compose.onNodeWithTag("plan-editor-clock-1").assertTextContains("10:00", substring = true)
        compose.onNodeWithTag("plan-editor-clock-remove-1").performScrollTo().performClick()
        compose.onNodeWithTag("plan-editor-clock-1").assertDoesNotExist()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("09:00"), saved.single().calendarClocks())
    }

    /** 系统是 12 小时制时:中文把「上午/下午」滚轮放在小时前面,选「下午 8:30」存成 20:30。 */
    @Test fun twelveHourPickerFollowsTheLanguageAndSavesTwentyFourHourClock() {
        use24Hour = false
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock-0").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-hour").assertDoesNotExist()
        val period = compose.onNodeWithTag("plan-time-ampm").fetchSemanticsNode().boundsInRoot
        val hour = compose.onNodeWithTag("plan-time-hour12").fetchSemanticsNode().boundsInRoot
        assertTrue(period.left < hour.left)
        compose.onNodeWithTag("plan-time-ampm-1").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-hour12-7").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-minute-30").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-ok").performClick()
        compose.onNodeWithTag("plan-editor-clock-0").assertTextContains("下午8:30", substring = true)
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("20:30"), saved.single().calendarClocks())
    }

    /** 时区可按偏移搜:「+9」只列 +9 的时区,选东京后计划存成东京时区。 */
    @Test fun timezonePickerSearchesByOffsetAndSavesTheZone() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        // 先在测试线程上把这门语言的时区索引建好(编辑器打开时也会在后台预热同一份),
        // 选择器打开时直接拿到现成索引,不再靠超时去等后台线程;顺便把真机上的建索引耗时记进日志。
        val buildMs = measureTimeMillis { PlanZoneIndex.get(uiLocale()) }
        Log.i("PlanEditorDialogTest", "zone index ready in $buildMs ms")
        compose.onNodeWithTag("plan-editor-zone").performScrollTo().performClick()
        compose.onNodeWithTag("plan-zone-list").assertExists()
        compose.onNodeWithTag("plan-zone-search").performTextInput("+9")
        compose.onNodeWithTag("plan-zone-Europe/London").assertDoesNotExist()
        // 列表是懒加载的,东京不一定在首屏:按 key 滚过去再点。
        compose.onNodeWithTag("plan-zone-list").performScrollToKey("Asia/Tokyo")
        compose.onNodeWithTag("plan-zone-Asia/Tokyo").performClick()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("Asia/Tokyo", saved.single().calendarZone)
    }

    @Test fun swipingWheelSavesTheCenteredMinuteAndCancelKeepsIt() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock-0").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-minute").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val center = compose.onNodeWithTag("plan-time-minute").fetchSemanticsNode().boundsInRoot.center.y
        val minute = (0..59).minBy { index ->
            kotlin.math.abs(compose.onNodeWithTag("plan-time-minute-$index").fetchSemanticsNode().boundsInRoot.center.y - center)
        }
        assertTrue(minute > 0)
        compose.onNodeWithTag("plan-time-ok").performClick()
        val selected = "09:%02d".format(java.util.Locale.ROOT, minute)
        compose.onNodeWithTag("plan-editor-clock-0").assertTextContains(selected, substring = true)
        compose.onNodeWithTag("plan-editor-clock-0").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-hour-23").performScrollTo().performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("plan-editor-clock-0").assertTextContains(selected, substring = true)
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(selected), saved.single().calendarClocks())
    }

    @Test fun editingWeeklyClockToDuplicateKeepsOneSavedTime() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-weekly").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock-add").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-ok").performClick()
        compose.onNodeWithTag("plan-editor-clock-1").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-hour-9").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-minute-0").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-ok").performClick()
        compose.onNodeWithTag("plan-editor-clock-0").assertTextContains("09:00", substring = true)
        compose.onNodeWithTag("plan-editor-clock-1").assertDoesNotExist()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("09:00"), saved.single().calendarClocks())
        assertEquals(1, saved.single().calendarDays.size)
    }

    /** 首次执行走日期 + 时间两块点按区:选中的 23:59 要写回草稿并显示在时间块上。 */
    @Test fun dateAndTimePickersWriteTheFirstRunTime() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-first-date-pick").performClick()
        compose.onNodeWithTag("plan-date-picker").assertExists()
        compose.onNodeWithTag("plan-date-ok").performClick()
        compose.onNodeWithTag("plan-editor-first-time-pick").performClick()
        compose.onNodeWithTag("plan-time-hour-23").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-minute-59").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-ok").performClick()

        compose.onNodeWithTag("plan-editor-first-time-pick").assertTextContains("23:59", substring = true)
    }

    @Test fun clockPickerWorksInDarkRtlWithLargeText() {
        val saved = mutableListOf<TriggerDefinition>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale = 1.5f),
            ) {
                MikaTheme(dark = true, reduceMotion = true) {
                    PlanEditorDialog(null, "new-thread", onDismiss = {}, onSave = saved::add, use24Hour = true)
                }
            }
        }
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock-add").performScrollTo().performClick()
        val hour = compose.onNodeWithTag("plan-time-hour").fetchSemanticsNode().boundsInRoot
        val minute = compose.onNodeWithTag("plan-time-minute").fetchSemanticsNode().boundsInRoot
        assertTrue(hour.left < minute.left)
        compose.onNodeWithTag("plan-time-hour-23").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-minute-59").performScrollTo().performClick()
        compose.onNodeWithTag("plan-time-ok").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plan-editor-clock-1").assertTextContains("23:59", substring = true)
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("09:00", "23:59"), saved.single().calendarClocks())
    }

    @Test fun plansPageShowsExecutionWindowDeferredTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val goal = "执行时段测试 $id"
        val plan = TriggerDefinition(
            id, "window-list-$id", goal, Instant.parse("2099-01-01T02:00:00Z").toEpochMilli(),
            executionWindowZone = "UTC", executionWindows = listOf(ExecutionWindow(10 * 60, 11 * 60)),
        )
        // 和计划页同一个格式:界面语言的日期 + 系统的 12/24 小时制
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        fun format(ms: Long) = formatPlanTime(ms, is24Hour)
        try {
            TriggerRuntime.schedule(context, plan)
            compose.setContent {
                MikaTheme(dark = false, reduceMotion = true) {
                    PlansScreen(context, onBack = {}, onOpenConversation = {})
                }
            }

            compose.onNodeWithText(format(plan.executionAtOrAfter(plan.nextAtMs)), substring = true)
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(format(plan.nextAtMs), substring = true).assertDoesNotExist()
        } finally {
            TriggerRuntime.remove(context, id)
        }
    }

    @Test fun plansPageDoesNotShowPastDeferredTimeForDueOneShot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val goal = "到期执行时段测试 $id"
        val before = System.currentTimeMillis()
        val plan = TriggerDefinition(
            id, "due-window-list-$id", goal, before - 3_600_000,
            executionWindowZone = "UTC", executionWindows = listOf(ExecutionWindow(0, 24 * 60)),
        )
        // 和计划页同一个格式:界面语言的日期 + 系统的 12/24 小时制
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        fun format(ms: Long) = formatPlanTime(ms, is24Hour)
        try {
            TriggerRuntime.schedule(context, plan)
            compose.setContent {
                MikaTheme(dark = false, reduceMotion = true) {
                    PlansScreen(context, onBack = {}, onOpenConversation = {})
                }
            }
            val currentTimes = listOf(before, System.currentTimeMillis()).map(::format).distinct()

            assertTrue(currentTimes.any { text ->
                runCatching { compose.onNodeWithText(text, substring = true).assertExists() }.isSuccess
            })
            compose.onNodeWithText(format(plan.nextAtMs), substring = true).assertDoesNotExist()
        } finally {
            TriggerRuntime.remove(context, id)
        }
    }

    @Test fun plansPageHasManualCreationEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlansScreen(context, onBack = {}, onOpenConversation = {})
            }
        }

        compose.onNodeWithContentDescription("新建定时任务").performClick()
        compose.onNodeWithTag("plan-editor-content").assertIsDisplayed()
    }

    @Test fun plansPageShowsRecordedRunsInTheSelectedPlansMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val goal = "执行记录测试 $id"
        val now = System.currentTimeMillis()
        val store = FileTriggerStore(File(context.filesDir, "triggers-v3.json"))
        try {
            store.upsert(TriggerDefinition(id, "history-$id", goal, now + 3_600_000, intervalMs = 60_000))
            val earlier = checkNotNull(store.claimNow(id, now - 3_600_000))
            store.mark(earlier.key, TriggerWindowState.Failed)
            val latest = checkNotNull(store.claimNow(id, now))
            store.mark(latest.key, TriggerWindowState.Delivered)
            compose.setContent {
                MikaTheme(dark = false, reduceMotion = true) {
                    PlansScreen(context, onBack = {}, onOpenConversation = {})
                }
            }

            compose.onNodeWithText(goal).performScrollTo().performClick()
            compose.onNodeWithText("执行记录").performClick()
            compose.onNodeWithText("知道了").assertIsDisplayed()
            compose.onNodeWithText("没成功").assertIsDisplayed()
        } finally {
            TriggerRuntime.remove(context, id)
        }
    }

    @Test fun copyFromPlanMenuSavesAnIndependentPausedPlan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val goal = "复制计划测试 $id"
        val original = TriggerDefinition(id, "copy-test-$id", goal, System.currentTimeMillis() + 86_400_000)
        try {
            TriggerRuntime.schedule(context, original)
            compose.setContent {
                MikaTheme(dark = false, reduceMotion = true) {
                    PlansScreen(context, onBack = {}, onOpenConversation = {})
                }
            }
            compose.onNodeWithText(goal).performScrollTo().performClick()
            compose.onNodeWithText("复制为新计划").performClick()
            compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

            val plans = TriggerRuntime.list(context).filter { it.threadId == original.threadId }
            assertEquals(original, plans.single { it.id == id })
            val copy = plans.single { it.id != id }
            assertEquals(goal, copy.goal)
            assertEquals(false, copy.enabled)
        } finally {
            TriggerRuntime.list(context).filter { it.threadId == original.threadId }
                .forEach { TriggerRuntime.remove(context, it.id) }
        }
    }
}
