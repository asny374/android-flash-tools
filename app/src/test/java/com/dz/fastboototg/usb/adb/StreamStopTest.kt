package com.dz.fastboototg.usb.adb
import com.dz.fastboototg.usb.BulkChannel
import org.junit.Test
import org.junit.Assert.*
class StreamStopTest {
    private class Channel(messages: List<AdbMessage>): BulkChannel {
        val data = messages.fold(byteArrayOf()) { acc, m -> acc + AdbMessage.serialize(m) }
        var pos = 0; var closed = false
        override val name = "fake"
        override fun write(bytes: ByteArray, offset: Int, length: Int) = length
        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            val n = minOf(length, data.size - pos)
            if (n == 0) return -1
            data.copyInto(buffer, offset, pos, pos+n); pos += n; return n
        }
        override fun clearHalt() = true
        override fun close() { closed = true }
    }
    @Test fun stoppingLogStreamPreservesConnectionForNextShell() {
        val channel = Channel(listOf(
            AdbMessage("OKAY", 7, 1, byteArrayOf()),
            AdbMessage("WRTE", 7, 1, "log".toByteArray()),
            AdbMessage("CLSE", 7, 1, byteArrayOf()),
            AdbMessage("OKAY", 8, 2, byteArrayOf()),
            AdbMessage("WRTE", 8, 2, "working".toByteArray()),
            AdbMessage("CLSE", 8, 2, byteArrayOf())
        ))
        val session = AdbSession(channel)
        session.streamShell("logcat", { true }) { session.requestStreamStop() }
        assertTrue(session.connected); assertFalse(channel.closed)
        assertEquals("working", session.shell("echo working"))
    }
}
