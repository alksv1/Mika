package com.wangzan101.mika.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class PageStatesTest {
    private val none: (String) -> Boolean = { false }

    @Test fun deeperPagesAreDroppedWhenGoingBack() {
        // 设置(2) → 模型(3):返回设置时模型页出栈,设置页自己留着
        assertEquals(listOf("Models"), PageStates.stalePages(mapOf("Chat" to 1, "Settings" to 2, "Models" to 3), "Settings", 2, none))
    }

    @Test fun ancestorsSurviveGoingDeeper() {
        assertEquals(emptyList<String>(), PageStates.stalePages(mapOf("Chat" to 1, "Settings" to 2), "Models", 3, none))
    }

    @Test fun sameLevelReplacementDropsTheOldPage() {
        // 对话页上直接换到另一页(侧栏切页):原来那页已不在栈里
        assertEquals(listOf("Settings"), PageStates.stalePages(mapOf("Chat" to 1, "Settings" to 2), "Memory", 2, none))
    }

    @Test fun keptPagesSurviveReset() {
        val gone = PageStates.stalePages(mapOf("Chat" to 1, "Personal" to 2, "Settings" to 3), "Chat", 1) { it == "Personal" }
        assertEquals(listOf("Settings"), gone)
    }

    @Test fun depthsSurviveSaveAndRestore() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val depths = hashMapOf("Chat" to 1, "Detail(id=a:b, fromProviders=true)" to 2)
        val saved = with(PageStates.DepthsSaver) { scope.save(depths) }!!
        assertEquals(depths, PageStates.DepthsSaver.restore(saved))
    }
}
