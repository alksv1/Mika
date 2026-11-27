package com.wangzan101.mika.core.device.adb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LocalPortScanTest {
    @Test
    fun parsesTheSystemPortRangeOrFallsBack() {
        assertEquals(32768..60999, LocalPortScan.parseRange("32768\t60999\n"))
        assertEquals(40000..40000, LocalPortScan.parseRange(" 40000 40000 "))
        val fallback = LocalPortScan.FALLBACK_RANGE
        assertEquals(30000..65535, fallback)
        for (bad in listOf(null, "", "abc", "32768", "1 2 3", "60999 32768", "80 9000", "32768 70000")) {
            assertEquals(bad, fallback, LocalPortScan.parseRange(bad))
        }
    }

    /** 每次扫描、核对都让假时钟往前走:用完预算就停,不扫下一个地址、不核对剩下的端口。 */
    private class SlowScanner(val clock: LongArray, val sweepMs: Long, val probeMs: Long, val open: List<Int>) : LocalPortScanner {
        val swept = mutableListOf<String>()
        val probed = mutableListOf<Int>()
        val ranges = mutableListOf<IntRange>()
        var skip: Set<Int> = emptySet()
        override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> {
            if (swept.lastOrNull() != host) swept += host
            ranges += ports
            this.skip = skip
            clock[0] += minOf(sweepMs, budgetMs)
            return open.filter { it in ports && it !in skip }
        }
        override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean {
            probed += port
            clock[0] += probeMs
            return false
        }
    }

    @Test
    fun timeCapStopsProbingAndSkipsTheNextHost() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 6_000, probeMs = 1_500, open = listOf(40001, 40002, 40003, 40004, 40005))
        val scan = LocalPortScan(scanner, { listOf("127.0.0.1", "192.0.2.10") }, range = { 40000..40005 }, budgetMs = 10_000, now = { clock[0] })
        val r = scan.scan(emptySet())
        assertEquals(listOf("127.0.0.1"), scanner.swept)
        // 6 s 扫描 + 3 次 1.5 s 核对就到 10.5 s,剩下的不再核对
        assertEquals(listOf(40001, 40002, 40003), scanner.probed)
        assertEquals(ScanResult(emptyList(), open = 5, adb = 0), r)
        assertTrue(clock[0] <= 10_500)
    }

    @Test
    fun bothHostsScannedWithinBudgetWhenNothingAnswers() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 2_000, probeMs = 10, open = listOf(40001))
        val r = LocalPortScan(scanner, { listOf("127.0.0.1", "192.0.2.10", "127.0.0.1") }, range = { 40000..40005 }, now = { clock[0] }).scan(emptySet())
        assertEquals(listOf("127.0.0.1", "192.0.2.10"), scanner.swept)
        assertEquals(2, r.open)
        assertEquals(0, r.adb)
    }

    @Test
    fun usesTheGivenRangeAndSkipsTriedAndOwnPorts() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 1, probeMs = 1, open = listOf(40001, 40002, 40003))
        val scan = LocalPortScan(scanner, { listOf("127.0.0.1") }, range = { 32768..60999 }, ownPorts = { setOf(40002) }, now = { clock[0] })
        scan.scan(setOf(40001))
        // 分段扫,各段首尾相接、不重不漏,正好盖住给定的范围
        assertEquals((32768..60999).toList(), scanner.ranges.flatMap { it.toList() })
        assertTrue(scanner.ranges.all { it.count() <= LocalPortScan.PORT_BATCH_SIZE })
        assertEquals(setOf(40001, 40002), scanner.skip)
        assertEquals(listOf(40003), scanner.probed)
    }

    /** 扫得慢时一口气扫完整段会把预算花光:早就开着的 adbd 要在扫完它那一段后马上核对,不等后面的段。 */
    @Test
    fun anEarlyAdbdIsVerifiedBeforeTheWholeRangeUsesTheBudget() = runTest {
        var now = 0L
        val verified = mutableListOf<Int>()
        val scanner = object : LocalPortScanner {
            // 每扫一个端口花 1 ms,预算用完就停
            override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> {
                val scanned = minOf(ports.count().toLong(), budgetMs).toInt()
                now += scanned
                return listOf(44339).filter { it in ports.first until ports.first + scanned && it !in skip }
            }
            override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean {
                verified += port
                now += 50
                return port == 44339
            }
        }
        val r = LocalPortScan(scanner, { listOf("127.0.0.1") }, range = { 40000..60000 }, budgetMs = 10_000, now = { now })
            .scan(emptySet())
        assertEquals(listOf(44339), verified)
        assertEquals(listOf(AdbEndpoint("127.0.0.1", 44339)), r.endpoints)
        assertTrue(now <= 10_000)
    }

    /** 一段里核对出 adbd 就停:不扫后面的段,也不扫下一个地址。 */
    @Test
    fun scanningStopsAfterTheBatchThatFindsAdbd() = runTest {
        val swept = mutableListOf<IntRange>()
        val scanner = object : LocalPortScanner {
            override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> {
                swept += ports
                return listOf(40500, 41500, 50000).filter { it in ports }
            }
            override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int) = port != 50000
        }
        val r = LocalPortScan(scanner, { listOf("127.0.0.1", "192.0.2.10") }, range = { 40000..60000 }).scan(emptySet())
        assertEquals(listOf(40000..41023), swept)
        assertEquals(listOf(AdbEndpoint("127.0.0.1", 40500)), r.endpoints)
        assertEquals(ScanResult(listOf(AdbEndpoint("127.0.0.1", 40500)), open = 1, adb = 1), r)
    }

    /** 端口一直扫不完也有界:预算用完就停,不再开新的一段。 */
    @Test
    fun batchesStopWhenTheBudgetRunsOut() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 3_000, probeMs = 1, open = emptyList())
        val r = LocalPortScan(scanner, { listOf("127.0.0.1", "192.0.2.10") }, range = { 30000..65535 }, budgetMs = 10_000, now = { clock[0] })
            .scan(emptySet())
        assertEquals(4, scanner.ranges.size)
        assertEquals(listOf("127.0.0.1"), scanner.swept)
        assertEquals(ScanResult(emptyList(), 0, 0), r)
        assertEquals(10_000, clock[0])
    }

    @Test
    fun theLastPortOfTheRangeIsScannedWithoutOverflow() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 1, probeMs = 1, open = listOf(65535))
        LocalPortScan(scanner, { listOf("127.0.0.1") }, range = { 65000..65535 }, now = { clock[0] }).scan(emptySet())
        assertEquals(listOf(65000..65535), scanner.ranges)
        assertEquals(listOf(65535), scanner.probed)
    }

    @Test
    fun ownListeningPortsAreTracked() {
        OwnListeningPorts.add(45678)
        assertTrue(45678 in OwnListeningPorts.snapshot())
        OwnListeningPorts.remove(45678)
        assertFalse(45678 in OwnListeningPorts.snapshot())
    }

    /** 真 socket:在一小段端口里扫出假 adbd 和一个不说 adb 的服务,只有 adbd 回 STLS。 */
    @Test
    fun nioScannerFindsOpenPortsAndOnlyAdbdAnswersStls() = runBlocking {
        FakeAdbd(emptyList()).use { adbd ->
            ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { other ->
                val scanner = NioPortScanner()
                val lo = minOf(adbd.port, other.localPort)
                val hi = maxOf(adbd.port, other.localPort)
                // 范围太大时只扫两个端口附近
                val ports = if (hi - lo > 2_000) listOf(lo..lo, hi..hi) else listOf(lo..hi)
                val open = ports.flatMap { scanner.openPorts("127.0.0.1", it, emptySet(), 5_000) }
                assertTrue(open.toString(), adbd.port in open && other.localPort in open)
                assertTrue(scanner.speaksAdbTls("127.0.0.1", adbd.port, 1_000))
                assertFalse(scanner.speaksAdbTls("127.0.0.1", other.localPort, 300))
                assertTrue(scanner.openPorts("127.0.0.1", adbd.port..adbd.port, setOf(adbd.port), 1_000).isEmpty())
            }
        }
    }

    @Test
    fun nioScannerRejectsNonLocalHostsInsteadOfResolvingOrConnecting() = runBlocking {
        val scanner = NioPortScanner()
        for (host in listOf("127.evil.com", "127.0.0.1.example.com", "192.0.2.1")) {
            assertTrue(host, scanner.openPorts(host, 40000..40001, emptySet(), 50).isEmpty())
            assertFalse(host, scanner.speaksAdbTls(host, 40000, 50))
        }
    }
}
