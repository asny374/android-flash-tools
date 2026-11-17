package com.dz.fastboototg.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.dz.fastboototg.core.AppConfig
import com.dz.fastboototg.core.log.AppLogger

/**
 * 通用 bulk 通道抽象。
 *
 * 提取成接口的原因：fastboot 帧解析、ADB 消息解析都是纯逻辑，
 * 依赖接口后可以在 JVM 单元测试里用假通道喂字节流，不用真机。
 * TCP（无线 ADB）也是这个接口的实现。
 */
interface BulkChannel : AutoCloseable {
    val name: String

    /** 发送整段字节（等价于 write(bytes, 0, bytes.size)） */
    fun write(bytes: ByteArray): Int = write(bytes, 0, bytes.size)

    /**
     * 发送字节数组的一段。
     * 大块数据（几十 MB）必须由调用方切小块发送 —— 单片超过写超时会被取消，
     * 设备只收到半截数据。
     */
    fun write(bytes: ByteArray, offset: Int, length: Int): Int

    /** 接收字节，返回实际字节数，<= 0 表示失败 */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** 清除两端点的 halt 状态 */
    fun clearHalt(): Boolean

    override fun close()
}

/**
 * USB 批量通道实现 —— **只走 libusb**。
 *
 * 历史：最初同时挂了 Java bulkTransfer / UsbRequest / controlTransfer / libusb
 * 四条路径，实测 Java 路径在部分主机上 bulk OUT 拿不到 ACK（数据半路丢失、
 * 设备零响应），而 libusb 走自己的 URB + 事件循环一直稳定。现在删掉所有
 * Java 分支，只保留被证实可靠的那条路，代码与排障路径都最短。
 */
class UsbBulkChannel(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    /** libusb 句柄。会话层做 USB 设备级重置时需要它 */
    private var handle: Long,
    private val interfaceNumber: Int = 0
) : BulkChannel {

    private val lock = Any()
    val nativeHandle: Long get() = synchronized(lock) { check(handle != 0L); handle }

    override val name: String get() = "libusb"

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            val t0 = System.nanoTime()
            val code = NativeUsb.nativeBulkOut(
                nativeHandle, endpointOut.address, bytes, offset, length,
                AppConfig.USB_WRITE_TIMEOUT_MS
            )
            AppLogger.d(
                "[$name] 发送 $length 字节 → 返回码 $code，耗时 ${elapsed(t0)}ms"
            )
            return code
        }
    }

    /** End an ADB payload that occupies an exact number of USB packets. */
    fun finishAdbPayload(length: Int) {
        if (length > 0 && length % endpointOut.maxPacketSize == 0) {
            val result = write(ByteArray(0))
            if (result < 0) throw com.dz.fastboototg.usb.adb.AdbException(
                "ADB USB 包结束失败（$result）", "请重新连接设备。"
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        synchronized(lock) {
            val t0 = System.nanoTime()
            val code = NativeUsb.nativeBulkIn(
                nativeHandle, endpointIn.address, buffer, offset, length, timeoutMs
            )
            val spent = elapsed(t0)
            // 读取失败一律用 WARN，保证不开详细日志也能看到 —— 这个返回码是定位问题的关键。
            // 耗时同样重要：「瞬时返回 -1」（连接已死）和「等满超时返回 -1」（设备没答）是两回事。
            if (code <= 0) {
                AppLogger.w("[$name] 接收失败 → $code${suffix(code)}，耗时 ${spent}ms")
            } else {
                AppLogger.d("[$name] 接收 $code 字节，耗时 ${spent}ms")
            }
            return code
        }
    }

    override fun clearHalt(): Boolean = synchronized(lock) {
        val out = runCatching { NativeUsb.nativeClearHalt(nativeHandle, endpointOut.address) }
            .getOrDefault(-1)
        val incoming = runCatching { NativeUsb.nativeClearHalt(nativeHandle, endpointIn.address) }
            .getOrDefault(-1)
        AppLogger.i("[libusb] clear_halt OUT=$out IN=$incoming")
        out == 0 && incoming == 0
    }

    override fun close() = synchronized(lock) {
        val old = handle
        if (old != 0L) {
            handle = 0L
            runCatching { NativeUsb.nativeRelease(old, interfaceNumber) }
            runCatching { NativeUsb.nativeClose(old) }
            runCatching { connection.close() }
        }
    }

    private fun elapsed(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun suffix(code: Int): String =
        if (code < 0) "（${NativeUsb.describeError(code)}）" else ""
}
