package com.wangzan101.mika.core.cap.devicefiles

/** A device-visible entry. [path] is an opaque identifier understood by this port. */
data class DeviceFileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long? = null)
data class DeviceFilePage(val entries: List<DeviceFileEntry>, val nextCursor: String? = null)
data class DeviceFileImport(val path: String, val size: Long)

/**
 * Platform access to user-visible device files. Null path lists accessible roots.
 * Check platform permissions and allowed roots on every call; never expose app-private files.
 * List pages must be stable and contain at most [limit] entries.
 * Import only regular files into the named conversation's workspace, under its tree lock,
 * with size and storage limits and no overwrite. Remove partial files on failure or cancellation.
 * Return the actual /workspace path and size; throw SandboxError with a safe message on expected errors.
 */
interface DeviceFilesPort {
    suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage
    suspend fun importFile(path: String, workspace: String): DeviceFileImport
}
