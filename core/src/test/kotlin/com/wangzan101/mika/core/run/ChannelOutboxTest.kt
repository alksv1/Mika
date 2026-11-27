package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelOutboxTest {
    private val peer = ChannelPeer("fake", "owner-peer", "thread")

    private class FakeChannel(override val confirms: Boolean = true) : Channel {
        override val id = "fake"
        val sent = mutableListOf<Delivery>()
        val acknowledged = mutableListOf<String>()
        var outcome = SendOutcome.Delivered
        var alive = true
        private var sink: ChannelInputSink? = null
        override fun isAlive(peer: ChannelPeer) = alive
        override fun start(sink: ChannelInputSink): AutoCloseable { this.sink = sink; return AutoCloseable { this.sink = null } }
        override fun acknowledge(messageId: String) { acknowledged += messageId }
        override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome { sent += delivery; return outcome }
        suspend fun receive(input: ChannelInput) { sink?.receive(input) }
    }

    @Test fun askReplyConfirmAndResultRoundTrip() = runBlocking {
        val channel = FakeChannel()
        val outbox = Outbox()
        val owners = MemoryOwnerIdentity().also { it.bind(peer) }
        var active: Delivery.Ask? = null
        val received = mutableListOf<String>()
        val gateway = ChannelGateway(owners, { active }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { received += "new:$text" }
            override suspend fun answer(threadId: String, askId: String, text: String): Boolean { received += "answer:$text"; active = null; return true }
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean { received += "confirm:$choice"; active = null; return true }
            override suspend fun stop(runId: RunId) { received += "stop" }
            override suspend fun cancelAsk(threadId: String) { received += "cancelAsk"; active = null }
            override suspend fun paused(peer: ChannelPeer, messageId: String): Unit = error("not paused")
        })
        gateway.start(channel)
        val input = Delivery.Ask("d1", "thread", "a1", Delivery.Ask.Kind.Input, "which?")
        active = input
        assertEquals(DeliveryState.Delivered, outbox.deliver(channel, peer, input).state)
        channel.receive(ChannelInput.Text(peer, "m1", "A", replyToAskId = "a1"))
        val confirm = Delivery.Ask("d2", "thread", "a2", Delivery.Ask.Kind.Confirm, "order?")
        active = confirm
        outbox.deliver(channel, peer, confirm)
        channel.receive(ChannelInput.Confirm(peer, "a2", ConfirmChoice.Once))
        outbox.deliver(channel, peer, Delivery.Result("d3", "thread", "done"))
        assertEquals(listOf("answer:A", "confirm:Once"), received)
        assertEquals(listOf("m1"), channel.acknowledged)
        assertEquals(listOf(input, confirm, Delivery.Result("d3", "thread", "done")), channel.sent)
        assertEquals(DeliveryState.Delivered, outbox.deliver(channel, peer, input).state)
        assertEquals(3, channel.sent.size)
    }

    @Test fun ownerAndChannelRulesBlockUntrustedApproval() = runBlocking {
        val channel = FakeChannel(confirms = false)
        val payment = Delivery.Ask("d", "thread", "a", Delivery.Ask.Kind.Confirm, "payment?")
        val events = mutableListOf<String>()
        val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { payment }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { events += "new" }
            override suspend fun answer(threadId: String, askId: String, text: String) = true
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean { events += "confirm"; return true }
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String) = Unit
            override suspend fun paused(peer: ChannelPeer, messageId: String): Unit = error("not paused")
        })
        gateway.start(channel)
        channel.receive(ChannelInput.Confirm(peer, "a", ConfirmChoice.Once))
        channel.receive(ChannelInput.Confirm(peer.copy(peerId = "stranger"), "a", ConfirmChoice.Once))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun unmarkedTextStartsNewTaskEvenWhileAskIsActive() = runBlocking {
        val channel = FakeChannel()
        val ask = Delivery.Ask("d", "thread", "a1", Delivery.Ask.Kind.Input, "which?")
        val events = mutableListOf<String>()
        val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { ask }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { events += "new:$text" }
            override suspend fun answer(threadId: String, askId: String, text: String): Boolean { events += "answer:$text"; return true }
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String) { events += "cancelAsk" }
            override suspend fun paused(peer: ChannelPeer, messageId: String): Unit = error("not paused")
        })
        gateway.start(channel)

        channel.receive(ChannelInput.Text(peer, "m-new", "a different task"))
        channel.receive(ChannelInput.Text(peer, "m-old", "another task", replyToAskId = "old-ask"))

        assertEquals(listOf("cancelAsk", "new:a different task", "cancelAsk", "new:another task"), events)
        assertEquals(emptyList<String>(), channel.acknowledged)
    }

    @Test fun rejectedAnswerStaysUnacknowledgedForRedelivery() = runBlocking {
        val channel = FakeChannel()
        val ask = Delivery.Ask("d", "thread", "a1", Delivery.Ask.Kind.Input, "which?")
        var answerCalls = 0
        val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { ask }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) = Unit
            override suspend fun answer(threadId: String, askId: String, text: String): Boolean { answerCalls++; return false }
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String) = Unit
            override suspend fun paused(peer: ChannelPeer, messageId: String): Unit = error("not paused")
        })
        gateway.start(channel)

        channel.receive(ChannelInput.Text(peer, "m1", "A", replyToAskId = "a1"))

        assertEquals(1, answerCalls)
        assertEquals(emptyList<String>(), channel.acknowledged)
    }

    @Test fun newMessageWhilePausedGetsPausedReplyAndIsNotQueuedForResume() = runBlocking {
        val channel = FakeChannel()
        val outbox = Outbox()
        var paused = true
        val events = mutableListOf<String>()
        val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { null }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { events += "new:$text" }
            override suspend fun answer(threadId: String, askId: String, text: String) = false
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String) = Unit
            override suspend fun paused(peer: ChannelPeer, messageId: String) {
                events += "paused:$messageId"
                outbox.deliver(channel, peer, Delivery.Notice("paused:$messageId", peer.threadId, "Mika is paused"))
            }
        }, paused = PauseFlag { paused })
        gateway.start(channel)

        channel.receive(ChannelInput.Text(peer, "m1", "order lunch"))
        // 确认收到:渠道不会把这条留着重交,恢复后也就不会自动开任务
        assertEquals(listOf("m1"), channel.acknowledged)
        assertEquals(listOf<Delivery>(Delivery.Notice("paused:m1", "thread", "Mika is paused")), channel.sent)

        paused = false
        channel.receive(ChannelInput.Text(peer, "m2", "check weather"))
        assertEquals(listOf("paused:m1", "new:check weather"), events)
    }

    @Test fun pausedMessageIsAcknowledgedEvenWhenThePausedReplyFails() = runBlocking {
        val channel = FakeChannel()
        val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { null }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String): Unit = error("must not start while paused")
            override suspend fun answer(threadId: String, askId: String, text: String) = false
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String) = Unit
            override suspend fun paused(peer: ChannelPeer, messageId: String): Unit = error("reply could not be recorded")
        }, paused = PauseFlag { true })
        gateway.start(channel)

        // 回复只是尽力而为:发不出去也照样确认收到,渠道不重交,恢复后也不会自己跑起来
        channel.receive(ChannelInput.Text(peer, "m1", "order lunch"))
        assertEquals(listOf("m1"), channel.acknowledged)
    }

    @Test fun uncertainSendIsNeverBlindlyRetried() = runBlocking {
        val channel = FakeChannel().apply { outcome = SendOutcome.Uncertain }
        val outbox = Outbox()
        val result = Delivery.Result("one", "thread", "done")
        assertEquals(DeliveryState.Uncertain, outbox.deliver(channel, peer, result).state)
        assertEquals(DeliveryState.Uncertain, outbox.deliver(channel, peer, result).state)
        assertEquals(1, channel.sent.size)
    }

    @Test fun deadSessionIsNotSentTo() = runBlocking {
        val channel = FakeChannel().apply { alive = false }
        val record = Outbox().deliver(channel, peer, Delivery.Notice("n1", "thread", "hello"))
        assertEquals(DeliveryState.Failed, record.state)
        assertEquals(emptyList<Delivery>(), channel.sent)
    }
}
