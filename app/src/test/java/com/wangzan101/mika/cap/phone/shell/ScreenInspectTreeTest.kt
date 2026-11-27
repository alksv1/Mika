package com.wangzan101.mika.cap.phone.shell

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenInspectTreeTest {
    private val released = mutableListOf<FakeNode>()
    private val fetched = mutableListOf<FakeNode>()

    private inner class FakeNode(
        override val text: CharSequence? = null,
        override val description: CharSequence? = null,
        override val isPassword: Boolean = false,
        val kids: List<FakeNode> = emptyList(),
        override val className: CharSequence? = "android.widget.TextView",
    ) : InspectNode {
        override val packageName: CharSequence? = "com.example"
        override val resourceId: String? = null
        override val bounds = intArrayOf(1, 2, 3, 4)
        override val isClickable = false
        override val isEditable = isPassword
        override val isEnabled = true
        override val isVisible = true
        override val childCount get() = kids.size
        override fun child(index: Int): InspectNode = kids[index].also { fetched += it }
        override fun release() { released += this }
    }

    private fun JsonObject.children(): List<JsonObject> = getValue("children").jsonArray.map { it.jsonObject }

    @Test
    fun readableTextAndMetadataAreKept() {
        val budget = InspectBudget()
        val node = FakeNode(text = "Example", description = "Label")
        val json = ScreenInspectTree.node(node, budget)!!
        assertEquals("Example", json.getValue("text").jsonPrimitive.content)
        assertEquals("Label", json.getValue("description").jsonPrimitive.content)
        assertEquals("android.widget.TextView", json.getValue("class").jsonPrimitive.content)
        assertEquals(listOf(1, 2, 3, 4), (json.getValue("bounds") as JsonArray).map { it.jsonPrimitive.content.toInt() })
        assertFalse(json.getValue("password").jsonPrimitive.boolean)
        assertEquals(1, budget.nodes)
        assertFalse(budget.truncated)
        assertEquals(listOf(node), released)
    }

    @Test
    fun passwordTextAndDescriptionNeverAppear() {
        val inner = FakeNode(text = "inner-secret")
        val field = FakeNode(text = "hunter2-secret", description = "desc-secret", isPassword = true, kids = listOf(inner))
        val root = FakeNode(text = "Sign in", kids = listOf(field))
        val json = ScreenInspectTree.node(root, InspectBudget())!!
        val out = json.toString()
        for (secret in listOf("hunter2-secret", "desc-secret", "inner-secret")) assertFalse(secret, secret in out)
        val masked = json.children().single()
        assertEquals(JsonNull, masked["text"])
        assertEquals(JsonNull, masked["description"])
        assertTrue(masked.getValue("password").jsonPrimitive.boolean)
        // 密码框下面的子控件同样遮蔽
        val child = masked.children().single()
        assertEquals(JsonNull, child["text"])
        assertTrue(child.getValue("password").jsonPrimitive.boolean)
        assertEquals("Sign in", json.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun depthLimitStopsBeforeFetchingDeeperChildren() {
        // 一条 10 层的链,只允许 3 层
        var chain = FakeNode(text = "leaf")
        val all = mutableListOf(chain)
        repeat(9) { chain = FakeNode(kids = listOf(chain)).also { all += it } }
        val budget = InspectBudget(maxDepth = 3)
        val json = ScreenInspectTree.node(chain, budget)!!
        var depth = 1
        var cur = json
        while (cur.children().isNotEmpty()) { cur = cur.children().single(); depth++ }
        assertEquals(3, depth)
        assertEquals(3, budget.nodes)
        assertTrue(budget.truncated)
        // 第 4 层不去取,取过的都交还了
        assertEquals(2, fetched.size)
        assertEquals((fetched + chain).toSet(), released.toSet())
    }

    @Test
    fun nodeLimitCapsTheWholeTreeAndReleasesEverythingFetched() {
        val root = FakeNode(kids = List(50) { FakeNode(text = "item $it") })
        val budget = InspectBudget(maxNodes = 10)
        val json = ScreenInspectTree.node(root, budget)!!
        assertEquals(9, json.children().size)
        assertEquals(10, budget.nodes)
        assertTrue(budget.truncated)
        assertEquals(9, fetched.size)
        assertEquals(10, released.size)
        // 总数用完后,同一次读屏里的下一棵树不再读
        val other = FakeNode()
        assertNull(ScreenInspectTree.node(other, budget))
        assertEquals(other, released.last())
    }

    @Test
    fun longTextIsCutWithoutSplittingACharacter() {
        val budget = InspectBudget(maxText = 4)
        assertEquals("abcd", ScreenInspectTree.plain("abcdef", budget).content)
        assertTrue(budget.truncated)
        assertEquals("abc", ScreenInspectTree.plain("abc😀", InspectBudget(maxText = 4)).content)
        val untouched = InspectBudget(maxText = 4)
        assertEquals("abcd", ScreenInspectTree.plain("abcd", untouched).content)
        assertFalse(untouched.truncated)
        assertEquals(JsonNull, ScreenInspectTree.plain(null, untouched))
    }

    @Test
    fun totalOutputIsCappedAcrossTreesAndNamesTheReason() {
        // 每个控件 220 + 100 个字符;上限只够 3 个
        val budget = InspectBudget(maxOutput = 3 * (ScreenInspectTree.NODE_OVERHEAD + 100) + 50)
        val text = "x".repeat(100)
        val first = ScreenInspectTree.node(FakeNode(text = text, className = null, kids = List(5) { FakeNode(text = text, className = null) }), budget)!!
        assertEquals(2, first.children().size)
        assertEquals(3, budget.nodes)
        assertEquals(listOf(ScreenInspectTree.CUT_OUTPUT), budget.truncatedBy.toList())
        assertTrue(budget.output <= budget.maxOutput)
        // 同一次读屏的下一个窗口也不再读,取过的节点都交还
        assertNull(ScreenInspectTree.node(FakeNode(text = text), budget))
        assertEquals(fetched.size + 2, released.size)
        // 每段文字都在单段上限内,截断原因只有总输出
        assertFalse(ScreenInspectTree.CUT_TEXT in budget.truncatedBy)
    }

    @Test
    fun truncationReasonsAreRecordedInOrder() {
        val budget = InspectBudget(maxNodes = 1, maxText = 2)
        ScreenInspectTree.node(FakeNode(text = "long", kids = listOf(FakeNode())), budget)
        assertEquals(listOf(ScreenInspectTree.CUT_TEXT, ScreenInspectTree.CUT_NODES), budget.truncatedBy.toList())
    }

    @Test
    fun focusedThenActiveWindowsAreReadFirstOtherwiseInSystemOrder() {
        data class W(val name: String, val focused: Boolean = false, val active: Boolean = false)
        val windows = listOf(W("bar"), W("app", active = true), W("ime"), W("dialog", focused = true), W("nav"))
        val order = ScreenInspectTree.windowOrder(windows, { it.focused }, { it.active }).map { it.name }
        assertEquals(listOf("dialog", "app", "bar", "ime", "nav"), order)
        assertEquals(order, ScreenInspectTree.windowOrder(windows, { it.focused }, { it.active }).map { it.name })
    }
}
