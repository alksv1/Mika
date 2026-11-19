package com.wangzan101.mika.core.cap.devicefiles

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.serialization.json.*
import java.io.IOException

internal const val DEVICE_FILES = "device_files"

internal class DeviceFilesCapability(val port: DeviceFilesPort, val workspace: () -> String) : Capability {
    override val id = DEVICE_FILES
    override fun status() = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = DeviceFilesGroup.tools
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && tools().any { it.name in env.offered }) listOf(env.gate(Prompts.capability(id))) else emptyList()
    companion object { val KEY = CapabilityKey<DeviceFilesCapability>(DEVICE_FILES) }
}

private interface DeviceFileTool : AgentTool {
    override val capability get() = DEVICE_FILES
    override val offScreen get() = true
    override fun available(ctx: ToolContext) = ctx[DeviceFilesCapability.KEY] != null
}

private fun error(code: String, message: String, recoverable: Boolean = true) = ToolExecution(buildJsonObject {
    put("ok", false); put("error", code); put("message", message); put("recoverable", recoverable)
})

private suspend fun guarded(block: suspend () -> ToolExecution): ToolExecution = try { block() }
catch (e: SandboxError) { error(e.code, e.message ?: e.code, e.recoverable) }
catch (e: SecurityException) { error("device_access_denied", "Device file access was denied.") }
catch (e: IOException) { error("device_file_io", "Could not access or copy the file.") }

private object ListDeviceFiles : DeviceFileTool {
    override val verb = Verb.ListDeviceFiles
    override val label = "查看设备文件"
    override val risk = Risk.Read
    override val def = ToolDef("list_device_files", Prompts.tool("list_device_files"), mapOf(
        "path" to str("Directory identifier from a previous listing; omit to list accessible roots."),
        "cursor" to str("Cursor returned by the preceding listing."),
        "limit" to int("Maximum entries, 1-100 (default 100).", 1, 100),
    ), emptyList())
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guarded {
        val path = a.str("path", max = 4096)
        val cursor = a.str("cursor", max = 4096)
        val limit = a.int("limit", 1, 100) ?: 100
        val page = ctx[DeviceFilesCapability.KEY]!!.port.list(path, cursor, limit)
        ToolExecution(buildJsonObject {
            put("ok", true)
            put("entries", JsonArray(page.entries.map { entry -> buildJsonObject {
                put("name", entry.name); put("path", entry.path); put("isDirectory", entry.isDirectory)
                entry.size?.let { put("size", it) }
            } }))
            page.nextCursor?.let { put("nextCursor", it) }
        })
    }
}

private object CopyDeviceFile : DeviceFileTool {
    override val verb = Verb.CopyDeviceFile
    override val label = "复制设备文件"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("copy_device_file", Prompts.tool("copy_device_file"), mapOf(
        "path" to str("Source file identifier from list_device_files."),
    ), listOf("path"))
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guarded {
        val source = a.str("path", max = 4096)!!
        val cap = ctx[DeviceFilesCapability.KEY]!!
        val imported = cap.port.importFile(source, cap.workspace())
        ToolExecution(buildJsonObject { put("ok", true); put("path", imported.path); put("bytesCopied", imported.size) })
    }
}

private object WriteDeviceFile : DeviceFileTool {
    override val verb = Verb.WriteDeviceFile
    override val label = "写入设备文件"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("write_device_file", Prompts.tool("write_device_file"), mapOf(
        "path" to str("Destination file identifier from the write target in list_device_directory_hints."),
        "content" to str("UTF-8 text to write, at most 1 MiB. Use content or source, not both."),
        "source" to str("Workspace file to copy as bytes. Use source or content, not both."),
        "expected_sha256" to str("SHA-256 of the current file when replacing or appending."),
        "append" to bool("Append instead of replacing (default false)."),
    ), listOf("path"))
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guarded {
        val cap = ctx[DeviceFilesCapability.KEY]!!
        val path = a.str("path", max = 4096)!!
        val inline = a.str("content", min = 0)
        val source = a.str("source", max = 4096)
        if ((inline == null) == (source == null)) throw SandboxError("invalid", "Provide exactly one of content or source.", true)
        val content = inline?.let { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > 1_048_576) throw SandboxError("file_too_large", "Inline device text is limited to 1 MiB. Use source for a workspace file.", true)
            bytes
        }
        val expected = a.str("expected_sha256", max = 64)
        val append = a.bool("append", false)
        val result = cap.port.writeFile(path, content, expected, append, source, cap.workspace())
        ToolExecution(buildJsonObject {
            put("ok", true)
            put("path", result.path)
            put("bytesWritten", result.size)
            put("sha256", result.sha256)
        })
    }
}

private object SearchDeviceFiles : DeviceFileTool {
    override val verb = Verb.SearchDeviceFiles
    override val label = "搜索设备文件"
    override val risk = Risk.Read
    override val def = ToolDef("search_device_files", Prompts.tool("search_device_files"), mapOf(
        "path" to str("Directory to search."), "name" to str("Case-insensitive filename substring."),
        "extension" to str("File extension, with or without a leading dot."),
        "modified_after" to str("Inclusive Unix timestamp in milliseconds."), "modified_before" to str("Inclusive Unix timestamp in milliseconds."),
        "max_depth" to int("Maximum directory depth, 0-10 (default 3).", 0, 10),
        "max_entries" to int("Maximum visited entries, 1-5000 (default 1000).", 1, 5000),
        "timeout_ms" to int("Time budget in milliseconds, 1-5000 (default 2000).", 1, 5000),
    ), listOf("path"))
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guarded {
        fun timestamp(key: String): Long? = a.str(key, max = 19)?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw SandboxError("invalid", "Invalid timestamp.", true) }
        val query = DeviceFileSearchQuery(a.str("path", max = 4096)!!, a.str("name", max = 255), a.str("extension", max = 255), timestamp("modified_after"), timestamp("modified_before"), a.int("max_depth", 0, 10) ?: 3, a.int("max_entries", 1, 5000) ?: 1000, a.int("timeout_ms", 1, 5000) ?: 2000)
        val result = ctx[DeviceFilesCapability.KEY]!!.port.search(query)
        ToolExecution(buildJsonObject {
            put("ok", true); put("visited", result.visited); put("truncated", result.truncated)
            result.reason?.let { put("reason", it) }
            put("entries", JsonArray(result.entries.map { buildJsonObject { put("name", it.name); put("path", it.path) } }))
        })
    }
}

private fun accessJson(access: DeviceAccess) = buildJsonObject {
    put("state", access.state)
    put("source", access.source)
    access.reason?.let { put("reason", it) }
    access.target?.let { put("target", it) }
    put("operations", JsonArray(access.operations.map { JsonPrimitive(it) }))
}

private fun hintJson(h: DeviceDirectoryHint) = buildJsonObject {
    put("path", h.path); put("name", h.name); put("description", h.description); put("source", h.source); put("status", h.status)
    put("read", accessJson(h.read))
    put("write", accessJson(h.write))
}
private object ListDirectoryHints : DeviceFileTool {
    override val verb = Verb.ListDeviceDirectoryHints
    override val risk = Risk.Read
    override val def = ToolDef("list_device_directory_hints", Prompts.tool("list_device_directory_hints"), emptyMap(), emptyList())
    override suspend fun execute(a: Args, ctx: ToolContext) = guarded {
        ToolExecution(buildJsonObject { put("ok", true); put("directories", JsonArray(ctx[DeviceFilesCapability.KEY]!!.port.listDirectoryHints().map(::hintJson))) })
    }
}
private object UpdateDirectoryDescription : DeviceFileTool {
    override val verb = Verb.UpdateDeviceDirectoryDescription
    override val risk = Risk.Write
    override val def = ToolDef("update_device_directory_description", Prompts.tool("update_device_directory_description"), mapOf("path" to str("Existing directory identifier."), "description" to str("Short directory description.")), listOf("path", "description"))
    override suspend fun execute(a: Args, ctx: ToolContext) = guarded {
        val hint = ctx[DeviceFilesCapability.KEY]!!.port.updateDirectoryDescription(a.str("path", max = 4096)!!, a.str("description", max = 500)!!)
        ToolExecution(buildJsonObject { put("ok", true); put("directory", hintJson(hint)) })
    }
}

internal object DeviceFilesGroup { val tools: List<AgentTool> = listOf(ListDeviceFiles, SearchDeviceFiles, CopyDeviceFile, WriteDeviceFile, ListDirectoryHints, UpdateDirectoryDescription) }
