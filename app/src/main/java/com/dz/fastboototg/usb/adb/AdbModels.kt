package com.dz.fastboototg.usb.adb

import com.dz.fastboototg.core.AppException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ADB 流程中的可预期失败，hint 面向用户给出下一步建议 */
class AdbException(
    message: String,
    hint: String = ""
) : AppException(message, hint)

/** ADB 协议的六种消息命令 */
object AdbCommand {
    const val CNXN = "CNXN"
    const val AUTH = "AUTH"
    const val OPEN = "OPEN"
    const val OKAY = "OKAY"
    const val WRTE = "WRTE"
    const val CLSE = "CLSE"
}

/** AUTH 的 arg0 类型 */
object AdbAuthType {
    const val TOKEN = 1
    const val SIGNATURE = 2
    const val RSAPUBLICKEY = 3
}

/**
 * ADB 消息帧。
 *
 * 线上格式为 24 字节小端头 + 载荷：
 * ```
 * command(4) | arg0(4) | arg1(4) | data_length(4) | data_checksum(4) | magic(4)
 * ```
 * magic = command 按位取反（adbd 会校验，错了静默丢包）。
 */
data class AdbMessage(
    val command: String,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray
) {

    override fun equals(other: Any?): Boolean =
        other is AdbMessage && command == other.command &&
            arg0 == other.arg0 && arg1 == other.arg1 &&
            data.contentEquals(other.data)

    override fun hashCode(): Int =
        31 * (31 * (31 * command.hashCode() + arg0) + arg1) + data.contentHashCode()

    fun describe(): String = when (command) {
        AdbCommand.CNXN -> "CNXN 版本=%08x 上限=$arg1".format(arg0.toLong() and 0xFFFFFFFFL)
        AdbCommand.AUTH -> "AUTH 类型=${when (arg0) {
            AdbAuthType.TOKEN -> "TOKEN"
            AdbAuthType.SIGNATURE -> "SIGNATURE"
            AdbAuthType.RSAPUBLICKEY -> "RSAPUBLICKEY"
            else -> arg0.toString()
        }} 载荷=${data.size}B"
        AdbCommand.OPEN -> "OPEN 本地=$arg0 服务=${String(data, Charsets.US_ASCII)}"
        AdbCommand.OKAY -> "OKAY 本地=$arg0 远端=$arg1"
        AdbCommand.WRTE -> "WRTE 本地=$arg0 远端=$arg1 载荷=${data.size}B"
        AdbCommand.CLSE -> "CLSE 本地=$arg0 远端=$arg1"
        else -> "$command arg0=$arg0 arg1=$arg1 载荷=${data.size}B"
    }

    companion object {

        const val HEADER_SIZE = 24

        fun encode(command: String, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): AdbMessage =
            AdbMessage(command, arg0, arg1, data)

        /** ADB v1 checksum is the unsigned byte sum, not CRC32. */
        fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
            var sum = 0
            for (i in offset until offset + length) sum += data[i].toInt() and 0xff
            return sum
        }

        /**
         * 计算 ADB magic：命令 4 字节 ASCII 按小端序解释成 int 后按位取反。
         * adbd 收包时校验 magic，不对就静默丢包。
         */
        fun commandMagic(command: String): Int {
            val cmd = command.toByteArray(Charsets.US_ASCII)
            val b = ByteArray(4)
            System.arraycopy(cmd, 0, b, 0, minOf(4, cmd.size))
            val littleEndian = (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
            return littleEndian.inv()
        }

        fun serialize(message: AdbMessage): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + message.data.size)
                .order(ByteOrder.LITTLE_ENDIAN)
            val cmdBytes = ByteArray(4)
            val raw = message.command.toByteArray(Charsets.US_ASCII)
            System.arraycopy(raw, 0, cmdBytes, 0, minOf(4, raw.size))
            buf.put(cmdBytes)
            buf.putInt(message.arg0)
            buf.putInt(message.arg1)
            buf.putInt(message.data.size)
            buf.putInt(checksum(message.data))
            buf.putInt(commandMagic(message.command))
            buf.put(message.data)
            return buf.array()
        }

        /**
         * 从缓冲里解析一帧。输入必须包含完整帧（头 + 载荷），
         * 恰好多出的字节会留在 [consumed] 中返回的长度之外由调用方处理。
         *
         * @param buffer 含至少一帧的字节缓冲
         * @param available 当前有效字节数
         * @return Pair(消息, 本帧消耗的总字节数)，头不完整返回 null，magic 校验失败抛异常
         */
        fun parse(buffer: ByteArray, available: Int, verifyChecksum: Boolean = true): Pair<AdbMessage, Int>? {
            if (available < HEADER_SIZE) return null
            val header = ByteBuffer.wrap(buffer, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val cmdBytes = ByteArray(4)
            header.get(cmdBytes)
            val command = String(cmdBytes, Charsets.US_ASCII)
            val arg0 = header.int
            val arg1 = header.int
            val dataLength = header.int
            val crc = header.int
            val magic = header.int

            val expectedMagic = (cmdBytes[0].toInt() and 0xFF) or
                ((cmdBytes[1].toInt() and 0xFF) shl 8) or
                ((cmdBytes[2].toInt() and 0xFF) shl 16) or
                ((cmdBytes[3].toInt() and 0xFF) shl 24)
            if (magic != expectedMagic.inv()) {
                throw AdbException("ADB 消息 magic 校验失败", "收到 $command magic=%08x，流已错位，请重插数据线。".format(magic.toLong() and 0xFFFFFFFFL))
            }
            if (dataLength < 0 || dataLength > MAX_PAYLOAD_HARD_LIMIT) {
                throw AdbException("ADB 消息载荷异常（$dataLength 字节）", "可能不是 ADB 设备，或流已错位，请重插数据线。")
            }
            if (available < HEADER_SIZE + dataLength) return null

            val data = buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + dataLength)
            if (verifyChecksum && checksum(data) != crc) {
                throw AdbException("ADB 消息校验和错误", "USB 传输出错，请重插数据线再试。")
            }
            val msg = AdbMessage(command, arg0, arg1, data)
            return msg to (HEADER_SIZE + dataLength)
        }

        /** 防御坏数据：单帧载荷不可能超过 16MB */
        const val MAX_PAYLOAD_HARD_LIMIT = 16 * 1024 * 1024
    }
}
