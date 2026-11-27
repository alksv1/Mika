package com.wangzan101.mika.voice.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

class VoicePackStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root by lazy { File(tmp.root, "voice-models") }
    private val store by lazy { VoicePackStore(root, freeSpace = { 1234L }) }

    private fun entry(id: String = "a", version: Int = 2) = VoicePackEntry(
        id = id, version = version, backend = "sherpa-onnx", family = "sense_voice", displayName = id,
        languages = listOf("en"), recommendedFor = listOf("en"),
        files = listOf(
            VoicePackFile("model.onnx", "model", 10, "0".repeat(64)),
            VoicePackFile("tokens.txt", "tokens", 4, "1".repeat(64)),
            VoicePackFile("LICENSE", "license", 3, "2".repeat(64)),
        ),
        license = VoicePackLicense("MIT", "LICENSE", "x"),
        upstream = "https://up.example.com/$id/",
    )

    private fun catalog(vararg entries: VoicePackEntry) = VoicePackCatalog("https://example.com/r/", entries.toList())

    /** 按清单大小把 [e] 的文件写进 [dir];[skip] 里的不写,[short] 里的少写一个字节。 */
    private fun fill(dir: File, e: VoicePackEntry, skip: Set<String> = emptySet(), short: Set<String> = emptySet()): File {
        dir.mkdirs()
        e.files.filter { it.name !in skip }.forEach { f ->
            File(dir, f.name).writeBytes(ByteArray((f.size - if (f.name in short) 1 else 0).toInt()))
        }
        return dir
    }

    private fun versionDir(e: VoicePackEntry, version: Int = e.version) = File(root, "${e.id}/$version")

    @Test
    fun installedRequiresAllFilesWithManifestSizes() {
        val e = entry()
        assertNull(store.installed(e))
        assertEquals(VoicePackStatus.NotInstalled, store.status(e))

        fill(versionDir(e), e, skip = setOf("LICENSE"))
        assertNull("missing license file", store.installed(e))

        fill(versionDir(e), e)
        val pack = store.installed(e)
        assertNotNull(pack)
        assertEquals(versionDir(e), pack!!.dir)
        assertEquals(File(versionDir(e), "model.onnx"), pack.file("model"))
        assertEquals(VoicePackStatus.Installed, store.status(e))
        assertEquals(versionDir(e), store.installedAny(e)!!.dir)
    }

    @Test
    fun sizeMismatchIsCorrupt() {
        val e = entry()
        fill(versionDir(e), e, short = setOf("model.onnx"))
        assertNull(store.installed(e))
        assertEquals(VoicePackStatus.Corrupt, store.status(e))

        File(versionDir(e), "model.onnx").delete()
        assertEquals(VoicePackStatus.Corrupt, store.status(e))
    }

    @Test
    fun commitIsAtomicAndRemovesOldVersion() {
        val old = entry(version = 1)
        val e = entry(version = 2)
        fill(versionDir(old), old)
        val staging = store.stagingDir(e)
        assertEquals(File(root, ".staging/a-2"), staging)
        assertTrue(staging.isDirectory)
        assertEquals(0, staging.listFiles()!!.size)

        // 文件不全的 staging 不能生效,旧版本原样留着
        fill(staging, e, skip = setOf("tokens.txt"))
        try {
            store.commit(e, staging)
            fail("incomplete staging must not commit")
        } catch (_: IOException) {}
        assertFalse(versionDir(e).exists())
        assertTrue(File(versionDir(old), "model.onnx").isFile)

        fill(staging, e)
        val pack = store.commit(e, staging)
        assertEquals(versionDir(e), pack.dir)
        assertFalse(staging.exists())
        assertFalse(versionDir(old).exists())
        assertEquals(VoicePackStatus.Installed, store.status(e))

        // 重新下载同一版本:新 staging 盖掉已有的那份
        val again = store.stagingDir(e)
        fill(again, e)
        File(again, "tokens.txt").writeText("new!")
        store.commit(e, again)
        assertEquals("new!", File(versionDir(e), "tokens.txt").readText())
        assertEquals(listOf("2"), File(root, "a").list()!!.toList())
    }

    @Test
    fun stagingDirStartsEmpty() {
        val e = entry()
        File(store.stagingDir(e), "leftover.part").writeText("x")
        assertEquals(0, store.stagingDir(e).listFiles()!!.size)
    }

    @Test
    fun olderVersionReportsUpdateAvailableAndStillLoads() {
        val old = entry(version = 1)
        val e = entry(version = 2)
        fill(versionDir(old), old)
        assertNull(store.installed(e))
        assertEquals(VoicePackStatus.UpdateAvailable, store.status(e))
        val pack = store.installedAny(e)
        assertNotNull(pack)
        assertEquals(versionDir(old), pack!!.dir)
    }

    @Test
    fun cleanupRemovesStagingAndStaleVersions() {
        val e = entry("a", version = 3)
        val b = entry("b", version = 2)
        fill(versionDir(e, 1), e)
        fill(versionDir(e, 2), e)
        fill(versionDir(b, 1), b)   // b 只有旧版本:照常留着用
        fill(File(root, "gone/1"), entry("gone"))
        fill(File(root, ".staging/a-3"), e)   // 上个进程没下完的残留(没登记为正在用)
        File(root, "a/junk").mkdirs()

        store.cleanup(catalog(e, b))

        assertFalse(File(root, ".staging").exists())
        assertFalse(File(root, "gone").exists())
        assertEquals(listOf("2"), File(root, "a").list()!!.toList())
        assertEquals(listOf("1"), File(root, "b").list()!!.toList())
        assertEquals(VoicePackStatus.UpdateAvailable, store.status(e))
    }

    @Test
    fun cleanupKeepsCurrentOverOlderAndOnNothingInstalled() {
        val e = entry(version = 2)
        fill(versionDir(e, 1), e)
        fill(versionDir(e), e)
        store.cleanup(catalog(e))
        assertEquals(listOf("2"), File(root, "a").list()!!.toList())

        VoicePackStore(File(tmp.root, "never-created")).cleanup(catalog(e))
        assertFalse(File(tmp.root, "never-created").exists())
    }

    @Test
    fun crashMidCommitOfSameVersionKeepsAUsablePack() {
        val e = entry()
        // commit 刚把装好的那份挪到一旁、新的还没改名进来时进程被杀
        fill(File(root, "a/2.previous"), e)
        fill(File(root, ".staging/a-2"), e)
        VoicePackStore(root).cleanup(catalog(e))
        assertEquals(VoicePackStatus.Installed, store.status(e))
        assertEquals(listOf("2"), File(root, "a").list()!!.toList())
        assertFalse(File(root, ".staging").exists())

        // 新的已经改名进来、旁边那份还没删时被杀:留新的
        fill(File(root, "a/2.previous"), e)
        File(root, "a/2/tokens.txt").writeText("new!")
        VoicePackStore(root).cleanup(catalog(e))
        assertEquals(listOf("2"), File(root, "a").list()!!.toList())
        assertEquals("new!", File(root, "a/2/tokens.txt").readText())
    }

    @Test
    fun asideCopyIsNotAnOlderVersion() {
        val e = entry()
        fill(File(root, "a/2.previous"), e)
        assertNull(store.installedAny(e))
        assertEquals(VoicePackStatus.NotInstalled, store.status(e))
    }

    @Test
    fun cleanupAndDeleteDoNotFollowSymlinks() {
        val e = entry()
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("keep") }
        root.mkdirs()
        // 根下一个清单里没有的「包」其实是指到外面的链接;包里一个版本目录也是
        Files.createSymbolicLink(File(root, "gone").toPath(), outside.toPath())
        File(root, "a").mkdirs()
        Files.createSymbolicLink(File(root, "a/1").toPath(), outside.toPath())
        fill(versionDir(e), e)
        Files.createSymbolicLink(File(versionDir(e), "extra").toPath(), outside.toPath())

        store.cleanup(catalog(e))
        assertTrue(precious.isFile)
        assertFalse(Files.exists(File(root, "gone").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(root, "a/1").toPath(), LinkOption.NOFOLLOW_LINKS))

        store.delete("a")
        assertTrue(precious.isFile)
        assertFalse(File(root, "a").exists())

        // 包目录本身是链接:删链接,不删外面的东西
        Files.createSymbolicLink(File(root, "a").toPath(), outside.toPath())
        store.delete("a")
        assertTrue(precious.isFile)
        assertFalse(Files.exists(File(root, "a").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun cleanupLeavesActiveStagingAlone() {
        val e = entry()
        val other = entry("b")
        val staging = store.stagingDir(e)
        fill(staging, e, skip = setOf("LICENSE"))
        // 另一个实例(App 启动时那个)清理时,正在下的 staging 不动
        VoicePackStore(root).cleanup(catalog(e, other))
        assertTrue(File(staging, "model.onnx").isFile)

        store.abandon(e)
        assertFalse(staging.exists())
        fill(store.stagingDir(other), other)
        store.commit(other, File(root, ".staging/b-2"))
        File(root, ".staging/stray").mkdirs()
        VoicePackStore(root).cleanup(catalog(e, other))
        assertFalse(File(root, ".staging").exists())
        assertEquals(VoicePackStatus.Installed, store.status(other))
    }

    @Test
    fun deleteRemovesOnlyThatPack() {
        val a = entry("a")
        val b = entry("b")
        fill(versionDir(a), a)
        fill(versionDir(b), b)
        store.delete("a")
        assertFalse(File(root, "a").exists())
        assertEquals(VoicePackStatus.NotInstalled, store.status(a))
        assertEquals(VoicePackStatus.Installed, store.status(b))
    }

    @Test
    fun unsafeIdsAreRejected() {
        for (id in listOf("", ".", "..", ".staging", "a/b", "..\\x")) {
            try {
                store.delete(id)
                fail("id '$id' accepted")
            } catch (_: IllegalArgumentException) {}
        }
        assertTrue(tmp.root.exists())
    }

    @Test
    fun freeBytesComesFromTheFilesystem() {
        assertEquals(1234L, store.freeBytes())
        assertEquals(50L * 1024 * 1024, VoicePackStore.SPACE_MARGIN)
    }
}
