package com.wangzan101.mika.core.sensitive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 敏感名单:默认空、精确匹配、读不出不拦、拒绝码映射。 */
class SensitiveAppsTest {
    @Test fun emptyListNeverMatches() {
        val state = SensitiveAppsState()
        assertFalse(state.enabled)
        assertFalse(state.isSensitive("com.example.bank"))
        assertFalse(state.isSensitive(null))
    }

    @Test fun unavailableListNeverMatchesEvenIfItCarriesPackages() {
        val state = SensitiveAppsState(setOf("com.example.bank"), available = false)
        assertFalse(state.enabled)
        assertFalse(state.isSensitive("com.example.bank"))
    }

    @Test fun exactPackageMatchOnly() {
        val state = SensitiveAppsState(setOf("com.example.bank"))
        assertTrue(state.enabled)
        assertTrue(state.isSensitive("com.example.bank"))
        assertFalse(state.isSensitive("com.example.bank.copy"))
        assertFalse(state.isSensitive("com.example"))
        assertFalse(state.isSensitive(""))
        assertFalse(state.isSensitive("   "))
        assertFalse(state.isSensitive(null))
    }

    @Test fun policyNoneIsEmpty() {
        assertFalse(SensitiveAppsPolicy.NONE.current().enabled)
        assertFalse(SensitiveAppsPolicy.NONE.current().isSensitive("com.example.bank"))
    }

    @Test fun noneGuardAllowsEverythingAndIsInactive() = runBlocking {
        assertFalse(SensitiveAppsGuard.NONE.active)
        assertTrue(SensitiveAppsGuard.NONE.check("com.example.bank", SensitiveAction.Open))
        assertTrue(SensitiveAppsGuard.NONE.check("com.example.bank", SensitiveAction.Read))
        assertTrue(SensitiveAppsGuard.NONE.check(null, SensitiveAction.Act))
    }

    @Test fun guardLambdaCanDecideByAction() = runBlocking {
        val guard = SensitiveAppsGuard { _, action -> action == SensitiveAction.Escape }
        assertTrue(guard.active)
        assertTrue(guard.check("com.example.bank", SensitiveAction.Escape))
        assertFalse(guard.check("com.example.bank", SensitiveAction.Act))
    }

    @Test fun deniedCodesAreMapped() {
        assertEquals("sensitive_app_denied", SensitiveAppsDenied.DENIED)
        assertEquals("sensitive_app_timeout", SensitiveAppsDenied.TIMEOUT)
        assertTrue(SensitiveAppsDenied.isSensitiveCode("sensitive_app_denied"))
        assertTrue(SensitiveAppsDenied.isSensitiveCode("sensitive_app_timeout"))
        assertFalse(SensitiveAppsDenied.isSensitiveCode("app_blocked"))
        assertFalse(SensitiveAppsDenied.isSensitiveCode("sensitive_app"))
    }
}
