package com.wangzan101.mika.cap.phone.shell

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真的 AccessibilityNodeInfo 经适配层写出时,密码框的文字和描述不出现;普通文字照常保留。 */
@RunWith(AndroidJUnit4::class)
class ScreenInspectDeviceTest {
    @Suppress("DEPRECATION") // obtain() 在 API 30 起有构造方法替代,最低 API 29 仍用它
    private fun node(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain()

    @Test fun passwordFieldTextNeverLeavesTheAdapter() {
        val field = node().apply {
            isPassword = true
            text = "secret-value"
            contentDescription = "secret-description"
            className = "android.widget.EditText"
        }
        val json = ScreenInspectTree.node(A11yInspectNode(field), InspectBudget())!!
        assertFalse(json.toString().contains("secret-"))
        assertEquals(JsonNull, json["text"])
        assertEquals(JsonNull, json["description"])
        assertTrue(json.getValue("password").jsonPrimitive.boolean)
        assertEquals("android.widget.EditText", json.getValue("class").jsonPrimitive.content)
    }

    @Test fun readableTextIsKept() {
        val label = node().apply { text = "Example" }
        val budget = InspectBudget()
        val json = ScreenInspectTree.node(A11yInspectNode(label), budget)!!
        assertEquals("Example", json.getValue("text").jsonPrimitive.content)
        assertEquals(1, budget.nodes)
        assertFalse(budget.truncated)
    }
}
