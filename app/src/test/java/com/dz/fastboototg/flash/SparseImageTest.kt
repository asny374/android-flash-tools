package com.dz.fastboototg.flash

import com.dz.fastboototg.core.io.RandomAccessSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android sparse 镜像边界扫描测试 */
class SparseImageTest {

    private class FakeSource(private val data: ByteArray) : RandomAccessSource {
        override val length: Long get() = data.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int) {
            System.arraycopy(data, position.toInt(), buffer, offset, count)
        }
        override fun close() {}
    }

    private fun intLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun shortLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte()
    )

    private fun buildSparse(): ByteArray {
        val chunkSize = 4096
        // 头 28 字节
        val header = ByteArray(28)
        System.arraycopy(intLe(0xED26FF3A.toInt()), 0, header, 0, 4)   // magic
        System.arraycopy(shortLe(1), 0, header, 4, 2)          // major
        System.arraycopy(shortLe(0), 0, header, 6, 2)          // minor
        System.arraycopy(shortLe(28), 0, header, 8, 2)         // file_hdr_sz
        System.arraycopy(shortLe(12), 0, header, 10, 2)        // chunk_hdr_sz
        System.arraycopy(intLe(chunkSize), 0, header, 12, 4)   // blk_sz
        System.arraycopy(intLe(2), 0, header, 16, 4)           // total_blks
        System.arraycopy(intLe(2), 0, header, 20, 4)           // total_chunks

        // chunk 1：RAW，total_sz = 12 + 4096（块头 + 块数据，必须与实际字节数一致）
        val chunk1 = ByteArray(12 + chunkSize)
        System.arraycopy(intLe(1), 0, chunk1, 0, 4)                // chunk_type = RAW
        System.arraycopy(intLe(chunkSize / 4096), 0, chunk1, 4, 4) // chunk_sz（块数）
        System.arraycopy(intLe(12 + chunkSize), 0, chunk1, 8, 4)   // total_sz

        // chunk 2：DONTCARE，头 12 字节 = type(4) + chunk_sz(4) + total_sz(4)
        val chunk2 = intLe(3) + intLe(1) + intLe(12)

        return header + chunk1 + chunk2
    }

    @Test
    fun `识别 sparse 魔数`() {
        val head = buildSparse().copyOf(28)
        assertTrue(SparseImage.isSparse(head))
    }

    @Test
    fun `非 sparse 镜像判定为 false`() {
        val head = ByteArray(28)
        assertFalse(SparseImage.isSparse(head))
    }

    @Test
    fun `chunk 边界扫描`() {
        val data = buildSparse()
        val boundaries = SparseImage.chunkBoundaries(FakeSource(data))
        // chunk1 结束 = 28 + 12 + 4096 = 4136；chunk2 结束 = 4136 + 12 = 4148
        assertArrayEquals(longArrayOf(4136L, 4148L), boundaries)
    }

    @Test
    fun `total_chunks 为 0 时返回空`() {
        val data = buildSparse()
        System.arraycopy(intLe(0), 0, data, 20, 4)
        val boundaries = SparseImage.chunkBoundaries(FakeSource(data))
        assertTrue(boundaries.isEmpty())
    }
}
