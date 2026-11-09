package com.wangzan101.mika.settings

import com.wangzan101.mika.core.sensitive.SensitiveAppsPolicy
import com.wangzan101.mika.core.sensitive.SensitiveAppsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 敏感名单存储:默认空、增删跨实例保留、损坏按空处理且能改回来。 */
class SensitiveAppsStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = java.io.File(temp.root, "sensitive.json")

    @Test fun emptyByDefaultAndLivePolicySeesChanges() {
        val store = SensitiveAppsStore(file())
        assertEquals(SensitiveAppsState(), store.current())
        val policy: SensitiveAppsPolicy = store
        assertFalse(policy.current().isSensitive("com.example.bank"))
        store.setSensitive("com.example.bank", true)
        assertTrue(policy.current().isSensitive("com.example.bank"))
        assertEquals(setOf("com.example.bank"), policy.current().packages)
    }

    @Test fun addAndRemoveSurviveReload() {
        val store = SensitiveAppsStore(file())
        store.setSensitive("a.bank", true)
        store.setSensitive("b.gallery", true)
        assertEquals(setOf("a.bank", "b.gallery"), SensitiveAppsStore(file()).current().packages)
        store.setSensitive("a.bank", false)
        assertEquals(setOf("b.gallery"), SensitiveAppsStore(file()).current().packages)
    }

    @Test fun blankPackageIsRejected() {
        val store = SensitiveAppsStore(file())
        assertThrows(IllegalArgumentException::class.java) { store.setSensitive("   ", true) }
        assertTrue(store.current().packages.isEmpty())
    }

    @Test fun replaceTrimsDropsBlankAndSurvivesReload() {
        val store = SensitiveAppsStore(file())
        store.replace(setOf(" a.bank ", "", "b.gallery"))
        assertEquals(setOf("a.bank", "b.gallery"), store.current().packages)
        assertEquals(setOf("a.bank", "b.gallery"), SensitiveAppsStore(file()).current().packages)
    }

    @Test fun resetClearsTheList() {
        val store = SensitiveAppsStore(file())
        store.setSensitive("a.bank", true)
        store.reset()
        assertEquals(SensitiveAppsState(), store.current())
        assertEquals(SensitiveAppsState(), SensitiveAppsStore(file()).current())
    }

    @Test fun corruptFileIsEmptyAndCanBeRepairedByWriting() {
        file().writeText("{bad")
        val store = SensitiveAppsStore(file())
        assertFalse(store.current().available)
        assertFalse(store.current().isSensitive("a.bank"))
        store.setSensitive("a.bank", true)
        assertTrue(store.current().available)
        assertEquals(setOf("a.bank"), SensitiveAppsStore(file()).current().packages)
    }

    @Test fun wrongVersionIsTreatedAsUnreadable() {
        file().writeText("""{"version":2,"packages":["a.bank"]}""")
        val store = SensitiveAppsStore(file())
        assertFalse(store.current().available)
        assertFalse(store.current().isSensitive("a.bank"))
    }
}
