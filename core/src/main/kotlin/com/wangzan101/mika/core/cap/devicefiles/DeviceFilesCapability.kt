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

internal object DeviceFilesGroup { val tools: List<AgentTool> = listOf(ListDeviceFiles, CopyDeviceFile) }
