package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.voice.Wav

/** 本机识别的时间预算:一段录音给它音频时长的 [FACTOR] 倍,至少 [MIN_MS];加载模型另算 [LOAD_TIMEOUT_MS]。 */
object OnDeviceTimeout {
    const val MIN_MS = 30_000L
    const val FACTOR = 3
    const val LOAD_TIMEOUT_MS = 60_000L

    fun budgetMs(samples: Int, rate: Int = Wav.RATE): Long =
        maxOf(MIN_MS, samples.toLong() * 1000L * FACTOR / rate)
}
