package com.dz.fastboototg.usb.adb
internal object AdbReadSizing {
    // USB requests must fit whole packets; never ask for more than the pending payload needs.
    fun requestSize(remaining: Int): Int {
        require(remaining > 0)
        return ((minOf(remaining, 16384) + 1023) / 1024) * 1024
    }
}
internal object ScreenPng {
    fun extract(bytes: ByteArray): ByteArray {
        val magic = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val start = (0..minOf(4096, bytes.size - magic.size)).firstOrNull { pos -> magic.indices.all { bytes[pos + it] == magic[it] } }
            ?: throw AdbException("目标机未返回 PNG 画面", "请确认目标屏幕已点亮，且当前内容允许截图。")
        return bytes.copyOfRange(start, bytes.size)
    }
}
