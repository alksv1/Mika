package com.wangzan101.mika.task

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Read device files through the shell helper and copy them into the conversation workspace. */
internal class AndroidDeviceFiles(
    private val hub: HelperHub,
    private val dirs: SandboxDirs,
    private val sandbox: AndroidSandbox,
) : DeviceFilesPort {
    override suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage = withContext(Dispatchers.IO) {
        if (path == null) return@withContext DeviceFilePage(listOf(DeviceFileEntry("手机存储", "/sdcard", true)))
        val response = callHelper { hub.listFiles(path, cursor, limit.coerceIn(1, 128)) }
        requireOk(response)
        val names = response.getStringArrayList("names") ?: arrayListOf()
        val types = response.getIntArray("types") ?: intArrayOf()
        if (names.size != types.size) throw failure("helper_error", "Invalid directory response from phone helper.")
        val entries = names.mapIndexedNotNull { index, name ->
            when (types[index]) {
                1, 2 -> DeviceFileEntry(name, path.trimEnd('/') + "/" + name, types[index] == 2)
                else -> null
            }
        }
        DeviceFilePage(entries, response.getString("next"))
    }

    override suspend fun importFile(path: String, workspace: String): DeviceFileImport = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val response = callHelper { hub.openFile(path) }
            requireOk(response)
            @Suppress("DEPRECATION")
            val descriptor = response.getParcelable<ParcelFileDescriptor>("fd")
                ?: throw failure("helper_error", "Phone helper did not return a file.")
            descriptor.use { fd ->
                val result = Attachments.importOne(
                    dirs.workspace(workspace), dirs.gate, Attachments.safeName(File(path).name),
                    sandbox::freeBytes, { sandbox.workspaceBytes(workspace) },
                    { ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)) },
                )
                val entry = result.added.singleOrNull()
                if (entry == null) {
                    val reason = result.failures.firstOrNull()?.reason?.name?.lowercase() ?: "io_error"
                    throw failure(reason, "Could not copy the device file to the workspace ($reason).")
                }
                DeviceFileImport(entry.first, entry.second)
            }
        }
    }

    private fun requireOk(response: Bundle) {
        val status = response.getString("status") ?: "helper_error"
        if (status != "ok") throw failure(status, "Could not access the device file ($status).")
    }

    private inline fun <T> callHelper(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw failure("helper_unavailable", "Phone helper is unavailable. Reconnect it and try again.")
    }

    private fun failure(code: String, message: String) = SandboxError(code, message, recoverable = true)
}
