package com.dz.fastboototg.flash

import com.dz.fastboototg.core.AppException
import com.dz.fastboototg.core.io.RandomAccessSource

/**
 * vbmeta 镜像的 AVB flags 补丁。
 *
 * `fastboot flash vbmeta --disable-verity --disable-verification vbmeta.img`
 * 的客户端实现就是改镜像头里的 flags 字段（偏移 120，u32 大端）再发送：
 * - bit0 (1)：hashtree 禁用 —— 即 --disable-verity
 * - bit1 (2)：验证禁用 —— 即 --disable-verification
 *
 * 改动会使原签名失效，仅在 bootloader 已解锁的设备上有意义（与 PC 端 fastboot 行为一致）。
 */
object VbmetaPatch {

    const val FLAG_HASHTREE_DISABLED = 1L
    const val FLAG_VERIFICATION_DISABLED = 2L

    private const val FLAGS_OFFSET = 120

    fun isVbmeta(head: ByteArray): Boolean =
        head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "AVB0"

    /**
     * 读取 vbmeta 镜像并按需 OR 上禁用标志，返回补丁后的完整镜像字节。
     * vbmeta 镜像很小（几 KB），整块进内存没有压力。
     */
    fun patch(
        source: RandomAccessSource,
        disableVerity: Boolean,
        disableVerification: Boolean
    ): ByteArray {
        if (!disableVerity && !disableVerification) {
            throw AppException("没有选择要禁用的标志", "两个开关至少开一个。")
        }
        if (source.length <= FLAGS_OFFSET + 4 || source.length > 1024 * 1024) {
            throw AppException(
                "文件不像 vbmeta 镜像（${source.length} 字节）",
                "vbmeta 分区镜像通常只有几 KB，确认选的是 vbmeta.img 而不是别的分区。"
            )
        }
        val image = ByteArray(source.length.toInt())
        source.readAt(0, image, 0, image.size)
        if (!isVbmeta(image)) {
            throw AppException(
                "文件缺少 AVB0 魔数",
                "vbmeta 镜像应以 \"AVB0\" 开头。确认选的是 vbmeta.img。"
            )
        }

        var flags = readU32Be(image, FLAGS_OFFSET)
        if (disableVerity) flags = flags or FLAG_HASHTREE_DISABLED
        if (disableVerification) flags = flags or FLAG_VERIFICATION_DISABLED
        writeU32Be(image, FLAGS_OFFSET, flags)

        return image
    }

    private fun readU32Be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeU32Be(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}
