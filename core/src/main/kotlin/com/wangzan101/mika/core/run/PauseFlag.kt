package com.wangzan101.mika.core.run

/**
 * 用户手动「暂停 Mika」的标记。持久化的那一份(App 层)是唯一的真相来源:
 * 各入口(定时、渠道、电脑交来的任务、工作流、内置助手的看门狗与自动修复)都只读它,不另存一份,
 * 每次要开始新工作、每个异步交接点都重新读。暂停只拦新工作和自动恢复,不解除配对、不删已有的数据。
 */
fun interface PauseFlag {
    fun paused(): Boolean

    companion object {
        /** 没有接暂停的地方(测试、旧调用方)用:从不暂停。 */
        val Never = PauseFlag { false }

        /** 暂停期间被拦下的工作记下的机器码:触发时段的跳过原因、工作流尝试的失败原因、电脑任务的拒绝原因。 */
        const val REASON = "mika_paused"
        /** 电脑(MCP、CLI 接口)在暂停时收到的说明:不开始、不排队,恢复后要重新发。 */
        const val REMOTE_MESSAGE = "Mika is paused on the phone, so it did not start or queue this request. Resume Mika on the phone, then send it again."
    }
}
