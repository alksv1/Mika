package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.shell.IMikaShell

/**
 * 真机测试:内置助手经无线调试起来、交接 Binder、在自己的虚拟屏上注入点击,解绑后助手留着、再绑定直接可用。
 * 前提:安卓 12+,无线调试开着。配对二选一:
 * - 带 `-e pairingCode <6 位码>`(可加 `-e pairingPort <端口>`),此时系统的「使用配对码配对」窗口要开着;
 * - 已经用 Mika 的钥匙配对过:带 `-e assumePaired true`。
 */
@RunWith(AndroidJUnit4::class)
class BuiltinHelperDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val phone = PhoneServices.of(context)
    private val engine = phone.shellManager.engine

    private fun helperUp(): IMikaShell = runBlocking {
        when {
            args.getString("pairingCode") != null && engine.status.value != BuiltinShellStatus.Running ->
                engine.startPairing(args.getString("pairingCode")!!, args.getString("pairingPort")?.toIntOrNull())
            else -> {
                if (args.getString("assumePaired") == "true") {
                    context.getSharedPreferences("builtin_shell", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
                }
                engine.retry()
                assumeTrue("not paired: pass -e pairingCode <code> or -e assumePaired true", engine.status.value != BuiltinShellStatus.NotPaired)
            }
        }
        assertEquals(BuiltinShellStatus.Running, engine.status.value)
        withTimeout(5_000) { phone.hub.shell.filterNotNull().first() }
    }

    private fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    @Test
    fun fileBridgeReadsSharedStorageAndRejectsInvalidTargets() {
        val shell = helperUp()
        val dir = "/sdcard/Download/mika-file-bridge-${android.os.Process.myPid()}"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun command(cmd: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }
        }
        try {
            command("mkdir -p $dir/subdir; printf bridge > $dir/sample.txt")
            val listing = shell.listFiles(dir, null, 128)
            assertEquals("ok", listing.getString("status"))
            val names = listing.getStringArrayList("names")!!
            val types = listing.getIntArray("types")!!
            assertEquals(1, types[names.indexOf("sample.txt")])
            assertEquals(2, types[names.indexOf("subdir")])
            val opened = shell.openFile("$dir/sample.txt")
            assertEquals("ok", opened.getString("status"))
            val fd = opened.getParcelable<android.os.ParcelFileDescriptor>("fd")!!
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                assertEquals("bridge", it.readBytes().toString(Charsets.UTF_8))
            }
            val workspaceId = "device-file-test-${android.os.Process.myPid()}"
            val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(context)
            val sandbox = com.wangzan101.mika.cap.sandbox.AndroidSandbox(context, com.wangzan101.mika.cap.sandbox.SandboxState.get(context))
            try {
                val deviceFiles = com.wangzan101.mika.cap.sandbox.AndroidDeviceFiles(phone.hub, dirs, sandbox)
                val copied = runBlocking { deviceFiles.importFile("$dir/sample.txt", workspaceId) }
                assertEquals("/workspace/inbox/sample.txt", copied.path)
                assertEquals(6L, copied.size)
                dirs.gate.openRead(dirs.workspace(workspaceId), "inbox/sample.txt").use {
                    assertEquals("bridge", it.input().readBytes().toString(Charsets.UTF_8))
                }
            } finally {
                runBlocking { dirs.deleteWorkspace(workspaceId) }
            }
            assertEquals("not_file", shell.openFile(dir).getString("status"))
            assertEquals("denied", shell.openFile("/dev/null").getString("status"))
            assertEquals("denied", shell.openFile("/data/data/file").getString("status"))
            assertEquals("not_found", shell.openFile("$dir/missing").getString("status"))
            assertEquals("not_directory", shell.listFiles("$dir/sample.txt", null, 128).getString("status"))
            assertEquals("invalid", shell.listFiles("relative", null, 128).getString("status"))
            val first = shell.listFiles(dir, null, 1)
            assertEquals(listOf("sample.txt"), first.getStringArrayList("names"))
            val second = shell.listFiles(dir, first.getString("next"), 1)
            assertEquals(listOf("subdir"), second.getStringArrayList("names"))
            assertNull(second.getString("next"))
        } finally {
            command("rm -rf $dir")
        }
    }

    @Test
    fun helperRunsAsShellDrivesItsDisplayAndSurvivesUnbind() {
        assumeTrue(Build.VERSION.SDK_INT >= BuiltinShellManager.MIN_SDK)
        val shell = helperUp()
        val ids = shell.identity()
        assertEquals("helper uid", 2000, ids[0])
        assertTrue("helper pid", ids[1] > 0 && ids[1] != android.os.Process.myPid())

        val conn = BuiltinShellConnection(phone.hub) { engine.need() }
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { conn.bind() }
        val bound = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
        assertSame(shell, bound)

        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val dm = context.getSystemService(DisplayManager::class.java)
        try {
            val displayId = bound.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay -> $displayId", displayId > 0)
            assertNotNull("display $displayId not visible", dm.getDisplay(displayId))
            assertTrue("injectTap", bound.injectTap(displayId, 100, 100))

            // 解绑:屏被撤掉,和服务结束一样;助手进程和它的 Binder 还在
            main.runOnMainSync { conn.unbind() }
            assertNull(conn.shell.value)
            assertTrue("display not released on unbind", poll(5_000) { dm.getDisplay(displayId) == null })
            assertTrue("helper died on unbind", shell.asBinder().pingBinder())
            assertSame(shell, phone.hub.shell.value)

            // 再绑定:直接拿到同一个助手,还能接着用
            main.runOnMainSync { conn.bind() }
            val again = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
            assertSame(shell, again)
            val second = again.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay after rebind -> $second", second > 0)
            assertTrue(again.injectTap(second, 50, 50))
        } finally {
            main.runOnMainSync { conn.unbind() }
            reader.close()
        }
    }
}
