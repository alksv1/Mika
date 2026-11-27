package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavTest {
    private fun ascii(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)

    @Test
    fun `一秒 16k 单声道编码出 44 字节头加 32000 字节数据`() {
        val wav = Wav.encode(ShortArray(16000))
        assertEquals(44 + 32000, wav.size)
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", ascii(wav, 0))
        assertEquals(36 + 32000, buf.getInt(4))
        assertEquals("WAVE", ascii(wav, 8))
        assertEquals("fmt ", ascii(wav, 12))
        assertEquals(16, buf.getInt(16))
        assertEquals(1, buf.getShort(20).toInt())
        assertEquals(1, buf.getShort(22).toInt())
        assertEquals(16000, buf.getInt(24))
        assertEquals(32000, buf.getInt(28))
        assertEquals(2, buf.getShort(32).toInt())
        assertEquals(16, buf.getShort(34).toInt())
        assertEquals("data", ascii(wav, 36))
        assertEquals(32000, buf.getInt(40))
    }

    @Test
    fun `采样按小端写入`() {
        val wav = Wav.encode(shortArrayOf(0x0102, -2))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()), wav.copyOfRange(44, 48))
    }

    @Test
    fun `静音同长度且数据全零`() {
        val wav = Wav.silence(1000)
        assertEquals(44 + 32000, wav.size)
        assertTrue(wav.drop(44).all { it == 0.toByte() })
    }

    @Test
    fun decodeRoundTripsEncode() {
        val pcm = shortArrayOf(0, 1, -1, 0x0102, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(pcm, Wav.decode(Wav.encode(pcm)))
    }

    @Test
    fun `解码跳过 data 之前的其他块`() {
        val wav = Wav.encode(shortArrayOf(7, -7))
        val extra = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray(Charsets.US_ASCII)).putInt(4).put("INFO".toByteArray(Charsets.US_ASCII)).array()
        val withList = wav.copyOfRange(0, 36) + extra + wav.copyOfRange(36, wav.size)
        assertArrayEquals(shortArrayOf(7, -7), Wav.decode(withList))
    }

    @Test
    fun `data 块长度超出文件时读到文件末尾`() {
        for (len in intArrayOf(Int.MAX_VALUE, -1, 1_000)) {
            val wav = Wav.encode(shortArrayOf(1, 2, 3))
            ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, len)
            assertArrayEquals("len=$len", shortArrayOf(1, 2, 3), Wav.decode(wav))
        }
    }

    @Test
    fun `其他块长度很大时不越界`() {
        val wav = Wav.encode(shortArrayOf(5))
        val junk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("JUNK".toByteArray(Charsets.US_ASCII)).putInt(-8).array()
        val bad = wav.copyOfRange(0, 36) + junk + wav.copyOfRange(36, wav.size)
        Wav.decode(bad)    // 不抛异常就行:找不到 data 块时按 44 字节头之后处理
        assertArrayEquals(ShortArray(0), Wav.decode(ByteArray(10)))
    }
}
