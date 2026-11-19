package com.wangzan101.mika.task

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileWrite
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.settings.DeviceWriteGrantStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Writes UTF-8 text through a persisted SAF tree grant. The URI stays inside the app. */
internal class SafDeviceFileWriter(
    private val resolver: ContentResolver,
    private val grants: DeviceWriteGrantStore,
) {
    fun write(path: String, content: ByteArray, expectedSha256: String?, append: Boolean): DeviceFileWrite = synchronized(lock) {
        guarded {
            checkText(content)
            val target = grants.resolveTarget(path) ?: fail("device_write_denied", "The device write location is not authorized.")
            val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == target.uri }
                ?: fail("device_write_revoked", "The device write permission was revoked. Choose the folder again.")
            if (!permission.isReadPermission || !permission.isWritePermission)
                fail("device_write_denied", "The device folder does not allow writing.")

            val tree = target.uri
            val root = metadata(document(tree, DocumentsContract.getTreeDocumentId(tree)))
            if (!root.directory) fail("not_a_directory", "The device write target is not a folder.")
            if (target.parts.isEmpty()) fail("not_a_file", "Choose a file inside the device folder.")
            val parent = descend(tree, root.id, target.parts.dropLast(1))
            if (!parent.directory) fail("not_a_directory", "The device file parent is not a folder.")
            val name = target.parts.last()
            val existing = find(tree, parent.id, name)
            val old = existing?.let {
                if (it.directory) fail("not_a_file", "The device target is a folder.")
                if (it.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0)
                    fail("device_write_denied", "The provider does not allow changing this file.")
                readText(document(tree, it.id), it)
            }
            if (old != null && (expectedSha256 == null || sha256(old) != expectedSha256))
                fail("file_changed", "The device file changed. Read it again before updating it.")
            if (old == null && expectedSha256 != null)
                fail("file_changed", "The device file no longer exists. Nothing was written.")

            val bytes = if (append && old != null) old + content else content
            checkText(bytes)
            if (existing == null && parent.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0)
                fail("device_write_denied", "The provider does not allow creating files here.")

            val uri = existing?.let { document(tree, it.id) } ?: DocumentsContract.createDocument(
                resolver,
                document(tree, parent.id),
                if (name.endsWith(".md", ignoreCase = true)) "text/markdown" else "text/plain",
                name,
            ) ?: fail("device_write_failed", "The device file could not be created.")
            try {
                writeBytes(uri, bytes)
                val written = readText(uri, metadata(uri))
                if (!written.contentEquals(bytes)) throw IOException("Read-back mismatch")
            } catch (error: Exception) {
                val restored = runCatching {
                    if (old == null) DocumentsContract.deleteDocument(resolver, uri)
                    else {
                        writeBytes(uri, old)
                        readText(uri, metadata(uri)).contentEquals(old)
                    }
                }.getOrDefault(false)
                fail(
                    "device_write_failed",
                    if (restored) "Saving failed; the previous contents were preserved."
                    else "Saving failed and the device file may have changed. Read it before trying again.",
                )
            }
            DeviceFileWrite(path, bytes.size.toLong(), sha256(bytes))
        }
    }

    private data class Doc(val id: String, val name: String, val mimeType: String, val flags: Int, val size: Long?) {
        val directory get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun descend(tree: Uri, root: String, parts: List<String>): Doc {
        var current = metadata(document(tree, root))
        for (part in parts) current = find(tree, current.id, part)
            ?: fail("file_not_found", "The device file or folder does not exist.")
        return current
    }

    private fun find(tree: Uri, parent: String, name: String): Doc? = children(tree, parent, 2001).filter { it.name == name }.singleOrNull()

    private fun children(tree: Uri, parent: String, limit: Int): List<Doc> {
        val result = mutableListOf<Doc>()
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
            COLUMNS,
            null,
            null,
            null,
        )?.use { cursor ->
            while (result.size < limit && cursor.moveToNext()) result += row(cursor)
        } ?: throw IOException("Cannot list device folder")
        if (result.size >= limit) fail("device_folder_too_large", "The device folder has too many entries.")
        return result
    }

    private fun metadata(uri: Uri): Doc = resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("Missing device document")
        row(cursor)
    } ?: throw IOException("Cannot read device file metadata")

    private fun row(cursor: android.database.Cursor) = Doc(
        cursor.getString(0),
        cursor.getString(1),
        cursor.getString(2),
        cursor.getInt(3),
        if (cursor.isNull(4)) null else cursor.getLong(4),
    )

    private fun document(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun readText(uri: Uri, doc: Doc): ByteArray {
        if (doc.directory) fail("not_a_file", "The device target is a folder.")
        if ((doc.size ?: 0) > MAX_BYTES) fail("file_too_large", "Device text files are limited to 1 MiB.")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) fail("file_too_large", "Device text files are limited to 1 MiB.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Cannot read device file")
        checkText(bytes)
        return bytes
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Cannot open device file for writing")
    }

    private fun checkText(bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) fail("file_too_large", "Device text files are limited to 1 MiB.")
        if (bytes.any { it == 0.toByte() }) fail("not_text", "Only UTF-8 text files are supported.")
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail("not_text", "Only UTF-8 text files are supported.")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: SandboxError) {
        throw error
    } catch (_: SecurityException) {
        fail("device_write_revoked", "The device write permission was revoked. Choose the folder again.")
    } catch (_: IOException) {
        fail("device_write_failed", "The device file could not be written.")
    }

    private fun fail(code: String, message: String): Nothing = throw SandboxError(code, message, true)

    companion object {
        private const val MAX_BYTES = 1_048_576
        private val lock = Any()
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
