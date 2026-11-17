package com.dz.fastboototg.flash
import com.dz.fastboototg.core.io.ByteArraySource
import com.dz.fastboototg.usb.*
import com.dz.fastboototg.usb.fastboot.FastbootProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class FlashGuardTest {
    @Test fun oversizedRawIsRejectedBeforeDownload() = runBlocking {
        val commands = mutableListOf<String>()
        val channel = object: BulkChannel {
            override val name = "fake"
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int { commands.add(String(bytes, offset, length)); return length }
            override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
                val response = (if (commands.last() == "getvar:max-download-size") "OKAY00000004" else "OKAY00000010").toByteArray()
                response.copyInto(buffer, offset); return response.size
            }
            override fun clearHalt() = true
            override fun close() {}
        }
        try { FlashOrchestrator(FastbootProtocol(channel), {_,_->}, {}).flash("boot", ByteArraySource(ByteArray(8))); fail() }
        catch (_: FastbootException) { }
        assertTrue(commands.all { it.startsWith("getvar:") })
    }
}
