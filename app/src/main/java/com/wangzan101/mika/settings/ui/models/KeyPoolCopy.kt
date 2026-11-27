package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/**
 * 模型设置里密钥池的文字中,按 Key 数量选说法的几处(纯函数,便于测试)。
 * 只写一次的静态文案在界面上直接用 stringResource。
 */
object KeyPoolCopy {
    /** 「N keys」;带数量的其他说法都引用它,各语言按自己的复数规则变化。 */
    fun keys(count: Int): String = trPlural(R.plurals.model_keys_count, count)

    /** 服务商详情里密钥区的标题:没配 Key、一把、多把各一种。 */
    fun sectionTitle(count: Int): String = when (count) {
        0 -> tr(R.string.model_keys_title)
        1 -> tr(R.string.model_keys_pool_one)
        else -> tr(R.string.model_keys_pool_many, keys(count))
    }

    /** 密钥区下面的脚注:一把 Key 时劝再添,多把时说明轮换规则;没配 Key 时没有。 */
    fun footerNote(count: Int): String? = when {
        count > 1 -> tr(R.string.model_keys_rotate_note)
        count == 1 -> tr(R.string.model_keys_add_more_note)
        else -> null
    }

    /** 添加 Key 弹窗里粘贴了多把 Key 时的反馈。 */
    fun detectedAdd(count: Int): String = tr(R.string.model_keys_detected_add, keys(count))

    /** 更换 Key 弹窗里粘贴了多把 Key 时的反馈。 */
    fun detectedChange(count: Int): String = trPlural(R.plurals.model_keys_detected_change, count)

    /** 接入向导填 Key 处粘贴了多把 Key 时的反馈。 */
    fun detectedSetup(count: Int): String = tr(R.string.model_keys_detected_setup, keys(count))

    /** 添加 Key 弹窗的确认按钮:多把时写明把数,一把时就是「保存」。 */
    fun addConfirm(count: Int, save: String): String =
        if (count > 1) trPlural(R.plurals.model_key_add_count, count) else save

    /** 服务商列表里一行的副标题:池里有多把 Key 时在 [line] 后面带上密钥池说明,最多一把时就是 [line]。 */
    fun providerLine(line: String, count: Int): String =
        if (count > 1) tr(R.string.model_provider_line_pool, line, keys(count)) else line

    /**
     * 服务商入口的副标题:没连服务商、池里有多把 Key、只有服务商数三种。
     * 服务商数量按当前语言的复数规则说,只有一家但池里有多把时不再写成「1 providers」。
     */
    fun providersSubtitle(providers: Int, keysInPool: Int): String {
        if (providers == 0) return tr(R.string.model_providers_none)
        val count = trPlural(R.plurals.models_providers_count, providers)
        return if (keysInPool > 1) tr(R.string.model_providers_keys_in_pool, count, keys(keysInPool)) else count
    }
}
