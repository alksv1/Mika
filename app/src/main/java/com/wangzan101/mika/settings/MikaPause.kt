package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.run.PauseFlag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「暂停 Mika」的持久标记,整个进程一份。这是唯一的真相来源:任务服务、定时、渠道、电脑交来的任务、工作流、
 * 内置助手的看门狗与交接、自动修复和配对通知都只读它([paused]),不各自另存;内置助手的 Paused 状态只是它的投影。
 * 改它只经 [set]:先同步写盘,写成了才更新内存里的值,所以进程随时被杀,重启后读到的都是最后一次写成的。
 * [set] 会等磁盘,别在主线程上调(暂停页经 [com.wangzan101.mika.task.MikaPauseControl] 切到 IO 线程)。
 */
class MikaPause internal constructor(initial: Boolean, private val persist: (Boolean) -> Boolean) : PauseFlag {
    private val _state = MutableStateFlow(initial)
    /** 给要跟着变的地方(界面、自动修复的「此刻能不能动」)用。 */
    val state: StateFlow<Boolean> = _state.asStateFlow()

    override fun paused(): Boolean = _state.value

    /** 写盘失败返回 false,内存里的值不变。 */
    @Synchronized
    fun set(paused: Boolean): Boolean {
        if (_state.value == paused) return true
        if (!persist(paused)) return false
        _state.value = paused
        return true
    }

    companion object {
        private const val PREFS = "mika_pause"
        private const val KEY_PAUSED = "paused"

        @Volatile private var instance: MikaPause? = null

        fun get(context: Context): MikaPause = instance ?: synchronized(this) {
            instance ?: run {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                MikaPause(prefs.getBoolean(KEY_PAUSED, false)) { prefs.edit().putBoolean(KEY_PAUSED, it).commit() }
            }.also { instance = it }
        }
    }
}
