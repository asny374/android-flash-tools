package com.dz.fastboototg.flash

import com.dz.fastboototg.core.AppException
import com.dz.fastboototg.core.io.RandomAccessSource
import java.io.InputStream

/**
 * Android OTA 升级包 payload.bin 的解析与整包刷写。
 *
 * payload.bin 结构（AOSP update_engine，v2 格式）：
 * ```
 * "CrAU"(4) | major(u64 BE) | manifest_size(u64 BE) | meta_sig_size(u32 BE)
 * manifest（protobuf DeltaArchiveManifest）
 * metadata_signature
 * data blobs（各操作的原始/压缩数据，按 data_offset 定位）
 * ```
 *
 * 支持范围：**整包全量 OTA**（操作类型 REPLACE / REPLACE_BZ / ZERO）。
 * 差分包（BSDIFF / PUFFDIFF / MOVE 等需要旧分区内容的操作）明确拒绝，
 * 因为 fastboot 侧读不回旧分区内容，差分重建在本应用里无从谈起。
 */
object OtaPayload {

    const val MAGIC = "CrAU"

    // 操作类型（AOSP InstallOperation.Type）
    const val OP_REPLACE = 0
    const val OP_REPLACE_BZ = 1
    const val OP_MOVE = 2
    const val OP_BSDIFF = 3
    const val OP_SOURCE_COPY = 4
    const val OP_SOURCE_BSDIFF = 5
    const val OP_ZERO = 6
    const val OP_DISCARD = 7
    const val OP_REPLACE_RC = 8
    const val OP_BROTLI_BSDIFF = 9
    const val OP_PUFFDIFF = 10

    /** 一个刷写操作 */
    data class OtaOp(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val extents: List<OtaExtent>
    )

    data class OtaExtent(val startBlock: Long, val numBlocks: Long)

    /** 一个分区：名字 + 新分区大小 + 操作序列 */
    data class OtaPartition(
        val name: String,
        val size: Long,
        val ops: List<OtaOp>
    )

    data class OtaManifest(
        val blockSize: Int,
        val partitions: List<OtaPartition>,
        /** data blob 区的起始偏移（payload.bin 内） */
        val dataOffset: Long
    )

    // ---------- 解析入口 ----------

    fun parse(source: RandomAccessSource): OtaManifest {
        if (source.length < 24) throw AppException("payload.bin 文件不完整", "文件只有 ${source.length} 字节，重新导出一次。")

        val header = ByteArray(24)
        source.readAt(0, header, 0, 24)
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        if (magic != MAGIC) {
            throw AppException(
                "这不是 payload.bin 文件（魔数 $magic）",
                "请选择 OTA 包里的 payload.bin（zip 包直接选 zip 也行，应用会自动提取）。"
            )
        }
        val major = readU64Be(header, 4)
        val manifestSize = readU64Be(header, 12)
        val metaSigSize = readU32Be(header, 20)

        if (manifestSize <= 0 || manifestSize > 16L * 1024 * 1024) {
            throw AppException("payload 清单异常（$manifestSize 字节）", "文件可能损坏，重新下载刷机包。")
        }
        if (major != 2L) {
            throw AppException("payload 版本过旧（v$major）", "应用仅支持 v2 格式的整包。")
        }

        if (24L + manifestSize + metaSigSize > source.length) throw AppException("payload 元数据越界")
        val manifestBytes = ByteArray(manifestSize.toInt())
        source.readAt(24, manifestBytes, 0, manifestBytes.size)
        val dataOffset = 24L + manifestSize + metaSigSize

        return parseManifest(manifestBytes, dataOffset)
    }

    // ---------- protobuf 解析（只实现本场景需要的字段） ----------

    internal fun parseManifest(bytes: ByteArray, dataOffset: Long): OtaManifest {
        var blockSize = 4096
        val partitions = mutableListOf<OtaPartition>()
        val reader = ProtobufReader(bytes)
        while (reader.hasNext()) {
            val (field, wire) = reader.readTag()
            when {
                field == 13 && wire == 2 -> {
                    partitions += parsePartition(reader.readBytes())
                }
                field == 3 && wire == 0 -> {
                    val v = reader.readVarint().toInt()
                    if (v > 0) blockSize = v
                }
                else -> reader.skip(wire)
            }
        }
        if (partitions.isEmpty()) {
            throw AppException("payload 里没有可刷的分区", "这可能不是给 fastboot 用的整包。")
        }
        return OtaManifest(blockSize, partitions, dataOffset)
    }

    private fun parsePartition(bytes: ByteArray): OtaPartition {
        var name = ""
        var size = -1L
        val ops = mutableListOf<OtaOp>()
        val reader = ProtobufReader(bytes)
        while (reader.hasNext()) {
            val (field, wire) = reader.readTag()
            when {
                field == 1 && wire == 2 -> name = String(reader.readBytes(), Charsets.US_ASCII)
                field == 7 && wire == 2 -> size = parseImageInfo(reader.readBytes())
                field == 8 && wire == 2 -> ops += parseOperation(reader.readBytes())
                else -> reader.skip(wire)
            }
        }
        return OtaPartition(name, size, ops)
    }

    private fun parseImageInfo(bytes: ByteArray): Long {
        val reader = ProtobufReader(bytes)
        var size = -1L
        while (reader.hasNext()) {
            val (field, wire) = reader.readTag()
            if (field == 1 && wire == 0) size = reader.readVarint() else reader.skip(wire)
        }
        return size
    }

    private fun parseOperation(bytes: ByteArray): OtaOp {
        val reader = ProtobufReader(bytes)
        var type = -1
        var dataOffset = 0L
        var dataLength = 0L
        val extents = mutableListOf<OtaExtent>()
        while (reader.hasNext()) {
            val (field, wire) = reader.readTag()
            when {
                field == 1 && wire == 0 -> type = reader.readVarint().toInt()
                field == 3 && wire == 0 -> dataOffset = reader.readVarint()
                field == 4 && wire == 0 -> dataLength = reader.readVarint()
                field == 6 && wire == 2 -> extents += parseExtent(reader.readBytes())
                else -> reader.skip(wire)
            }
        }
        return OtaOp(type, dataOffset, dataLength, extents)
    }

    private fun parseExtent(bytes: ByteArray): OtaExtent {
        val reader = ProtobufReader(bytes)
        var start = 0L
        var num = 0L
        while (reader.hasNext()) {
            val (field, wire) = reader.readTag()
            when {
                field == 1 && wire == 0 -> start = reader.readVarint()
                field == 2 && wire == 0 -> num = reader.readVarint()
                else -> reader.skip(wire)
            }
        }
        return OtaExtent(start, num)
    }

    // ---------- 校验 ----------

    /** 分区操作是否全是整包类型（REPLACE/REPLACE_BZ/ZERO），差分操作直接判不支持 */
    fun ensureFullPackage(partition: OtaPartition) {
        val unsupported = partition.ops.map { it.type }.filter {
            it != OP_REPLACE && it != OP_REPLACE_BZ && it != OP_ZERO
        }.distinct()
        if (unsupported.isNotEmpty()) {
            throw AppException(
                "${partition.name} 的 OTA 包含差分操作（类型 $unsupported）",
                "应用仅支持整包全量 OTA（完整刷机包），差分包无法在 fastboot 侧重建。"
            )
        }
    }

    // ---------- 工具 ----------

    internal fun readU64Be(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    internal fun readU32Be(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    /** 从 [RandomAccessSource] 的固定区间顺序读出字节流（给 bzip2 解压器用） */
    class SourceRegionStream(
        private val source: RandomAccessSource,
        private var position: Long,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            val buf = ByteArray(1)
            return if (read(buf, 0, 1) == 1) buf[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining, 1024L * 512).toInt()
            source.readAt(position, b, off, want)
            position += want
            remaining -= want
            return want
        }
    }

    // ---------- 最小 protobuf 读取器 ----------

    internal class ProtobufReader(private val data: ByteArray) {
        private var pos = 0

        fun hasNext(): Boolean = pos < data.size

        /** 返回 (fieldNumber, wireType) */
        fun readTag(): Pair<Int, Int> {
            val tag = readVarint().toInt()
            return (tag ushr 3) to (tag and 0x7)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size) throw AppException("payload 清单解析越界", "文件可能损坏。")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw AppException("payload 清单 varint 过长", "文件可能损坏。")
            }
            return result
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || len > data.size - pos) {
                throw AppException("payload 清单字段越界", "文件可能损坏。")
            }
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> { val len = readVarint(); if (len < 0 || len > data.size - pos) throw AppException("payload 字段越界"); pos += len.toInt() }
                5 -> pos += 4
                else -> throw AppException("payload 清单含未知线型 $wireType", "文件可能损坏。")
            }
            if (pos > data.size) throw AppException("payload 清单解析越界", "文件可能损坏。")
        }
    }
}
