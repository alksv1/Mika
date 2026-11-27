package com.wangzan101.mika.mcp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MCP 服务器页内部的返回:每次只回上一层,列表才离开这一页;自定义服务器回到进来的那一级。 */
class McpRouteTest {
    @Test
    fun backGoesUpOneLevelAtATime() {
        assertNull(McpRoute.List.up(McpRoute.List))
        assertEquals(McpRoute.List, McpRoute.Catalog.up(McpRoute.List))
        assertEquals(McpRoute.Catalog, McpRoute.Connect.up(McpRoute.List))
        assertEquals(McpRoute.List, McpRoute.Manage.up(McpRoute.List))
    }

    @Test
    fun customServerReturnsToWhereItWasOpened() {
        assertEquals(McpRoute.Catalog, McpRoute.Custom.up(McpRoute.Catalog))
        assertEquals(McpRoute.List, McpRoute.Custom.up(McpRoute.List))
        // 从服务管理页新建/编辑时,editing 不为空,调用方传 Manage
        assertEquals(McpRoute.Manage, McpRoute.Custom.up(McpRoute.Manage))
    }
}
