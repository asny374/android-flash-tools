package com.dz.fastboototg.usb.fastboot

import com.dz.fastboototg.usb.BulkChannel
import com.dz.fastboototg.usb.FastbootResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fastboot 帧解析的单元测试。
 *
 * 帧重组是整个应用最容易出错的部分（TAGGED 与 LENGTH_PREFIXED 两种流），
 * 依赖接口 BulkChannel 后可以在 JVM 上直接喂字节流验证。
 */
class FastbootProtocolTest {

    /** 按脚本顺序吐数据的假通道 */
    private class FakeChannel(private val script: List<ByteArray>) : BulkChannel {
        private var index = 0
        override val name: String get() = "Fake"
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int = length
        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            if (index >= script.size) return -1
            val data = script[index++]
            val n = minOf(data.size, length)
            System.arraycopy(data, 0, buffer, offset, n)
            return n
        }
        override fun clearHalt(): Boolean = true
        override fun close() {}
    }

    private fun protocol(vararg chunks: ByteArray) = FastbootProtocol(FakeChannel(chunks.toList()))

    @Test
    fun `TAGGED 帧 - OKAY 带载荷`() {
        val p = protocol("OKAYdone".toByteArray())
        val r = p.readFrame(1000)
        assertTrue(r is FastbootResponse.Okay)
        assertEquals("done", (r as FastbootResponse.Okay).payload)
    }

    @Test
    fun `TAGGED 帧 - FAIL 原因`() {
        val p = protocol("FAILnot unlocked".toByteArray())
        val r = p.readFrame(1000)
        assertTrue(r is FastbootResponse.Fail)
        assertEquals("not unlocked", (r as FastbootResponse.Fail).reason)
    }

    @Test
    fun `INFO and TEXT are consumed before final response`() {
        val p = protocol("INFOerasing".toByteArray(), "TEXTwriting".toByteArray(), "OKAY".toByteArray(), "OKAYnext".toByteArray())
        assertTrue(p.execute("flash:boot") is FastbootResponse.Okay)
        assertEquals("next", (p.execute("getvar:product") as FastbootResponse.Okay).payload)
    }

    @Test
    fun `payload containing tags and hexadecimal is preserved`() {
        assertEquals("0002hi", (protocol("OKAY0002hi".toByteArray()).readFrame(1000) as FastbootResponse.Okay).payload)
        assertEquals("FAIL INFO DATA", (protocol("OKAYFAIL INFO DATA".toByteArray()).readFrame(1000) as FastbootResponse.Okay).payload)
    }

    @Test
    fun `device failure remains failure after INFO`() {
        assertEquals("locked", (protocol("INFOwait".toByteArray(), "FAILlocked".toByteArray()).execute("erase:boot") as FastbootResponse.Fail).reason)
    }

    @Test
    fun `超时 - 无数据时返回 Timeout`() {
        val p = protocol()
        val r = p.readFrame(50)
        assertTrue(r is FastbootResponse.Timeout)
    }

    @Test
    fun `parsePair - 标准与兜底分隔`() {
        val p = protocol()
        val method = FastbootProtocol::class.java.getDeclaredMethod(
            "parsePair", String::class.java, MutableMap::class.java
        )
        method.isAccessible = true
        val out = linkedMapOf<String, String>()
        method.invoke(p, "unlocked: yes", out)
        method.invoke(p, "kernel:lk", out)
        assertEquals("yes", out["unlocked"])
        assertEquals("lk", out["kernel"])
    }
}
