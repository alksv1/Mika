package com.wangzan101.mika.core.cap.devicefiles

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DeviceFilesTest {
    private class Port : DeviceFilesPort {
        var listed: Triple<String?, String?, Int>? = null
        var imported: Pair<String, String>? = null
        var fail = false
        var query: DeviceFileSearchQuery? = null
        var hints = emptyList<DeviceDirectoryHint>()
        var written: Quadruple? = null

        data class Quadruple(val path: String, val content: ByteArray, val expectedSha256: String?, val append: Boolean)
        override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult {
            this.query = query
            return DeviceFileSearchResult(listOf(DeviceFileEntry("a.txt", "/sdcard/a.txt", false)), 12, true, "max_entries")
        }
        override suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage {
            listed = Triple(path, cursor, limit)
            return DeviceFilePage(listOf(DeviceFileEntry("a.txt", "dir/a.txt", false, 3)), "next")
        }
        override suspend fun importFile(path: String, workspace: String): DeviceFileImport {
            if (fail) throw SandboxError("denied", "Access denied", true)
            imported = path to workspace
            return DeviceFileImport("/workspace/inbox/a.txt", 3)
        }
        override suspend fun listDirectoryHints(): List<DeviceDirectoryHint> = hints
        override suspend fun writeFile(path: String, content: ByteArray, expectedSha256: String?, append: Boolean): DeviceFileWrite {
            written = Quadruple(path, content, expectedSha256, append)
            return DeviceFileWrite(path, content.size.toLong(), "hash")
        }
    }
    private fun call(name: String, args: String) = Part.ToolCall("1", name, args)
    private fun spec(origin: RunOrigin = RunOrigin.Ui, attendance: Attendance = Attendance.Present, readOnly: Boolean = false) =
        RunSpec(RunId("test"), "test", "test", provenance = RunProvenance(origin, attendance), readOnly = readOnly)

    @Test fun availabilityIsLimitedToAttendedUi() {
        val port = Port()
        fun names(spec: RunSpec) = toolDispatcher(FakeScreen(), deviceFiles = port, runSpec = spec).definitions().map { it.name }
        assertFalse(toolDispatcher(FakeScreen()).definitions().any { it.name == "list_device_files" })
        assertTrue("list_device_files" in names(spec()))
        assertTrue("copy_device_file" in names(spec()))
        assertFalse("copy_device_file" in names(spec(readOnly = true)))
        for (origin in listOf(RunOrigin.Channel, RunOrigin.Trigger, RunOrigin.Mcp)) assertFalse("list_device_files" in names(spec(origin)))
        val child = toolDispatcher(FakeScreen(), web = com.wangzan101.mika.core.cap.web.FakeWebPort(), deviceFiles = port, runSpec = spec(RunOrigin.Child))
        assertFalse(child.definitions().any { it.name == "list_device_files" })
        assertFalse("list_device_files" in names(spec(attendance = Attendance.Unattended)))
    }

    @Test fun searchForwardsFiltersAndReportsTruncation() = runTest {
        val port = Port()
        val dispatcher = toolDispatcher(FakeScreen(), deviceFiles = port)
        val response = dispatcher.execute(call("search_device_files", """{"path":"/sdcard/docs","name":"report","extension":"pdf","modified_after":"1700000000000","max_depth":2,"max_entries":12,"timeout_ms":500}"""), ActionPass()).content
        assertEquals(DeviceFileSearchQuery("/sdcard/docs", "report", "pdf", 1700000000000, null, 2, 12, 500), port.query)
        assertEquals("true", response.getValue("truncated").jsonPrimitive.content)
        assertEquals("max_entries", response.getValue("reason").jsonPrimitive.content)
        assertEquals("/sdcard/a.txt", response.getValue("entries").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test fun directoryHintsExposeReadAndWriteIndependently() = runTest {
        val port = Port().apply {
            hints = listOf(
                DeviceDirectoryHint(
                    "/sdcard/Download",
                    "Download",
                    "downloads",
                    "preset",
                    "denied",
                    read = DeviceAccess("available", "shell", operations = listOf("list")),
                    write = DeviceAccess("android_restricted", "saf", "restricted_tree"),
                ),
            )
        }
        val dispatcher = toolDispatcher(FakeScreen(), deviceFiles = port)
        val response = dispatcher.execute(call("list_device_directory_hints", "{}"), ActionPass()).content
        val directory = response.getValue("directories").jsonArray.single().jsonObject
        assertEquals("available", directory.getValue("read").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("shell", directory.getValue("read").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("android_restricted", directory.getValue("write").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("restricted_tree", directory.getValue("write").jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test fun writeForwardsTextAndConcurrencyArguments() = runTest {
        val port = Port()
        val dispatcher = toolDispatcher(FakeScreen(), deviceFiles = port)
        val response = dispatcher.execute(
            call("write_device_file", """{"path":"/device-write/abc/note.txt","content":"hello","expected_sha256":"old","append":true}"""),
            ActionPass(),
        ).content
        assertEquals("/device-write/abc/note.txt", port.written!!.path)
        assertEquals("hello", port.written!!.content.toString(Charsets.UTF_8))
        assertEquals("old", port.written!!.expectedSha256)
        assertTrue(port.written!!.append)
        assertEquals("hash", response.getValue("sha256").jsonPrimitive.content)
    }

    @Test fun listAndImportForwardArgumentsAndErrors() = runTest {
        val port = Port()
        val dispatcher = toolDispatcher(FakeScreen(), deviceFiles = port, workspace = { "conversation" })
        val list = dispatcher.execute(call("list_device_files", """{"path":"dir","cursor":"a","limit":2}"""), ActionPass()).content
        assertEquals(Triple("dir", "a", 2), port.listed)
        assertEquals("next", list.getValue("nextCursor").jsonPrimitive.content)
        assertEquals("dir/a.txt", list.getValue("entries").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
        val copied = dispatcher.execute(call("copy_device_file", """{"path":"dir/a.txt"}"""), ActionPass()).content
        assertEquals("dir/a.txt" to "conversation", port.imported)
        assertEquals("/workspace/inbox/a.txt", copied.getValue("path").jsonPrimitive.content)
        port.fail = true
        val failure = dispatcher.execute(call("copy_device_file", """{"path":"bad"}"""), ActionPass()).content
        assertEquals("denied", failure.getValue("error").jsonPrimitive.content)
    }
}
