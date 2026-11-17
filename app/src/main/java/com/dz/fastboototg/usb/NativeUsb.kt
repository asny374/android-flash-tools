package com.dz.fastboototg.usb

/**
 * libusb 的 Kotlin 桥。
 *
 * 为什么不直接用 Android 的 UsbDeviceConnection：两者的底层 ioctl 路径不同。
 * 实测（对照 CableBee）在部分主机上 Java API 的 bulk 传输拿不到 ACK，
 * 而 libusb 走自己的 URB + 事件循环可以正常通信。
 *
 * 返回值约定：>= 0 为实际字节数，< 0 为 libusb 错误码（如 -7 = TIMEOUT）。
 */
object NativeUsb {

    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("fastbootusb")
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message
        }
    }

    val isAvailable: Boolean get() = loaded
    val error: String? get() = loadError

    external fun nativeVersion(): String
    external fun nativeOpen(fd: Int): Long
    external fun nativeClose(handle: Long)
    external fun nativeClaim(handle: Long, interfaceNumber: Int): Int
    external fun nativeRelease(handle: Long, interfaceNumber: Int): Int
    external fun nativeClearHalt(handle: Long, endpoint: Int): Int
    external fun nativeReset(handle: Long): Int

    external fun nativeBulkOut(
        handle: Long, endpoint: Int, data: ByteArray,
        offset: Int, length: Int, timeoutMs: Int
    ): Int

    external fun nativeBulkIn(
        handle: Long, endpoint: Int, buffer: ByteArray,
        offset: Int, length: Int, timeoutMs: Int
    ): Int

    external fun nativeControl(
        handle: Long, requestType: Int, request: Int, value: Int, index: Int,
        data: ByteArray?, length: Int, timeoutMs: Int
    ): Int

    external fun nativeErrorText(code: Int): String

    /** 把 libusb 错误码翻译成人话 */
    fun describeError(code: Int): String = when (code) {
        -1 -> "IO 错误"
        -2 -> "参数无效"
        -3 -> "内存不足"
        -4 -> "不支持该操作"
        -5 -> "设备不存在或已断开"
        -6 -> "接口被占用"
        -7 -> "传输超时（设备没有应答）"
        -8 -> "管道错误（端点可能已挂起）"
        -9 -> "连接被中断"
        -10 -> "操作被取消"
        -11 -> "传输溢出"
        -12 -> "设备已失去连接"
        else -> "libusb 错误 $code"
    }
}
