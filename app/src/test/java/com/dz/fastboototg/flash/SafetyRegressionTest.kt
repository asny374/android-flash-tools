package com.dz.fastboototg.flash
import com.dz.fastboototg.core.io.ByteArraySource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class SafetyRegressionTest {
    private class Sink(val limit: Long): FlashSink {
        var writes = 0; var image = byteArrayOf()
        override fun maxDownloadSize() = limit
        override fun download(data: ByteArray, offset: Int, length: Int) { image = data.copyOfRange(offset, offset + length) }
        override fun flash(partition: String) { writes++ }
    }
    private fun partition(type: Int, length: Long) = OtaPayload.OtaPartition("boot", 4, listOf(OtaPayload.OtaOp(type, 0, length, listOf(OtaPayload.OtaExtent(0, 1)))))
    @Test fun bzip2FixtureDecodesBeforeSingleFlash() = runBlocking {
        val compressed = byteArrayOf(66,90,104,57,49,65,89,38,83,89,61,76,51,75,0,0,0,1,0,60,0,32,0,33,-102,104,51,77,19,60,93,-55,20,-31,66,64,-11,48,-51,44)
        val sink = Sink(4)
        OtaFlasher(sink).flashPartition(partition(1, compressed.size.toLong()), OtaPayload.OtaManifest(4, emptyList(), 0), ByteArraySource(compressed), {_,_->}, {})
        assertEquals(1, sink.writes); assertArrayEquals("abcd".toByteArray(), sink.image)
    }
    @Test fun oversizedPartitionDoesNotWrite() = runBlocking {
        val sink = Sink(3)
        try { OtaFlasher(sink).flashPartition(partition(0, 4), OtaPayload.OtaManifest(4, emptyList(), 0), ByteArraySource(ByteArray(4)), {_,_->}, {}); fail() }
        catch (_: com.dz.fastboototg.core.AppException) { }
        assertEquals(0, sink.writes); assertEquals(0, sink.image.size)
    }
    @Test fun corruptCompressedDataDoesNotWrite() = runBlocking {
        val sink = Sink(4)
        try { OtaFlasher(sink).flashPartition(partition(1, 4), OtaPayload.OtaManifest(4, emptyList(), 0), ByteArraySource(ByteArray(4)), {_,_->}, {}); fail() }
        catch (_: java.io.IOException) { }
        assertEquals(0, sink.writes)
    }
}
