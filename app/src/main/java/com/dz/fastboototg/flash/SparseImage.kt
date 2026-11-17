package com.dz.fastboototg.flash

import com.dz.fastboototg.core.io.RandomAccessSource

/**
 * Android sparse image 解析。
 *
 * 现代刷机包里的 system / super 镜像基本都是 sparse 格式（用块描述代替全零数据）。
 * 关键点：分块发送时**不能把一个 chunk 切断**，否则设备端解析会失败。
 * 所以需要先扫出所有 chunk 的结束偏移，作为可用的切分点。
 *
 * 文件头（28 字节，小端）：
 *   0  magic        0xED26FF3A
 *   4  major        2 字节
 *   6  minor        2 字节
 *   8  file_hdr_sz  2 字节
 *  10  chunk_hdr_sz 2 字节
 *  12  blk_sz       4 字节
 *  16  total_blks   4 字节
 *  20  total_chunks 4 字节
 *  24  checksum     4 字节
 *
 * 块头（12 字节）：
 *   0  chunk_type  2 字节
 *   2  reserved    2 字节
 *   4  chunk_sz    4 字节（块数）
 *   8  total_sz    4 字节（含块头的总字节数）
 */
object SparseImage {

    private val MAGIC = 0xED26FF3A.toInt()
    private const val FILE_HEADER_SIZE = 28
    private const val CHUNK_HEADER_SIZE = 12

    fun isSparse(head: ByteArray): Boolean =
        head.size >= 4 && readIntLe(head, 0) == MAGIC

    /**
     * 返回每个 chunk 结束处的字节偏移。非 sparse 返回空数组。
     * 只读块头，不读块内容，所以对 GB 级文件也很快。
     */
    fun chunkBoundaries(source: RandomAccessSource): LongArray {
        if (source.length < FILE_HEADER_SIZE) return LongArray(0)

        val header = ByteArray(FILE_HEADER_SIZE)
        source.readAt(0, header, 0, FILE_HEADER_SIZE)
        if (readIntLe(header, 0) != MAGIC) return LongArray(0)

        val fileHeaderSize = readShortLe(header, 8)
        val totalChunks = readIntLe(header, 20)
        if (totalChunks <= 0) return LongArray(0)

        val boundaries = ArrayList<Long>(totalChunks.coerceAtMost(8192))
        val chunkHeader = ByteArray(CHUNK_HEADER_SIZE)
        var position = fileHeaderSize.toLong()

        for (i in 0 until totalChunks) {
            if (position + CHUNK_HEADER_SIZE > source.length) break
            source.readAt(position, chunkHeader, 0, CHUNK_HEADER_SIZE)
            val totalSize = readIntLe(chunkHeader, 8)
            if (totalSize <= 0) break
            position += totalSize
            boundaries.add(position)
        }
        return boundaries.toLongArray()
    }

    fun expandedSize(source: RandomAccessSource): Long {
        require(source.length >= 28) { "sparse 文件头不完整" }
        val h = ByteArray(28); source.readAt(0, h, 0, 28)
        require(readIntLe(h, 0) == MAGIC && readShortLe(h, 4) == 1)
        val header = readShortLe(h, 8); val chunkHeader = readShortLe(h, 10)
        val block = readIntLe(h, 12).toLong() and 0xffffffffL
        val blocks = readIntLe(h, 16).toLong() and 0xffffffffL
        val chunks = readIntLe(h, 20).toLong() and 0xffffffffL
        require(header >= 28 && chunkHeader >= 12 && block > 0 && block % 4 == 0L)
        require(chunks <= (source.length - header) / chunkHeader)
        var pos = header.toLong(); var count = 0L
        val ch = ByteArray(12)
        repeat(chunks.toInt()) {
            require(pos <= source.length - chunkHeader)
            source.readAt(pos, ch, 0, 12)
            val n = readIntLe(ch, 4).toLong() and 0xffffffffL
            val size = readIntLe(ch, 8).toLong() and 0xffffffffL
            val payload = when (readShortLe(ch, 0)) {
                0xcac1 -> Math.multiplyExact(n, block)
                0xcac2 -> 4L
                0xcac3 -> 0L
                0xcac4 -> { require(n == 0L); 4L }
                else -> error("未知 sparse chunk")
            }
            require(size == chunkHeader + payload && size <= source.length - pos)
            count = Math.addExact(count, n); pos += size
        }
        require(pos == source.length && count == blocks) { "sparse 长度或块数不一致" }
        return Math.multiplyExact(block, blocks)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
