package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice

/**
 * 渠道入站在这里验主人、问题编号与确认权限；普通新消息始终新开任务。
 * 用户暂停了 Mika([paused])时新消息不开任务、也不留着等恢复:先确认收到,再尽力回一句「已暂停」([Route.paused]);
 * 回复发不出去也不影响确认,渠道不会把这条重交、恢复后也不会自动跑起来;要办的事恢复后重新发。
 */
class ChannelGateway(
    private val owners: OwnerIdentity,
    private val activeAsk: (String) -> Delivery.Ask?,
    private val route: Route,
    private val paused: PauseFlag = PauseFlag.Never,
) {
    interface Route {
        suspend fun newTask(peer: ChannelPeer, messageId: String, text: String)
        /** 暂停期间收到的新消息(已确认收到):尽力告诉对方 Mika 已暂停、这条没有开始办。发不出去就算了,不重试。 */
        suspend fun paused(peer: ChannelPeer, messageId: String)
        suspend fun answer(threadId: String, askId: String, text: String): Boolean
        suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean
        suspend fun stop(runId: RunId)
        suspend fun cancelAsk(threadId: String)
    }

    fun start(channel: Channel): AutoCloseable = channel.start(ChannelInputSink { input ->
        if (input.peer.channelId != channel.id || !owners.isOwner(input.peer)) return@ChannelInputSink
        when (input) {
            is ChannelInput.Stop -> route.stop(input.runId)
            is ChannelInput.Text -> {
                if (paused.paused()) {
                    channel.acknowledge(input.messageId)
                    try { route.paused(input.peer, input.messageId) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { }
                    return@ChannelInputSink
                }
                val ask = activeAsk(input.peer.threadId)
                if (input.replyToAskId != null && ask?.askId == input.replyToAskId && ask.kind == Delivery.Ask.Kind.Input) {
                    if (route.answer(input.peer.threadId, ask.askId, input.text)) channel.acknowledge(input.messageId)
                } else if (input.replyToAskId != null && input.requireMatchingAsk) {
                    channel.acknowledge(input.messageId)
                } else {
                    if (ask != null) route.cancelAsk(input.peer.threadId)
                    route.newTask(input.peer, input.messageId, input.text)
                }
            }
            is ChannelInput.Confirm -> {
                if (paused.paused()) return@ChannelInputSink
                val ask = activeAsk(input.peer.threadId)
                if (ask?.askId == input.askId && ask.kind == Delivery.Ask.Kind.Confirm && channel.confirms) {
                    route.confirm(input.peer.threadId, ask.askId, input.choice)
                }
            }
        }
    })
}
