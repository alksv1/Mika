package com.wangzan101.mika.cap.phone.shell

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Paths

/** Small Binder replies; paths are interpreted by the helper in its own mount namespace. */
internal object ShellFiles {
    private const val MAX_PATH = 4096
    private const val MAX_ENTRIES = 128

    private fun valid(path: String?) = path != null && path.startsWith('/') && path.length <= MAX_PATH && '\u0000' !in path
    private fun result(status: String) = Bundle().apply { putString("status", status) }

    private fun inside(path: String, root: String) = path == root || path.startsWith("$root/")

    private fun allowedPath(path: String, user: Int): Boolean =
        inside(path, "/sdcard") || inside(path, "/storage/emulated/$user")

    private fun checkedRoot(path: String, user: Int): String {
        if (!allowedPath(path, user)) throw SecurityException("outside shared storage")
        val root = java.io.File("/storage/emulated/$user").canonicalPath
        if (!inside(java.io.File(path).canonicalPath, root)) throw SecurityException("outside shared storage")
        return root
    }

    private fun checkFd(fd: java.io.FileDescriptor, root: String) {
        ParcelFileDescriptor.dup(fd).use { copy ->
            if (!inside(java.io.File("/proc/self/fd/${copy.fd}").canonicalPath, root)) throw SecurityException("outside shared storage")
        }
    }

    fun list(path: String?, afterName: String?, limit: Int, user: Int): Bundle {
        if (!valid(path) || limit !in 1..MAX_ENTRIES ||
            (afterName != null && (afterName.length > MAX_PATH || '/' in afterName || '\u0000' in afterName))) return result("invalid")
        return try {
            val root = checkedRoot(path!!, user)
            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) return result("not_directory")
                checkFd(fd, root)
                ParcelFileDescriptor.dup(fd).use { dir ->
                    val directory = "/proc/self/fd/${dir.fd}"
                    // Retain only the smallest limit+1 names after the cursor, even for huge directories.
                    val selected = java.util.PriorityQueue<String>(compareByDescending { it })
                    Files.newDirectoryStream(Paths.get(directory)).use { stream ->
                        for (entry in stream) {
                            val name = entry.fileName.toString()
                            if (afterName != null && name <= afterName) continue
                            selected.add(name)
                            if (selected.size > limit + 1) selected.poll()
                        }
                    }
                    val ordered = selected.sorted()
                    val names = ArrayList(ordered.take(limit))
                    val types = names.map { name ->
                        val mode = try { Os.lstat(Paths.get(directory, name).toString()).st_mode } catch (_: ErrnoException) { 0 }
                        when {
                            OsConstants.S_ISREG(mode) -> 1
                            OsConstants.S_ISDIR(mode) -> 2
                            else -> 0
                        }
                    }
                    result("ok").apply {
                        putStringArrayList("names", names)
                        putIntArray("types", types.toIntArray())
                        putString("next", if (ordered.size > limit) names.last() else null)
                    }
                }
            } finally { Os.close(fd) }
        } catch (e: Exception) {
            result(error(e))
        }
    }

    fun open(path: String?, user: Int): Bundle {
        if (!valid(path)) return result("invalid")
        return try {
            val root = checkedRoot(path!!, user)
            if (!OsConstants.S_ISREG(Os.stat(path).st_mode)) return result("not_file")
            // Recheck the opened object; NONBLOCK prevents a substituted FIFO from blocking.
            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                checkFd(fd, root)
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return result("not_file")
                result("ok").apply { putParcelable("fd", ParcelFileDescriptor.dup(fd)) }
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            result(error(e))
        }
    }

    private fun error(e: Exception): String = when (e) {
        is DirectoryIteratorException -> error(e.cause ?: e)
        is NoSuchFileException -> "not_found"
        is AccessDeniedException, is SecurityException -> "denied"
        is NotDirectoryException -> "not_directory"
        is ErrnoException -> when (e.errno) {
            OsConstants.ENOENT -> "not_found"
            OsConstants.EACCES, OsConstants.EPERM -> "denied"
            OsConstants.ENOTDIR -> "not_directory"
            else -> "io_error"
        }
        is IOException -> "io_error"
        else -> "io_error"
    }
}
