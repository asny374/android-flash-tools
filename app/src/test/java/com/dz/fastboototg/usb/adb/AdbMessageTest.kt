package com.dz.fastboototg.usb.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ADB 24 字节帧的编码与解析测试 */
class AdbMessageTest {

    @Test
    fun `序列化与解析往返`() {
        val payload = "shell:ls".toByteArray(Charsets.US_ASCII)
        val msg = AdbMessage.encode(AdbCommand.OPEN, 1, 0, payload)

        val bytes = AdbMessage.serialize(msg)
        assertEquals(AdbMessage.HEADER_SIZE + payload.size, bytes.size)

        val (parsed, consumed) = AdbMessage.parse(bytes, bytes.size)!!
        assertEquals(AdbCommand.OPEN, parsed.command)
        assertEquals(1, parsed.arg0)
        assertEquals(0, parsed.arg1)
        assertArrayEquals(payload, parsed.data)
        assertEquals(bytes.size, consumed)
    }

    @Test
    fun `magic 等于命令取反`() {
        val bytes = AdbMessage.serialize(AdbMessage.encode(AdbCommand.CNXN, 1, 4096))
        val command = bytes.copyOfRange(0, 4)
        val magic = readIntLe(bytes, 20)
        val expected = readIntLe(command, 0).inv()
        assertEquals(expected, magic)
    }

    @Test
    fun `校验和错误抛异常`() {
        val payload = "hello".toByteArray()
        val bytes = AdbMessage.serialize(AdbMessage.encode(AdbCommand.WRTE, 1, 2, payload))
        // 破坏载荷一个字节
        bytes[AdbMessage.HEADER_SIZE] = (bytes[AdbMessage.HEADER_SIZE].toInt() xor 0x41).toByte()
        try {
            AdbMessage.parse(bytes, bytes.size)
            fail("应当抛出校验和异常")
        } catch (t: AdbException) {
            assertTrue(t.message!!.contains("校验和"))
        }
    }

    @Test
    fun `magic 校验失败抛异常`() {
        val bytes = AdbMessage.serialize(AdbMessage.encode(AdbCommand.OKAY, 1, 2))
        // 破坏 magic 字段
        bytes[20] = (bytes[20].toInt() xor 0x55).toByte()
        try {
            AdbMessage.parse(bytes, bytes.size)
            fail("应当抛出 magic 异常")
        } catch (t: AdbException) {
            assertTrue(t.message!!.contains("magic"))
        }
    }

    @Test
    fun `头不完整返回 null`() {
        val bytes = AdbMessage.serialize(AdbMessage.encode(AdbCommand.OKAY, 1, 2))
        assertNull(AdbMessage.parse(bytes, 10))
    }

    @Test
    fun `载荷跨消息缓冲不完整返回 null`() {
        val bytes = AdbMessage.serialize(
            AdbMessage.encode(AdbCommand.WRTE, 1, 2, "abcdef".toByteArray())
        )
        // 只有头 + 部分载荷
        assertNull(AdbMessage.parse(bytes, AdbMessage.HEADER_SIZE + 3))
    }

    @Test
    fun `载荷超硬上限抛异常`() {
        val bytes = ByteArray(AdbMessage.HEADER_SIZE)
        writeIntLe(bytes, 0, ascii("WRTE"))
        writeIntLe(bytes, 12, Int.MAX_VALUE)
        writeIntLe(bytes, 20, ascii("WRTE").inv())
        try {
            AdbMessage.parse(bytes, AdbMessage.HEADER_SIZE)
            fail("应当抛出载荷异常")
        } catch (t: AdbException) {
            assertTrue(t.message!!.contains("载荷"))
        }
    }

    @Test
    fun `ADB 校验和是无符号字节之和`() {
        // AOSP ADB v1: sum of unsigned bytes, not zlib CRC32.
        val data = "123456789".toByteArray()
        assertEquals(477, AdbMessage.checksum(data))
        assertEquals(383, AdbMessage.checksum(byteArrayOf(0xff.toByte(), 0x80.toByte())))
    }

    @Test
    fun `known AOSP OPEN wire frame uses length at offset 12 and byte sum at 16`() {
        val payload = "shell:id\u0000".toByteArray(Charsets.UTF_8)
        val bytes = AdbMessage.serialize(AdbMessage.encode(AdbCommand.OPEN, 1, 0, payload))
        assertEquals(9, readIntLe(bytes, 12))
        assertEquals(799, readIntLe(bytes, 16))
    }

    @Test
    fun `modern negotiated packets can omit checksum`() {
        val bytes = AdbMessage.serialize(AdbMessage.encode(AdbCommand.WRTE, 1, 2, byteArrayOf(42)))
        writeIntLe(bytes, 16, 0)
        assertArrayEquals(byteArrayOf(42), AdbMessage.parse(bytes, bytes.size, false)!!.first.data)
    }

    private fun ascii(s: String): Int =
        (s[0].toInt() and 0xFF) or ((s[1].toInt() and 0xFF) shl 8) or
            ((s[2].toInt() and 0xFF) shl 16) or ((s[3].toInt() and 0xFF) shl 24)

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
