package com.wangzan101.mika.mcp.ui

/** MCP 服务器页里的几层:[List] 是这一页的首页,其余是服务目录、引导连接、服务管理和自定义服务器。 */
internal enum class McpRoute { List, Catalog, Connect, Manage, Custom }

/**
 * 返回上一层。顶栏返回按钮和系统返回键都按这张表走;[List] 返回 null,表示再返回就离开这一页。
 * [customFrom] 是「自定义服务器」从哪一级进来的(列表或目录):返回时回到那里,不跳过一层。
 */
internal fun McpRoute.up(customFrom: McpRoute): McpRoute? = when (this) {
    McpRoute.List -> null
    McpRoute.Catalog -> McpRoute.List
    McpRoute.Connect -> McpRoute.Catalog
    McpRoute.Manage -> McpRoute.List
    McpRoute.Custom -> customFrom
}
