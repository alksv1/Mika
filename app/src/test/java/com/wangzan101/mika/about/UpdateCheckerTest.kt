package com.wangzan101.mika.about

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var github: MockResponse = MockResponse().setResponseCode(500)
    private val hits = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var clock = 1_000_000_000L

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.path.orEmpty()
                return github
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun checker(current: String = "0.1.0", mirrors: MirrorSettings = MirrorSettings.OFF) = UpdateChecker(
        tmp.root.resolve("update.json"), current,
        releaseApi = server.url("/api").toString(),
        releasesUrl = server.url("/releases").toString(),
        mirrorSettings = { mirrors },
        now = { clock },
    )

    private fun release(tag: String) =
        MockResponse().setBody("""{"tag_name":"$tag","name":"Mika $tag","body":"- 新东西","html_url":"https://github.com/wangzan101/Mika/releases/tag/$tag","assets":[]}""")

    @Test
    fun newerReleaseFromGitHubShowsUpdate() = runTest {
        github = release("v0.2.0")
        val c = checker()
        c.check(manual = true)
        val s = c.state.value
        assertEquals(CheckStatus.Done, s.status)
        assertEquals("0.2.0", s.update?.version)
        assertTrue(s.hasUpdate)
        assertEquals(1, s.manualRounds)
        assertEquals(listOf("/api"), hits)
        val req = server.takeRequest()
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("Mika/"))
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        github = release("v0.1.0")
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertNull(c.state.value.update)
        assertEquals("0.1.0", c.state.value.latest?.version)
    }

    /** GitHub 限流(403)就是这次没查到:只问 GitHub 一次,不去别处找。 */
    @Test
    fun gitHubRateLimitIsAFailure() = runTest {
        github = MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}""")
        val c = checker()
        c.check(manual = true)
        assertEquals(listOf("/api"), hits)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertNull(c.state.value.update)
    }

    /** 开了镜像后,镜像和官方并行问;官方失败、镜像成功时照样拿到新版本。 */
    @Test
    fun enabledMirrorIsQueriedInParallelAndCanWin() = runTest {
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.path.orEmpty()
                return if (request.path.orEmpty().contains("/mirror/")) release("v0.2.0")
                else MockResponse().setResponseCode(500)
            }
        }
        val c = checker(mirrors = MirrorSettings(enabled = true, mirrors = listOf(mirror)))
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertEquals("0.2.0", c.state.value.update?.version)
        assertTrue(hits.any { it.contains("/mirror/") })
    }

    @Test
    fun slowerGitHubReleaseBeatsOlderMirror() = runTest {
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("/mirror/")) release("v0.2.0")
                else release("v0.3.0").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val c = checker(mirrors = MirrorSettings(enabled = true, mirrors = listOf(mirror)))
        c.check(manual = true)
        assertEquals("0.3.0", c.state.value.update?.version)
    }

    @Test
    fun fastResultIsShownAndPersistedBeforeSlowerNewerResult() = runTest {
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        val releaseSlow = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("/mirror/")) release("v0.2.0")
                else {
                    check(releaseSlow.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    release("v0.3.0")
                }
        }
        val c = checker(mirrors = MirrorSettings(enabled = true, mirrors = listOf(mirror)))
        val checking = async(Dispatchers.Default) { c.check(manual = true) }
        try {
            withContext(Dispatchers.Default) { withTimeout(3_000) { while (c.state.value.manualRounds == 0) delay(10) } }
            assertEquals(CheckStatus.Done, c.state.value.status)
            assertEquals("0.2.0", c.state.value.update?.version)
            // 等待首次写盘完成，再用另一个实例验证检查仍在进行时可恢复。
            withContext(Dispatchers.Default) {
                withTimeout(3_000) { while (!tmp.root.resolve("update.json").exists()) delay(10) }
            }
            val reopened = checker()
            reopened.load()
            assertEquals("0.2.0", reopened.state.value.update?.version)
        } finally {
            releaseSlow.countDown()
        }
        checking.await()
        assertEquals("0.3.0", c.state.value.update?.version)
        assertEquals(1, c.state.value.manualRounds)
        val reopened = checker()
        reopened.load()
        assertEquals("0.3.0", reopened.state.value.update?.version)
    }

    @Test
    fun slowerOlderSourceCannotReplaceFastNewerRelease() = runTest {
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("/mirror/"))
                    release("v0.2.0").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                else release("v0.3.0")
        }
        val c = checker(mirrors = MirrorSettings(enabled = true, mirrors = listOf(mirror)))
        c.check(manual = true)
        assertEquals("0.3.0", c.state.value.latest?.version)
        assertEquals(1, c.state.value.manualRounds)
        val reopened = checker()
        reopened.load()
        assertEquals("0.3.0", reopened.state.value.latest?.version)
    }

    @Test
    fun upToDateFastResultWaitsForOtherSources() = runTest {
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        val slowStarted = java.util.concurrent.CountDownLatch(1)
        val releaseSlow = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("/mirror/")) release("v0.1.0")
                else {
                    slowStarted.countDown()
                    check(releaseSlow.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    release("v0.2.0")
                }
        }
        val c = checker(mirrors = MirrorSettings(enabled = true, mirrors = listOf(mirror)))
        val checking = async(Dispatchers.Default) { c.check(manual = true) }
        try {
            withContext(Dispatchers.Default) {
                check(slowStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
                withTimeout(3_000) { while (c.state.value.latest == null) delay(10) }
            }
            assertEquals(CheckStatus.Checking, c.state.value.status)
            assertEquals(0, c.state.value.manualRounds)
        } finally {
            releaseSlow.countDown()
        }
        checking.await()
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(1, c.state.value.manualRounds)
    }

    @Test
    fun sameVersionPreservesNotesAndOnlyReusesIntegrityForSameApk() = runTest {
        fun metadata(url: String, notes: String, integrity: String) = MockResponse().setBody(
            """{"tag_name":"v0.2.0","body":"$notes","assets":[{"name":"Mika.apk","browser_download_url":"$url"$integrity}]}"""
        )
        val c = checker()
        github = metadata("https://example.com/a.apk", "release notes", ",\"size\":123,\"digest\":\"sha256:abc\"")
        c.check(manual = true)
        github = metadata("https://example.com/a.apk", "", "")
        c.check(manual = true)
        assertEquals("release notes", c.state.value.latest?.notes)
        assertEquals(123L, c.state.value.latest?.apkSize)
        assertEquals("sha256:abc", c.state.value.latest?.apkDigest)
        github = metadata("https://example.com/b.apk", "", "")
        c.check(manual = true)
        assertEquals("release notes", c.state.value.latest?.notes)
        assertEquals("https://example.com/b.apk", c.state.value.latest?.apkUrl)
        assertNull(c.state.value.latest?.apkSize)
        assertNull(c.state.value.latest?.apkDigest)
        val reopened = checker()
        reopened.load()
        assertEquals(c.state.value.latest, reopened.state.value.latest)
    }

    @Test
    fun olderSuccessfulResultDoesNotEraseKnownUpdate() = runTest {
        val c = checker()
        github = release("v0.3.0")
        c.check(manual = true)
        github = release("v0.1.0")
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertEquals(2, c.state.value.manualRounds)
        assertEquals("0.3.0", c.state.value.latest?.version)
        assertEquals("0.3.0", c.state.value.update?.version)
        val reopened = checker()
        reopened.load()
        assertEquals("0.3.0", reopened.state.value.update?.version)
    }

    @Test
    fun sameVersionPrefersGitHubMetadata() = runTest {
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("/mirror/"))
                    MockResponse().setBody("""{"tag_name":"v0.2.0","name":"Mirror","assets":[]}""")
                else release("v0.2.0").setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val c = checker(mirrors = MirrorSettings(enabled = true, mirrors = listOf(mirror)))
        c.check(manual = true)
        assertEquals("Mika v0.2.0", c.state.value.latest?.name)
    }

    /** 关掉镜像时一个镜像请求都不发。 */
    @Test
    fun disabledMirrorsAreNotContacted() = runTest {
        github = release("v0.2.0")
        val mirror = UpdateMirror("m", "Mirror", server.url("/mirror/").toString())
        val c = checker(mirrors = MirrorSettings(enabled = false, mirrors = listOf(mirror)))
        c.check(manual = true)
        assertEquals(listOf("/api"), hits)
    }

    @Test
    fun manualFailureIsReported() = runTest {
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertEquals(1, c.state.value.manualRounds)
        assertNull(c.state.value.update)
    }

    /** 自动检查失败不改界面上的状态;每次打开都查,只挡住两分钟内连着来的。 */
    @Test
    fun autoCheckIsSilentAndRunsOnEveryOpenButNotInBursts() = runTest {
        val c = checker()
        assertTrue(c.autoCheck())
        assertEquals(CheckStatus.Idle, c.state.value.status)
        assertEquals(0, c.state.value.manualRounds)
        val before = hits.size
        clock += UpdateSchedule.MIN_GAP_MS - 1
        assertFalse(c.autoCheck())
        assertEquals(before, hits.size)
        clock += 1
        github = release("v0.2.0")
        assertTrue(c.autoCheck())
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(CheckStatus.Idle, c.state.value.status)
    }

    /** 查到的版本存盘:重开 App 不联网也有红点;装上新版后红点消失;上次自动检查的时间也记着。 */
    @Test
    fun foundUpdateSurvivesRestart() = runTest {
        github = release("v0.2.0")
        checker().autoCheck()
        github = MockResponse().setResponseCode(500)

        val again = checker()
        again.load()
        assertEquals("0.2.0", again.state.value.update?.version)
        assertFalse("just checked", again.autoCheck())

        val upgraded = checker(current = "0.2.0")
        upgraded.load()
        assertNull(upgraded.state.value.update)
        assertNotNull(upgraded.state.value.latest)
    }

    /** 同时进来的两次自动检查(查还没完)只真的查一次;隔十分钟再打开照样查。 */
    @Test
    fun concurrentAutoChecksHitGitHubOnce() = runTest {
        github = release("v0.2.0")
        val c = checker()
        val ran = listOf(async { c.autoCheck() }, async { c.autoCheck() }).awaitAll()
        assertEquals(1, ran.count { it })
        assertEquals(1, hits.size)
        clock += 10 * 60 * 1000L
        assertTrue(c.autoCheck())
        assertEquals(2, hits.size)
    }

    @Test
    fun scheduleRules() {
        assertTrue(UpdateSchedule.due(null, 5))
        assertFalse(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS - 1))
        assertTrue(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS))
        assertTrue("clock moved back", UpdateSchedule.due(10_000, 5_000))
    }
}
