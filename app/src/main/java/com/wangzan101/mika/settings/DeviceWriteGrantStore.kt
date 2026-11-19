package com.wangzan101.mika.settings

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.security.MessageDigest

/** The app-side SAF grant for one logical device directory. The URI never leaves this layer. */
data class DeviceWriteGrantStatus(
    val state: String,
    val reason: String? = null,
    val operations: List<String> = emptyList(),
    val target: String? = null,
)

data class DeviceWriteTarget internal constructor(val uri: Uri, val parts: List<String>)

class DeviceWriteGrantStore(
    private val resolver: ContentResolver,
    private val prefs: android.content.SharedPreferences,
) {
    fun status(path: String): DeviceWriteGrantStatus {
        val uri = uriFor(path)
        if (uri == null) {
            return if (restrictedTree(path)) {
                DeviceWriteGrantStatus("android_restricted", "android_restricted_tree", target = writeTarget(path))
            } else {
                DeviceWriteGrantStatus("not_granted", "choose_write_folder", target = writeTarget(path))
            }
        }
        val target = writeTarget(path)
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: return DeviceWriteGrantStatus("revoked", "persisted_permission_missing", target = target)
        if (!permission.isReadPermission || !permission.isWritePermission) {
            return DeviceWriteGrantStatus("provider_read_only", "write_permission_missing", target = target)
        }
        return try {
            val root = metadata(uri)
            when {
                root.mimeType != DocumentsContract.Document.MIME_TYPE_DIR ->
                    DeviceWriteGrantStatus("provider_unavailable", "not_a_directory", target = target)
                root.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0 ->
                    DeviceWriteGrantStatus("provider_read_only", "create_not_supported", target = target)
                else -> DeviceWriteGrantStatus("available", operations = listOf("create", "replace"), target = target)
            }
        } catch (_: SecurityException) {
            DeviceWriteGrantStatus("revoked", "provider_denied", target = target)
        } catch (_: Exception) {
            DeviceWriteGrantStatus("provider_unavailable", "metadata_unavailable", target = target)
        }
    }

    fun resolveTarget(path: String): DeviceWriteTarget? {
        if (!path.startsWith("/device-write/") || path.length > 4096) return null
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val uriString = value as? String ?: continue
            val rawPath = key.removePrefix(KEY_PREFIX)
            val base = writeTarget(rawPath)
            if (path != base && !path.startsWith("$base/")) continue
            val relative = path.removePrefix(base).trimStart('/')
            val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
            if (parts.any { part ->
                    part.isEmpty() || part == "." || part == ".." || part.length > 255 ||
                        part.any { character -> character.code < 32 || character == '\\' }
                }) return null
            return DeviceWriteTarget(Uri.parse(uriString), parts)
        }
        return null
    }

    fun authorize(path: String, uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "Choose a folder using the system picker." }
        resolver.takePersistableUriPermission(uri, ACCESS_FLAGS)
        prefs.edit().putString(key(path), uri.toString()).commitOrThrow()
        val status = status(path)
        if (status.state != "available") {
            prefs.edit().remove(key(path)).commitOrThrow()
            release(uri)
            throw IllegalArgumentException(status.reason ?: "This folder cannot be written.")
        }
    }

    fun revoke(path: String) {
        val uri = uriFor(path) ?: return
        prefs.edit().remove(key(path)).commitOrThrow()
        release(uri)
    }

    private fun uriFor(path: String): Uri? = prefs.getString(key(path), null)?.let(Uri::parse)

    private fun release(uri: Uri) {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    private data class RootMetadata(val mimeType: String, val flags: Int)

    private fun metadata(tree: Uri): RootMetadata {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("Missing tree document")
            RootMetadata(cursor.getString(0), cursor.getInt(1))
        } ?: throw IllegalStateException("Cannot read tree metadata")
    }

    private fun restrictedTree(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val user = android.os.Process.myUid() / 100000
        val shared = "/storage/emulated/$user"
        return path == "/sdcard" || path == shared ||
            path == "/sdcard/Download" || path == "$shared/Download"
    }

    private fun writeTarget(path: String): String = "/device-write/" + sha256(path).take(24)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun key(path: String) = "uri:$path"

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Could not save device write permission" }
    }

    companion object {
        private const val KEY_PREFIX = "uri:"
        private val ACCESS_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun get(context: Context) = DeviceWriteGrantStore(
            context.applicationContext.contentResolver,
            context.applicationContext.getSharedPreferences("device_write_grants", Context.MODE_PRIVATE),
        )
    }
}
