package com.dz.fastboototg.flash

import com.dz.fastboototg.core.io.RandomAccessSource
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.usb.FastbootException
import com.dz.fastboototg.usb.fastboot.FastbootProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Bounded single-image flash. Oversized images are rejected before download. */
class FlashOrchestrator(
    private val protocol: FastbootProtocol,
    private val onProgress: (written: Long, total: Long) -> Unit,
    private val onStage: (String) -> Unit
) {

    suspend fun flash(partition: String, source: RandomAccessSource) = withContext(Dispatchers.IO) {
        source.use {
            val total = source.length
            if (total <= 0L) {
                throw FastbootException("镜像文件是空的", "重新下载，或者重新选择一个镜像文件。")
            }

            val head = ByteArray(minOf(28L, total).toInt())
            source.readAt(0, head, 0, head.size)
            val sparse = SparseImage.isSparse(head)
            

            val maxChunk = protocol.maxDownloadSize()
            val capacity = protocol.partitionSize(partition)
            if (total > maxChunk) throw FastbootException("镜像超过安全单次刷写上限 $maxChunk 字节", "当前版本不支持大镜像重分片，请使用电脑 fastboot。")
            val expanded = if (sparse) SparseImage.expandedSize(source) else total

            onStage("准备写入 $partition")
            AppLogger.i("目标分区：$partition")
            AppLogger.i(
                "镜像格式：${if (sparse) "sparse" else "raw 原始镜像"}，共 $total 字节"
            )
            AppLogger.i("设备单次上限：$maxChunk 字节")

            if (capacity != null) {
                AppLogger.i("分区容量：$capacity 字节")
                if (expanded > capacity) {
                    throw FastbootException(
                        "镜像展开后 $expanded 字节超过分区容量 $capacity 字节",
                        "可能选错了镜像，或者这不是该机型的包。"
                    )
                }
            } else {
                AppLogger.w("设备没有上报 $partition 的容量，跳过容量校验继续执行")
            }

            val buffer = ByteArray(total.toInt())
            source.readAt(0, buffer, 0, buffer.size)
            coroutineContext.ensureActive()
            protocol.download(buffer, 0, buffer.size)
            protocol.flash(partition)
            onProgress(total, total)

            onStage("$partition 写入完成")
            AppLogger.i("$partition 写入完成，共 $total 字节")
        }
    }

}
