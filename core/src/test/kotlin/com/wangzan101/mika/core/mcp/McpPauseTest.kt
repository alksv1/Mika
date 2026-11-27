package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.DeviceAccessPort
import com.wangzan101.mika.core.device.DeviceAccessStore
import com.wangzan101.mika.core.device.DeviceCredential
import com.wangzan101.mika.core.device.DeviceState
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.run.PauseFlag
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/** 暂停 Mika 时电脑那边:会动手机的工具都回同一句 UNAVAILABLE,读状态、挂断照常。 */
class McpPauseTest {
    private var paused = true
    private val writes = mutableListOf<String>()
    private val calls = object : PhoneCallPort {
        override suspend fun state() = buildJsonObject { put("phase", "ringing"); put("call_id", "c1") }
        override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?, ownerConversationId: String?): JsonObject {
            writes += "dial"; return buildJsonObject { put("ok", true) }
        }
        override suspend fun answer(callId: String): JsonObject { writes += "answer"; return buildJsonObject { put("ok", true) } }
        override suspend fun hangup(callId: String): JsonObject { writes += "hangup"; return buildJsonObject { put("ok", true) } }
        override suspend fun takeover(): JsonObject { writes += "takeover"; return buildJsonObject { put("ok", true) } }
    }
    private val store = object : DeviceAccessStore {
        override val enabled = true
        override val credentialConfigured = false
        override val attemptBlocked = false
        override fun credential(): DeviceCredential? = null
        override fun beginAttempt(credential: DeviceCredential) = false
        override fun completeAttempt(credential: DeviceCredential) = Unit
    }
    private val device = object : DeviceAccessPort {
        override suspend fun state() = DeviceState(true, false, false, false, true, true)
        override suspend fun wake(): Boolean { writes += "wake"; return true }
        override suspend fun dismiss(): Boolean { writes += "dismiss"; return true }
        override suspend fun credentialReady(kind: CredentialKind) = false
        override suspend fun enter(credential: DeviceCredential): Boolean { writes += "enter"; return false }
    }
    private val endpoints = RemoteEndpoints(
        FakeRemoteTasks(), "test", device = DeviceAccess(device, store), calls = calls, paused = PauseFlag { paused },
    )

    private fun call(name: String, args: String = "{}"): String {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        val req = HttpRequest("POST", "/mcp", mapOf("content-type" to "application/json"), body.toByteArray(), InetAddress.getLoopbackAddress())
        return endpoints.handle(req).body.toString(Charsets.UTF_8)
    }

    @Test fun phoneActingToolsAreRefusedWithThePausedMessageWhilePaused() {
        for ((name, args) in listOf(
            McpCallTools.DIAL to """{"number":"13800000000"}""",
            McpCallTools.ANSWER to """{"call_id":"c1"}""",
            McpDeviceTools.WAKE to "{}",
            McpDeviceTools.UNLOCK to "{}",
        )) {
            val out = call(name, args)
            assertTrue("$name: $out", out.contains(PauseFlag.REMOTE_MESSAGE))
        }
        assertTrue(writes.isEmpty())
        // 读状态、挂断、交还听筒不是新工作:照常
        assertFalse(call(McpCallTools.STATE).contains(PauseFlag.REMOTE_MESSAGE))
        assertFalse(call(McpDeviceTools.STATE).contains(PauseFlag.REMOTE_MESSAGE))
        assertFalse(call(McpCallTools.HANGUP, """{"call_id":"c1"}""").contains(PauseFlag.REMOTE_MESSAGE))
        assertEquals(listOf("hangup"), writes)

        paused = false
        assertFalse(call(McpCallTools.DIAL, """{"number":"13800000000"}""").contains(PauseFlag.REMOTE_MESSAGE))
        assertTrue("dial" in writes)
    }

    @Test fun answerAndSteerAreRefusedAsUnavailableWhilePaused() {
        val service = RemoteTaskService(FakeRemoteTasks(), paused = PauseFlag { true })
        for (action in listOf<() -> Unit>({ service.answer("t", "yes") }, { service.steer("t", "more") })) {
            try { action(); fail("expected paused") } catch (e: RemoteTaskError) {
                assertEquals(RemoteTaskError.Code.UNAVAILABLE, e.code)
                assertEquals(PauseFlag.REMOTE_MESSAGE, e.message)
            }
        }
    }
}
