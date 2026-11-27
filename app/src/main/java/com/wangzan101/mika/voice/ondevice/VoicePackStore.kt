package com.wangzan101.mika.voice.ondevice

import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 一个语音包在本机的状态,设置页的卡片按它显示。 */
enum class VoicePackStatus {
    NotInstalled,
    Installed,

    /** 当前版本的目录在,但文件缺了或大小对不上;要重新下载。 */
    Corrupt,

    /** 只装着旧版本:照常能用([VoicePackStore.installedAny]),卡片上提示有新版本,由用户决定更不更新。 */
    UpdateAvailable,
}

/**
 * 语音包在本机的存放:`<root>/<包id>/<版本>/<文件名>`,root 是 `filesDir/voice-models`(备份不包含这个目录)。
 * 下载和导入先写进 `<root>/.staging/<包id>-<版本>/`,全部通过后 [commit] 一次改名生效,再删旧版本;
 * 中途失败只丢 staging,已装好的版本不受影响。
 *
 * 加载时只核对文件大小,不重算哈希(几百 MB 太慢);哈希在下载、导入完成时校验。
 * [freeSpace] 只为测试可换,默认用 [StatFs] 取 root 所在分区的可用空间。
 *
 * 并发:[stagingDir]、[commit]、[abandon]、[delete]、[cleanup] 在同一进程里互斥(进程级的锁,不论建了几个实例),
 * 都是阻塞的文件操作,在后台线程调。[stagingDir] 给出的目录记为「正在用」,直到 [commit] 或 [abandon];
 * [cleanup] 不动正在用的 staging。进程被杀后登记随之清空,下次启动的 [cleanup] 就把残留清掉。
 * 所以下载和导入(Task 7)必须经这个类建 staging、提交或放弃,不能自己在 `.staging` 下建目录。
 *
 * 删除不跟随符号链接:链接本身删掉,链接指向的东西不动;不在 root 之下的东西也不删。
 */
class VoicePackStore(
    private val root: File,
    private val freeSpace: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    private val staging get() = File(root, STAGING)

    /** 当前版本装全了(每个文件都在、大小和清单一致)时返回它,否则 null。 */
    fun installed(entry: VoicePackEntry): InstalledVoicePack? {
        val dir = versionDir(entry.id, entry.version)
        return if (complete(entry, dir)) InstalledVoicePack(entry, dir) else null
    }

    /**
     * 能用的那一份:当前版本装全了就是它;否则是已装的旧版本里最新的一个。
     * 旧版本的大小清单里没有,只看文件(按当前清单的文件名)都在。
     */
    fun installedAny(entry: VoicePackEntry): InstalledVoicePack? =
        installed(entry) ?: olderVersions(entry).firstOrNull { (_, dir) -> filesPresent(entry, dir) }
            ?.let { (_, dir) -> InstalledVoicePack(entry, dir) }

    fun status(entry: VoicePackEntry): VoicePackStatus = when {
        installed(entry) != null -> VoicePackStatus.Installed
        versionDir(entry.id, entry.version).exists() -> VoicePackStatus.Corrupt
        installedAny(entry) != null -> VoicePackStatus.UpdateAvailable
        else -> VoicePackStatus.NotInstalled
    }

    /** 这个包这个版本的 staging 目录,每次都是新建的空目录(上次没下完的残留清掉),登记为正在用。 */
    fun stagingDir(entry: VoicePackEntry): File = synchronized(lock) {
        val dir = stagingFor(entry)
        deleteInside(dir)
        if (!dir.mkdirs()) throw IOException("cannot create $dir")
        active += dir.canonicalPath
        dir
    }

    /** [stagingDir] 会给出的路径;只算路径,不建目录、不登记。下载要事先拼好每个文件的目标路径时用。 */
    fun stagingPath(entry: VoicePackEntry): File = stagingFor(entry)

    /** 下载或导入失败、取消:删掉这个包这个版本的 staging,撤销正在用的登记。 */
    fun abandon(entry: VoicePackEntry) = synchronized(lock) {
        val dir = stagingFor(entry)
        active -= dir.canonicalPath
        deleteInside(dir)
    }

    /**
     * 把下好的 [stagingDir] 改名为 `<id>/<版本>/` 生效,再删掉这个包的其他版本。
     * staging 里文件不全或大小不对时抛 [IOException],什么都不动;改名失败也抛 [IOException],原来的版本留着。
     *
     * 同一版本重新下载时,装好的那份先挪到 `<id>/<版本>.previous`,新的改名成功后再删。挪在包目录里而不是
     * staging 里:哪一步进程被杀,下次 [cleanup] 都能找回一份能用的(新的没进来就把旧的挪回去)。
     */
    fun commit(entry: VoicePackEntry, stagingDir: File): InstalledVoicePack = synchronized(lock) {
        if (!complete(entry, stagingDir)) throw IOException("voice pack ${entry.id} staging is incomplete")
        val target = versionDir(entry.id, entry.version)
        target.parentFile?.mkdirs()
        val previous = asideFor(target)
        deleteInside(previous)
        if (target.exists() && !target.renameTo(previous)) throw IOException("cannot move aside $target")
        if (!stagingDir.renameTo(target)) {
            if (previous.exists()) previous.renameTo(target)
            throw IOException("cannot move $stagingDir to $target")
        }
        active -= stagingDir.canonicalPath
        target.parentFile?.listFiles()?.filter { it != target }?.forEach { deleteInside(it) }
        InstalledVoicePack(entry, target)
    }

    /** 删掉这个包的所有版本。 */
    fun delete(id: String) = synchronized(lock) {
        deleteInside(File(root, safeId(id)))
    }

    /**
     * App 启动时调一次(后台线程):清掉不在用的 staging 残留、清单里已经没有的包、每个包除「能用的那一份」以外的版本。
     * 先把 [commit] 中途被杀留下的 `<版本>.previous` 挪回原处(原处已有新的就不用它)。
     * 一份能用的都没有时留着当前版本目录(状态显示「已损坏」,提示重新下载),其余删掉。
     */
    fun cleanup(catalog: VoicePackCatalog) = synchronized(lock) {
        if (!root.isDirectory) return@synchronized
        staging.listFiles()?.filter { it.canonicalPath !in active }?.forEach { deleteInside(it) }
        if (staging.list()?.isEmpty() == true) staging.delete()
        root.listFiles()?.forEach { idDir ->
            if (idDir.name == STAGING) return@forEach
            val entry = catalog.entry(idDir.name)
            if (entry == null || isLink(idDir) || !idDir.isDirectory) {
                deleteInside(idDir)
                return@forEach
            }
            restoreAside(idDir)
            val keep = installedAny(entry)?.dir ?: versionDir(entry.id, entry.version)
            idDir.listFiles()?.filter { it != keep }?.forEach { deleteInside(it) }
        }
    }

    /** root 所在分区的可用字节数;下载、导入前要求至少有包大小加 [SPACE_MARGIN]。 */
    fun freeBytes(): Long {
        root.mkdirs()
        return freeSpace(root)
    }

    private fun stagingFor(entry: VoicePackEntry) = File(staging, "${safeId(entry.id)}-${entry.version}")

    private fun versionDir(id: String, version: Int) = File(File(root, safeId(id)), version.toString())

    private fun asideFor(target: File) = File(target.parentFile, target.name + ASIDE)

    /** `<版本>.previous` 的原处没有了(commit 挪开旧的后被杀):挪回去。 */
    private fun restoreAside(idDir: File) {
        idDir.listFiles()?.filter { it.name.endsWith(ASIDE) && !isLink(it) }?.forEach { aside ->
            val target = File(idDir, aside.name.removeSuffix(ASIDE))
            if (!target.exists() && !isLink(target)) aside.renameTo(target)
        }
    }

    /** 比当前版本旧的版本目录,新的在前。`.previous` 之类不是纯数字的名字不算。 */
    private fun olderVersions(entry: VoicePackEntry): List<Pair<Int, File>> =
        File(root, safeId(entry.id)).listFiles().orEmpty()
            .mapNotNull { dir -> dir.name.toIntOrNull()?.takeIf { it < entry.version && dir.isDirectory }?.let { it to dir } }
            .sortedByDescending { it.first }

    private fun complete(entry: VoicePackEntry, dir: File): Boolean =
        dir.isDirectory && entry.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.size } }

    private fun filesPresent(entry: VoicePackEntry, dir: File): Boolean =
        dir.isDirectory && entry.files.all { File(dir, it.name).isFile }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /**
     * 删掉 [f]:是符号链接就只删链接本身;不在 root 之下就不动;目录按不跟随链接的方式逐层删
     * (`File.deleteRecursively` 会顺着指向目录的链接删进去)。
     */
    private fun deleteInside(f: File) {
        val path = f.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        val base = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(base)) return
        // walkFileTree 默认不跟随链接:链接当成普通条目交给 visitFile,删的是链接本身
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 包 id 当目录名用:规则同 [VoicePackCatalog.isSafeName],另外不能和 staging 目录重名。 */
    private fun safeId(id: String): String {
        require(VoicePackCatalog.isSafeName(id) && id != STAGING) { "invalid voice pack id '$id'" }
        return id
    }

    companion object {
        /** 剩余空间要比包大小多出的余量。 */
        const val SPACE_MARGIN = 50L * 1024 * 1024
        private const val STAGING = ".staging"
        private const val ASIDE = ".previous"

        /** 进程级:App 启动的清理和下载各自建的实例也互斥。 */
        private val lock = Any()

        /** 正在用的 staging 目录(规范路径),[stagingDir] 登记,[commit]/[abandon] 撤销。只在 [lock] 里读写。 */
        private val active = HashSet<String>()
    }
}
