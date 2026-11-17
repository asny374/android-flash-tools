package com.dz.fastboototg.usb.adb

import com.dz.fastboototg.usb.BulkChannel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 经典 TCP 传输（adb tcpip 模式）。
 *
 * 实现 [BulkChannel] 后，ADB 协议栈（消息帧 / RSA 认证 / shell / sync 文件传输）
 * 与 USB 通道完全共用 —— 换传输层不动协议层。
 *
 * 返回值约定与 libusb 对齐：-7 超时、-1 IO 错误，方便上层统一翻译错误信息。
 *
 * 注意：这支持的是 `adb tcpip 5555` 的传统 TCP 模式；Android 11+ 开发者选项里的
 * 「无线调试」（mDNS + TLS 配对码）是另一套协议，暂不支持。
 */
class TcpChannel(
    private val socket: Socket,
    host: String
) : BulkChannel {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val name: String = "TCP($host)"

    override fun write(bytes: ByteArray): Int = write(bytes, 0, bytes.size)

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        output.write(bytes, offset, length)
        output.flush()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE)
        return try {
            val n = input.read(buffer, offset, length)
            if (n > 0) n else -1  // EOF / 无数据一律按失败，让上层按超时或 IO 错误处理
        } catch (e: SocketTimeoutException) {
            -7
        } catch (e: IOException) {
            -1
        }
    }

    /** TCP 没有 halt 概念，恒成功 */
    override fun clearHalt(): Boolean = true

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {

        /** 建立到目标机 adbd 的 TCP 连接 */
        fun connect(host: String, port: Int, timeoutMs: Int): TcpChannel {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = DEFAULT_READ_TIMEOUT
                return TcpChannel(socket, "$host:$port")
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw AdbException(
                    "无法连接 $host:$port（${e.javaClass.simpleName}）",
                    "确认目标机已开启 tcpip 模式（先 USB 连接后点「开启无线模式」），且两台设备在同一 Wi-Fi 网络。"
                )
            }
        }

        const val DEFAULT_READ_TIMEOUT = 15_000
    }
}
