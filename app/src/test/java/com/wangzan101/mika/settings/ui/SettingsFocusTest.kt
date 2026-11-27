package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.ui.components.FocusTargetHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 页内定位请求状态机的分配、去重消费与重置。 */
class SettingsFocusTest {
    @Test
    fun requestAssignsIncreasingNoncesEvenForSameId() {
        val host = FocusTargetHost()
        assertNull(host.current)
        host.request("step_limit")
        val first = host.current!!
        assertEquals("step_limit", first.id)

        host.request("step_limit")
        val second = host.current!!
        assertEquals("step_limit", second.id)
        assertNotEquals(first.nonce, second.nonce)
    }

    @Test
    fun consumeClearsOnlyMatchingTargetAndClearResetsCurrent() {
        val host = FocusTargetHost()
        host.request("step_limit")
        val stale = host.current!!
        host.request("token_budget")
        val latest = host.current!!

        host.consume(stale)
        assertEquals(latest, host.current)

        host.consume(latest)
        assertNull(host.current)

        host.request("proxy")
        host.clear()
        assertNull(host.current)
    }
}
