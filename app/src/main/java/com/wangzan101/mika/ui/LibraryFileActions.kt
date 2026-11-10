package com.wangzan101.mika.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.SharedDraftCopy
import com.wangzan101.mika.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 文件库的来源；工作区路径必须是 /workspace/ 下的路径。 */
sealed interface LibraryFileHandle {
    data class Workspace(val conversationId: String, val path: String) : LibraryFileHandle
    data class Exported(val file: SharedFile) : LibraryFileHandle
}

enum class LibraryAttachmentResult { Added, Full, Unavailable, Failed }

/** 批量加入前的规划；[accepted] 已去掉重复来源并截到目标草稿的剩余槽位。 */
data class LibraryAddPlan(
    val accepted: List<LibraryFileHandle>,
    val duplicates: Int,
    val overLimit: Int,
)

/** 已经复制好、等着合并的一项；[release] 在没被收下时释放副本。 */
data class LibraryStaged(
    val attachment: PendingAttachment,
    val source: LibraryFileHandle,
    val release: () -> Unit,
)

/** 一批文件合并进草稿的结果；[unavailable] 表示目标草稿整批收不下。 */
data class LibraryMergeResult(
    val added: Int,
    val duplicates: Int,
    val full: Int,
    val unavailable: Boolean = false,
)

/** 文件库批量加入的最终结果，供界面汇总提示。 */
data class LibraryBatchOutcome(
    val added: Int,
    val duplicates: Int,
    val overLimit: Int,
    val failed: List<String>,
    val unavailable: Boolean = false,
) {
    val accepted: Int get() = added + duplicates
}

/** 文件库操作。工作区文件经闸门复制；导出文件预览和分享使用原 MediaStore URI。 */
object LibraryFileActions {
    private val startupLock = Any()
    private var orphanCleanupDone = false

    /** 普通附件草稿不跨进程恢复；本进程首次创建副本前清理上次进程遗留的副本。 */
    private fun cleanupOrphanedDraftCopies(context: Context) = synchronized(startupLock) {
        if (!orphanCleanupDone) {
            File(context.filesDir, "library-attachments").listFiles()?.forEach { it.deleteRecursively() }
            orphanCleanupDone = true
        }
    }

    /** 即时检查，只代表检查时可读；后续操作仍会重新打开来源。 */
    suspend fun isAvailable(context: Context, source: LibraryFileHandle): Boolean = withContext(Dispatchers.IO) {
        try {
            when (source) {
                is LibraryFileHandle.Workspace -> {
                    require(source.conversationId.isNotBlank())
                    val rel = requireNotNull(AttachFiles.relative(source.path))
                    val dirs = SandboxDirs(context)
                    dirs.gate.openRead(dirs.workspace(source.conversationId), rel).use { it.input().read() }
                }
                is LibraryFileHandle.Exported -> {
                    val uri = Uri.parse(source.file.uri)
                    require(uri.scheme == "content" && uri.authority == "media")
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { it.read() }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun preview(context: Context, source: LibraryFileHandle): Boolean =
        show(context, source) { uri, name -> FileViewer.open(context, uri, name) }

    suspend fun share(context: Context, source: LibraryFileHandle): Boolean =
        show(context, source) { uri, name -> FileViewer.share(context, uri, name) }

    /** 先复制来源，再在主线程合并目标草稿；复制期间切换会话不会改变目标。 */
    suspend fun addToDraft(context: Context, viewModel: ChatViewModel, targetConversationId: String,
                           source: LibraryFileHandle): LibraryAttachmentResult {
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val copy = stage(context, source, forDraft = true) ?: return@withContext LibraryAttachmentResult.Failed
            var accepted = false
            try {
                val result = withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    viewModel.addLibraryAttachment(targetConversationId, copy.attachment, source) { copy.file.parentFile?.deleteRecursively() }
                }
                accepted = result == LibraryAttachmentResult.Added
                result
            } finally {
                if (!accepted) copy.file.parentFile?.deleteRecursively()
            }
        }
    }

    /**
     * 批量加入:先按目标草稿规划,再逐个复制,最后一次合并。
     * 复制期间取消时,当前文件复制完就停,没合并的副本全部释放。
     */
    suspend fun addAllToDraft(
        context: Context,
        viewModel: ChatViewModel,
        targetConversationId: String,
        sources: List<LibraryFileHandle>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryBatchOutcome {
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val plan = withContext(Dispatchers.Main.immediate) {
                caller.ensureActive()
                viewModel.planLibraryAttachments(targetConversationId, sources)
            } ?: return@withContext LibraryBatchOutcome(0, 0, 0, emptyList(), unavailable = true)
            val staged = mutableListOf<LibraryStaged>()
            val failed = mutableListOf<String>()
            // 合并调用一旦返回,副本就归 ChatViewModel 管;取消或异常时只释放还没合并的。
            var committed = false
            try {
                withContext(Dispatchers.Main.immediate) { onProgress(0, plan.accepted.size) }
                plan.accepted.forEachIndexed { index, source ->
                    caller.ensureActive()
                    val copy = stage(context, source, forDraft = true)
                    if (copy == null) failed += nameOf(source)
                    else staged += LibraryStaged(copy.attachment, source) { copy.file.parentFile?.deleteRecursively() }
                    withContext(Dispatchers.Main.immediate) { onProgress(index + 1, plan.accepted.size) }
                }
                val merged = withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    viewModel.addLibraryAttachments(targetConversationId, staged, plan.overLimit).also { committed = true }
                }
                LibraryBatchOutcome(
                    added = merged.added,
                    duplicates = plan.duplicates + merged.duplicates,
                    overLimit = plan.overLimit + merged.full,
                    failed = failed,
                    unavailable = merged.unavailable,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!committed) staged.forEach { runCatching { it.release() } }
                throw e
            } catch (_: Exception) {
                if (!committed) staged.forEach { runCatching { it.release() } }
                LibraryBatchOutcome(0, plan.duplicates, plan.overLimit, failed, unavailable = false)
            }
        }
    }

    private fun nameOf(source: LibraryFileHandle): String = when (source) {
        is LibraryFileHandle.Workspace -> source.path.substringAfterLast('/')
        is LibraryFileHandle.Exported -> source.file.name
    }

    private suspend fun show(context: Context, source: LibraryFileHandle, open: (Uri, String) -> Unit): Boolean {
        if (source is LibraryFileHandle.Exported) {
            if (!isAvailable(context, source)) return false
            return try {
                withContext(Dispatchers.Main.immediate) {
                    open(Uri.parse(source.file.uri), source.file.name)
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val copy = stage(context, source, forDraft = false) ?: return@withContext false
            var opened = false
            try {
                withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    open(Uri.parse(copy.attachment.uri), copy.attachment.name)
                }
                opened = true
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                if (!opened) copy.file.parentFile?.deleteRecursively()
            }
        }
    }

    private data class Copy(val file: File, val attachment: PendingAttachment)

    private suspend fun stage(context: Context, source: LibraryFileHandle, forDraft: Boolean): Copy? =
        withContext(Dispatchers.IO + NonCancellable) {
            cleanupOrphanedDraftCopies(context)
            val name = when (source) {
                is LibraryFileHandle.Workspace -> source.path.substringAfterLast('/')
                is LibraryFileHandle.Exported -> source.file.name
            }.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").take(180)
                .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
            val root = if (forDraft) File(context.filesDir, "library-attachments") else File(context.cacheDir, "library-viewing")
            if (!forDraft) {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
                root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            }
            val dir = File(root, UUID.randomUUID().toString())
            try {
                check(dir.mkdirs())
                val file = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                when (source) {
                    is LibraryFileHandle.Workspace -> {
                        require(source.conversationId.isNotBlank())
                        val rel = requireNotNull(AttachFiles.relative(source.path))
                        val dirs = SandboxDirs(context)
                        dirs.gate.openRead(dirs.workspace(source.conversationId), rel).use { gated ->
                            SharedDraftCopy.copy(gated.input(), tmp, file) { dir.usableSpace }
                        }
                    }
                    is LibraryFileHandle.Exported -> {
                        val uri = Uri.parse(source.file.uri)
                        require(uri.scheme == "content" && uri.authority == "media")
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            SharedDraftCopy.copy(input, tmp, file) { dir.usableSpace }
                        }
                    }
                }
                dir.setLastModified(System.currentTimeMillis())
                val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file)
                Copy(file, PendingAttachment(uri.toString(), name, file.length()))
            } catch (_: Exception) {
                dir.deleteRecursively()
                null
            }
        }
}
