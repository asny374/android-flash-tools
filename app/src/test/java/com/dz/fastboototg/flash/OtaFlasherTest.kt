package com.dz.fastboototg.flash

import com.dz.fastboototg.core.io.RandomAccessSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OtaFlasher 流式聚合测试：假 FlashSink 验证切块 / download / flash 调用序列 */
class OtaFlasherTest {

    private class FakeSink(private val maxChunk: Int) : FlashSink {
        val assembled = mutableListOf<Byte>()
        val flashCalls = mutableListOf<String>()

        override fun maxDownloadSize(): Long = maxChunk.toLong()
        override fun download(data: ByteArray, offset: Int, length: Int) {
            for (i in 0 until length) assembled.add(data[offset + i])
        }
        override fun flash(partition: String) {
            flashCalls.add(partition)
        }
    }

    private class FakeSource(private val data: ByteArray) : RandomAccessSource {
        override val length: Long get() = data.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int) {
            System.arraycopy(data, position.toInt(), buffer, offset, count)
        }
        override fun close() {}
    }

    private fun op(type: Int, dataOffset: Long, dataLength: Long, start: Long, num: Long): ByteArray =
        protoVarintField(1, type.toLong()) + protoVarintField(3, dataOffset) +
            protoVarintField(4, dataLength) + protoLenField(6, extent(start, num))

    private fun extent(start: Long, num: Long): ByteArray =
        protoVarintField(1, start) + protoVarintField(2, num)

    private fun protoVarintField(field: Int, value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var header = (field shl 3)
        while (true) {
            if (header and 0x7F.inv() == 0) { out.write(header); break }
            out.write((header and 0x7F) or 0x80); header = header ushr 7
        }
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7F).toInt()) or 0x80); v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun protoLenField(field: Int, bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var header = (field shl 3) or 2
        while (true) {
            if (header and 0x7F.inv() == 0) { out.write(header); break }
            out.write((header and 0x7F) or 0x80); header = header ushr 7
        }
        var v = bytes.size.toLong()
        while (true) {
            if (v and 0x7F.inv() == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7F).toInt()) or 0x80); v = v ushr 7
        }
        out.write(bytes)
        return out.toByteArray()
    }

    private fun manifest(blockSize: Int, partitionBytes: ByteArray): ByteArray =
        protoVarintField(3, blockSize.toLong()) + protoLenField(13, partitionBytes)

    @Test
    fun `REPLACE 加 ZERO 跨块聚合正确`() {
        // 16 块分区：前 8 块 REPLACE 原始数据（32KB），后 8 块 ZERO；sink 块上限 32KB → 两次 download
        val block = 4096
        val data = ByteArray(8 * block) { i -> ((i % 251)).toByte() }
        val partitionOps = protoLenField(8, op(OtaPayload.OP_REPLACE, 0, (8 * block).toLong(), 0, 8)) +
            protoLenField(8, op(OtaPayload.OP_ZERO, 0, 0, 8, 8))
        val partition = protoLenField(1, "boot".toByteArray()) +
            protoLenField(7, protoVarintField(1, (16 * block).toLong())) + partitionOps
        val manifest = OtaPayload.parseManifest(manifest(block, partition), 32L)

        // payload 数据区（dataOffset=32 起）：前 32 字节头，之后是 REPLACE 数据
        val payload = ByteArray(32) + data + ByteArray(64)
        val sink = FakeSink(maxChunk = 16 * block)

        runBlocking {
            OtaFlasher(sink).flashPartition(
                manifest.partitions[0], manifest, FakeSource(payload),
                onProgress = { _, _ -> }, onStage = { }
            )
        }

        assertEquals(16 * block, sink.assembled.size)
        assertEquals(data.toList(), sink.assembled.subList(0, 8 * block))
        assertEquals(List(8 * block) { 0.toByte() }, sink.assembled.subList(8 * block, 16 * block))
        assertEquals(listOf("boot"), sink.flashCalls)
    }

    @Test
    fun `extent 非顺序时拒绝`() {
        val block = 4096
        val partitionOps = protoLenField(8, op(OtaPayload.OP_REPLACE, 0, 8 * block.toLong(), 1, 2))
        val partition = protoLenField(1, "boot".toByteArray()) +
            protoLenField(7, protoVarintField(1, (16 * block).toLong())) + partitionOps
        val manifest = OtaPayload.parseManifest(manifest(block, partition), 0)

        val sink = FakeSink(maxChunk = 16 * block)
        runBlocking {
            try {
                OtaFlasher(sink).flashPartition(
                    manifest.partitions[0], manifest, FakeSource(ByteArray(64)),
                    onProgress = { _, _ -> }, onStage = { }
                )
                org.junit.Assert.fail("应当拒绝非顺序 extent")
            } catch (t: com.dz.fastboototg.core.AppException) {
                assertTrue(t.message!!.contains("非顺序"))
            }
        }
    }

    @Test
    fun `差分操作在刷写前拒绝`() {
        val block = 4096
        val partitionOps = protoLenField(8, op(OtaPayload.OP_BSDIFF, 0, 100, 0, 2))
        val partition = protoLenField(1, "boot".toByteArray()) +
            protoLenField(7, protoVarintField(1, (16 * block).toLong())) + partitionOps
        val manifest = OtaPayload.parseManifest(manifest(block, partition), 0)

        runBlocking {
            try {
                OtaFlasher(FakeSink(8 * block)).flashPartition(
                    manifest.partitions[0], manifest, FakeSource(ByteArray(64)),
                    onProgress = { _, _ -> }, onStage = { }
                )
                org.junit.Assert.fail("应当拒绝差分操作")
            } catch (t: com.dz.fastboototg.core.AppException) {
                assertTrue(t.message!!.contains("差分"))
            }
        }
    }
}
