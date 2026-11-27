package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 语音等不经 RawResponse 的调用,按抛出的 ModelError 判断「这把密钥被拒」并轮换。 */
class KeyRejectionTest {
    @Test fun `rate limit auth and rejecting server statuses are key rejections, through wrappers`() {
        assertEquals(KeyRejection(429, 1500L), keyRejectionOf(ModelError.RateLimited(1500L)))
        assertEquals(KeyRejection(401, null), keyRejectionOf(ModelError.Auth()))
        assertEquals(KeyRejection(403, null), keyRejectionOf(ModelError.Server(403)))
        assertEquals(KeyRejection(429, 2000L), keyRejectionOf(IllegalStateException("wrapped", ModelError.RateLimited(2000L))))
    }

    @Test fun `overload, network and unrelated errors are not key rejections`() {
        assertNull(keyRejectionOf(ModelError.Server(503)))
        assertNull(keyRejectionOf(ModelError.Server(500)))
        assertNull(keyRejectionOf(ModelError.Network()))
        assertNull(keyRejectionOf(IllegalArgumentException("bad")))
    }

    @Test fun `each key is tried at most once and the rejection is reported with its status`() = runTest {
        val pool = ArrayDeque(listOf("k1", "k2", "k1", "k2"))
        val reported = mutableListOf<Triple<String, Long?, Int>>()
        val sent = mutableListOf<String?>()
        val result = withKeyRotation(
            nextKey = { pool.removeFirstOrNull() },
            rejected = { key, retry, status -> reported += Triple(key, retry, status) },
            rejectionOf = { r: Int -> if (r == 429) KeyRejection(429, 10L) else null },
            send = { key -> sent += key; 429 },
        )
        assertEquals(429, result)
        assertEquals(listOf("k1", "k2"), sent)
        assertEquals(listOf(Triple("k1", 10L, 429), Triple("k2", 10L, 429)), reported)
    }

    @Test fun `second key succeeds after the first is rejected`() = runTest {
        val pool = ArrayDeque(listOf("k1", "k2"))
        val result = withKeyRotation(
            nextKey = { pool.removeFirstOrNull() },
            rejected = { _, _, _ -> },
            rejectionOf = { r: String -> if (r == "no") KeyRejection(401, null) else null },
            send = { key -> if (key == "k1") "no" else "ok:$key" },
        )
        assertEquals("ok:k2", result)
    }
}
