package com.dz.fastboototg.flash

import com.dz.fastboototg.core.AppException
import com.dz.fastboototg.core.io.RandomAccessSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

interface FlashSink {
    fun maxDownloadSize(): Long
    fun download(data: ByteArray, offset: Int, length: Int)
    fun flash(partition: String)
}

/** Validate and materialize a bounded partition before issuing any destructive command. */
class OtaFlasher(private val sink: FlashSink) {
    suspend fun flashPartition(
        partition: OtaPayload.OtaPartition,
        manifest: OtaPayload.OtaManifest,
        payloadSource: RandomAccessSource,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        OtaPayload.ensureFullPackage(partition)
        val block = manifest.blockSize.toLong()
        if (block <= 0 || partition.size <= 0 || partition.ops.isEmpty()) throw AppException("OTA 分区信息不完整")
        var expected = 0L
        for (op in partition.ops) {
            if (op.extents.isEmpty()) throw AppException("OTA 操作没有目标范围")
            for (ext in op.extents) {
                if (ext.startBlock != expected) throw AppException("OTA extent 非顺序覆盖")
                if (ext.numBlocks <= 0 || ext.numBlocks > partition.size / block - expected) throw AppException("OTA extent 越界")
                expected += ext.numBlocks
            }
            if (op.type != OtaPayload.OP_ZERO && (op.dataOffset < 0 || op.dataLength < 0 ||
                manifest.dataOffset < 0 || manifest.dataOffset > payloadSource.length ||
                op.dataOffset > payloadSource.length - manifest.dataOffset ||
                op.dataLength > payloadSource.length - manifest.dataOffset - op.dataOffset)) throw AppException("OTA 数据越界")
        }
        val limit = minOf(sink.maxDownloadSize(), 64L * 1024 * 1024)
        if (partition.size > limit) throw AppException("OTA 分区超过安全单次刷写上限 $limit 字节", "请使用电脑 fastboot 刷写大分区。")
        val image = ByteArray(partition.size.toInt())
        var position = 0
        for (op in partition.ops) {
            coroutineContext.ensureActive()
            val length = (op.extents.sumOf { it.numBlocks } * block).toInt()
            onStage("校验并解压 ${partition.name}")
            if (op.type != OtaPayload.OP_ZERO) {
                val region = OtaPayload.SourceRegionStream(payloadSource, manifest.dataOffset + op.dataOffset, op.dataLength)
                val input = if (op.type == OtaPayload.OP_REPLACE_BZ) BZip2CompressorInputStream(region) else region
                input.use {
                    var read = 0
                    while (read < length) {
                        coroutineContext.ensureActive()
                        val n = it.read(image, position + read, minOf(length - read, 65536))
                        if (n < 0) break
                        if (n == 0) throw AppException("OTA 解压无进展")
                        read += n
                    }
                    if (it.read() != -1 || length - read >= block) throw AppException("OTA 操作输出长度异常")
                }
            }
            position += length
        }
        coroutineContext.ensureActive()
        onStage("写入 ${partition.name}")
        sink.download(image, 0, image.size)
        sink.flash(partition.name)
        onProgress(partition.size, partition.size)
        onStage("${partition.name} 写入完成")
    }
}
