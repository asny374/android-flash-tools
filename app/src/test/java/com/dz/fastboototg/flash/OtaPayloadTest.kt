package com.dz.fastboototg.flash

import com.dz.fastboototg.core.io.RandomAccessSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** OTA payload.bin 解析测试：手造最小 payload 验证头 / protobuf / 分区清单 */
class OtaPayloadTest {

    private class FakeSource(private val data: ByteArray) : RandomAccessSource {
        override val length: Long get() = data.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int) {
            System.arraycopy(data, position.toInt(), buffer, offset, count)
        }
        override fun close() {}
    }

    // ---------- protobuf 编码工具（与线上格式严格对应） ----------

    private fun u64be(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }
    private fun u32be(v: Long): ByteArray = ByteArray(4) { i -> ((v ushr (8 * (3 - i))) and 0xFF).toByte() }

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var value = v
        while (true) {
            if (value and 0x7F.inv() == 0L) {
                out.write(value.toInt()); break
            }
            out.write(((value and 0x7F).toInt()) or 0x80)
            value = value ushr 7
        }
        return out.toByteArray()
    }

    /** varint 字段：wire type 0 */
    private fun vfield(field: Int, value: Long): ByteArray = varint(((field shl 3) or 0).toLong()) + varint(value)

    /** 长度前缀字段：wire type 2 */
    private fun lenField(field: Int, bytes: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(bytes.size.toLong()) + bytes

    /** extent{start_block=field1, num_blocks=field2}，均为 varint */
    private fun extent(start: Long, num: Long): ByteArray = vfield(1, start) + vfield(2, num)

    /** operation{type=REPLACE_BZ, data_offset=100, data_length=50, extents=[{0,4}]} */
    private fun operation(): ByteArray =
        vfield(1, OtaPayload.OP_REPLACE_BZ.toLong()) +
            vfield(3, 100) +
            vfield(4, 50) +
            lenField(6, extent(0, 4))

    /** partition_update{name="boot", new_partition_info{size=16384}, ops=[...]} */
    private fun partitionUpdate(): ByteArray =
        lenField(1, "boot".toByteArray(Charsets.US_ASCII)) +
            lenField(7, vfield(1, 16384)) +
            lenField(8, operation())

    /** 完整 payload.bin */
    private fun buildPayload(): ByteArray {
        val manifest = vfield(3, 4096) + lenField(13, partitionUpdate())
        val header = "CrAU".toByteArray(Charsets.US_ASCII) +
            u64be(2) + u64be(manifest.size.toLong()) + u32be(16)
        return header + manifest + ByteArray(16) + ByteArray(1024)
    }

    // ---------- 用例 ----------

    @Test
    fun `解析 payload 头与分区清单`() {
        val manifest = OtaPayload.parse(FakeSource(buildPayload()))
        assertEquals(4096, manifest.blockSize)
        assertEquals(1, manifest.partitions.size)

        val partition = manifest.partitions[0]
        assertEquals("boot", partition.name)
        assertEquals(16384L, partition.size)
        assertEquals(1, partition.ops.size)

        val op = partition.ops[0]
        assertEquals(OtaPayload.OP_REPLACE_BZ, op.type)
        assertEquals(100L, op.dataOffset)
        assertEquals(50L, op.dataLength)
        assertEquals(1, op.extents.size)
        assertEquals(0L, op.extents[0].startBlock)
        assertEquals(4L, op.extents[0].numBlocks)
    }

    @Test
    fun `整包校验通过`() {
        val manifest = OtaPayload.parse(FakeSource(buildPayload()))
        OtaPayload.ensureFullPackage(manifest.partitions[0])  // 不抛异常即通过
        assertTrue(true)
    }

    @Test(expected = com.dz.fastboototg.core.AppException::class)
    fun `魔数不对时拒绝`() {
        val payload = buildPayload()
        payload[0] = 'X'.code.toByte()
        OtaPayload.parse(FakeSource(payload))
    }

    @Test(expected = com.dz.fastboototg.core.AppException::class)
    fun `差分操作被拒绝`() {
        val diffPartition = lenField(1, "boot".toByteArray(Charsets.US_ASCII)) +
            lenField(7, vfield(1, 16384)) +
            lenField(8, vfield(1, OtaPayload.OP_BSDIFF.toLong()) +
                vfield(3, 100) + vfield(4, 50) +
                lenField(6, extent(0, 4)))
        val manifest = vfield(3, 4096) + lenField(13, diffPartition)
        val parsed = OtaPayload.parseManifest(manifest, 0)
        OtaPayload.ensureFullPackage(parsed.partitions[0])
    }

    @Test
    fun `多分区解析`() {
        val manifest = vfield(3, 4096) +
            lenField(13, partitionUpdate()) +
            lenField(13, partitionUpdate())
        val parsed = OtaPayload.parseManifest(manifest, 0)
        assertEquals(2, parsed.partitions.size)
        assertEquals("boot", parsed.partitions[0].name)
    }

    @Test
    fun `区域流按边界截断`() {
        val data = ByteArray(100) { it.toByte() }
        val stream = OtaPayload.SourceRegionStream(FakeSource(data), 10, 20)
        val buf = ByteArray(64)
        var total = 0
        while (true) {
            val n = stream.read(buf, total, 64 - total)
            if (n <= 0) break
            total += n
        }
        assertEquals(20, total)
        assertEquals(10.toByte(), buf[0])
    }
}
