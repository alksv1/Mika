package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.run.PauseFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 暂停期间到点的计划:记成「因暂停跳过」,恢复后不补跑;恢复只放行以后的时段。 */
class TriggerPauseTest {
    @Test fun dueWindowWhilePausedIsSkippedAndNotReplayedAfterResume() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("daily", "t", "do", 1_000, intervalMs = 10_000))
        store.upsert(TriggerDefinition("once", "t", "do", 1_000))
        var paused = true
        val sent = mutableListOf<Pair<String, Long>>()
        val engine = TriggerEngine(store, {}, paused = PauseFlag { paused }) { t, w -> sent += t.id to w.dueAtMs }

        engine.fire(1_000)
        assertTrue(sent.isEmpty())
        val skipped = store.windows()
        assertEquals(2, skipped.size)
        assertTrue(skipped.all { it.state == TriggerWindowState.Skipped && it.reason == PauseFlag.REASON })

        paused = false
        // 恢复后同一时刻再响一次(闹钟重排、进程重启后补 fire):跳过的时段不再派发
        engine.fire(1_000)
        engine.fire(5_000)
        assertTrue(sent.isEmpty())
        // 一次性计划的时段已经记过账,恢复也不会再跑
        assertEquals(false, store.get("once")!!.enabled)

        // 恢复以后的新时段照常跑
        engine.fire(11_000)
        assertEquals(listOf("daily" to 11_000L), sent)
    }

    @Test fun idleOnlyPlanIsSkippedWhilePausedInsteadOfWaitingForResume() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("idle", "t", "do", 1_000, idleOnly = true))
        var paused = true
        val sent = mutableListOf<String>()
        val engine = TriggerEngine(store, {}, isIdle = { false }, paused = PauseFlag { paused }) { t, _ -> sent += t.id }

        engine.fire(1_000)
        assertEquals(TriggerWindowState.Skipped, store.windows().single().state)
        paused = false
        engine.fire(2_000)
        assertTrue(sent.isEmpty())
    }
}
