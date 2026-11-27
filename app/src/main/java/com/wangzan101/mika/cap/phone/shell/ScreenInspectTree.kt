package com.wangzan101.mika.cap.phone.shell

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 只读读屏时看到的一个控件;真机上包着 AccessibilityNodeInfo,测试里换成假的。 */
internal interface InspectNode {
    val packageName: CharSequence?
    val className: CharSequence?
    val resourceId: String?
    val text: CharSequence?
    val description: CharSequence?
    val isPassword: Boolean
    /** left, top, right, bottom(屏幕坐标)。 */
    val bounds: IntArray
    val isClickable: Boolean
    val isEditable: Boolean
    val isEnabled: Boolean
    val isVisible: Boolean
    val childCount: Int
    fun child(index: Int): InspectNode?
    /** 用完交还(旧系统上要回收节点)。 */
    fun release()
}

/**
 * 一次读屏的上限:控件总数、树的深度、每段文字的长度,以及整次输出的大致字符数([maxOutput],
 * 每个控件按 [ScreenInspectTree.NODE_OVERHEAD] 个字符的字段开销加上各段文字算)。超出哪一项就记进 [truncatedBy]。
 */
internal class InspectBudget(
    val maxNodes: Int = ScreenInspectTree.MAX_NODES,
    val maxDepth: Int = ScreenInspectTree.MAX_DEPTH,
    val maxText: Int = ScreenInspectTree.MAX_TEXT,
    val maxOutput: Long = ScreenInspectTree.MAX_OUTPUT,
) {
    var nodes = 0
    /** 已经写出的大致字符数。 */
    var output = 0L
    /** 因为哪几项上限截断了:`nodes`、`depth`、`text`、`output`(按先后)。 */
    val truncatedBy = LinkedHashSet<String>()
    val truncated: Boolean get() = truncatedBy.isNotEmpty()

    fun cut(reason: String) { truncatedBy += reason }

    /** 再写 [chars] 个字符还在总上限内就记上并返回真;否则记下截断。 */
    fun charge(chars: Long): Boolean {
        if (output + chars > maxOutput) {
            cut(ScreenInspectTree.CUT_OUTPUT)
            return false
        }
        output += chars
        return true
    }
}

/**
 * 把控件树写成 JSON。密码框(以及它下面的所有子控件)不写文字和描述,只标 `password: true`;
 * 超出 [InspectBudget] 的部分不读(连子节点都不去取),只记下截断的原因。
 */
internal object ScreenInspectTree {
    const val MAX_NODES = 2_500
    const val MAX_DEPTH = 60
    const val MAX_TEXT = 1_000
    /** 整次输出的字符上限;CLI 最多收 4 MiB,按 UTF-8 每字符最多 3 字节留出余量。 */
    const val MAX_OUTPUT = 1_000_000L
    /** 一个控件除文字外的字段(键名、布尔、坐标、括号)大约占的字符数。 */
    const val NODE_OVERHEAD = 220L
    const val CUT_NODES = "nodes"
    const val CUT_DEPTH = "depth"
    const val CUT_TEXT = "text"
    const val CUT_OUTPUT = "output"

    /** [node] 由这里交还;超出总数、深度或输出上限时返回 null。 */
    fun node(node: InspectNode, budget: InspectBudget, depth: Int = 0, inSecret: Boolean = false): JsonObject? {
        try {
            if (budget.nodes >= budget.maxNodes) { budget.cut(CUT_NODES); return null }
            if (depth >= budget.maxDepth) { budget.cut(CUT_DEPTH); return null }
            val secret = inSecret || node.isPassword
            val fields = listOf(
                "package" to plain(node.packageName, budget),
                "class" to plain(node.className, budget),
                "resource_id" to plain(node.resourceId, budget),
                "text" to if (secret) JsonNull else plain(node.text, budget),
                "description" to if (secret) JsonNull else plain(node.description, budget),
            )
            val chars = NODE_OVERHEAD + fields.sumOf { (_, v) -> if (v is JsonNull) 0L else v.content.length.toLong() }
            if (!budget.charge(chars)) return null
            budget.nodes++
            val children = buildJsonArray {
                val count = node.childCount
                for (i in 0 until count) {
                    if (budget.nodes >= budget.maxNodes) { budget.cut(CUT_NODES); break }
                    if (depth + 1 >= budget.maxDepth) { budget.cut(CUT_DEPTH); break }
                    if (budget.output + NODE_OVERHEAD > budget.maxOutput) { budget.cut(CUT_OUTPUT); break }
                    val child = node.child(i) ?: continue
                    node(child, budget, depth + 1, secret)?.let { add(it) }
                }
            }
            return buildJsonObject {
                for ((k, v) in fields) put(k, v)
                put("password", secret)
                put("bounds", bounds(node.bounds))
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("enabled", node.isEnabled)
                put("visible", node.isVisible)
                put("children", children)
            }
        } finally {
            node.release()
        }
    }

    fun plain(value: CharSequence?, budget: InspectBudget): JsonPrimitive {
        if (value == null) return JsonNull
        val s = value.toString()
        if (s.length <= budget.maxText) return JsonPrimitive(s)
        budget.cut(CUT_TEXT)
        // 不把一个字符的代理对切成两半
        val end = if (s[budget.maxText - 1].isHighSurrogate()) budget.maxText - 1 else budget.maxText
        return JsonPrimitive(s.substring(0, end))
    }

    /** 窗口的读取顺序:获得焦点的在前,其次是活动的,其余按显示屏、系统给的顺序;预算先给前面的。 */
    fun <W> windowOrder(windows: List<W>, focused: (W) -> Boolean, active: (W) -> Boolean): List<W> =
        windows.withIndex().sortedWith(
            compareBy<IndexedValue<W>>({ if (focused(it.value)) 0 else 1 }, { if (active(it.value)) 0 else 1 }, { it.index }),
        ).map { it.value }

    fun bounds(b: IntArray): JsonArray = buildJsonArray { for (v in b.take(4)) add(JsonPrimitive(v)) }
}
