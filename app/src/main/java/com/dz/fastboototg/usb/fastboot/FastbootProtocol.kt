package com.dz.fastboototg.usb.fastboot

import com.dz.fastboototg.core.AppConfig
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.usb.BulkChannel
import com.dz.fastboototg.usb.FastbootException
import com.dz.fastboototg.usb.FastbootResponse
import com.dz.fastboototg.usb.describe

/** USB fastboot: one response per USB transfer; INFO/TEXT precede terminal replies. */
class FastbootProtocol(private val transport: BulkChannel) : com.dz.fastboototg.flash.FlashSink {

    private val acc = ByteArray(AppConfig.USB_ACC_CAPACITY)
    private var everCommunicated = false

    /**
     * 最近一次读是否「瞬时失败」（返回 <=0 且耗时 <100ms）。
     * 「瞬时 -1」说明这条通道已死（主机侧故障），「等满超时」说明设备只是没答；
     * 前者值得换通道重试，后者换了也救不了。给会话层的自动切换提供依据。
     */
    var lastReadInstantFail: Boolean = false
        private set

    val channel: String get() = transport.name
    val hasCommunicated: Boolean get() = everCommunicated

    // ---------- 收发 ----------

    fun execute(command: String, timeoutMs: Int = AppConfig.FASTBOOT_CMD_TIMEOUT_MS): FastbootResponse {
        val (first, writeCode, readMs) = send(command, timeoutMs)
        val response = finishResponse(first, timeoutMs)
        if (response is FastbootResponse.Timeout) {
            throw FastbootException(
                message = "设备没有应答「$command」",
                hint = "发送返回码 $writeCode、读等待 ${readMs}ms（OUT 方向 -1 不一定代表失败）。${timeoutHint()}"
            )
        }
        everCommunicated = true
        return response
    }

    private fun finishResponse(first: FastbootResponse, timeoutMs: Int): FastbootResponse {
        var response = first
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
        while (response is FastbootResponse.Info) {
            AppLogger.i(response.text)
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (left <= 0) return FastbootResponse.Timeout
            response = readFrame(left)
        }
        return response
    }

    /** 超时排查建议。之前这段文案在 execute 和 probe 里各写了一份，现在收敛到这里。 */
    private fun timeoutHint(): String = if (!everCommunicated) {
        "目标机屏幕确认是 FASTBOOT 吗？如果是，说明主机这条 USB 通道不通，" +
            "换一根 USB-A 转 C 的短线，或换一台手机当主机做对照。"
    } else {
        "连接中途断了，重新插拔数据线再点连接。"
    }

    /**
     * 发一条命令并读一帧，**不抛异常**，把写返回码和读耗时一起交出来。
     * 自检和排障靠这个：只有原始数字能区分「没发出去」和「发出去了没人答」。
     */
    fun send(command: String, timeoutMs: Int): Triple<FastbootResponse, Int, Long> {
        AppLogger.d("→ $command")
        val bytes = command.toByteArray(Charsets.US_ASCII)
        val writeCode = transport.write(bytes)
        if (writeCode != bytes.size) throw FastbootException("USB 命令发送不完整：$writeCode/${bytes.size}")
        val t0 = System.nanoTime()
        val response = readFrame(timeoutMs)
        val readMs = (System.nanoTime() - t0) / 1_000_000
        AppLogger.d("← ${response.describe()}（读 ${readMs}ms，写码 $writeCode）")
        return Triple(response, writeCode, readMs)
    }

    /**
     * 读一帧。
     *
     * 先看累积缓冲里有没有现成的整帧，没有再读；一次读到的多帧会留在缓冲里给下次用。
     * 绝不先只读 4 字节 —— 那样会把设备已经推出来的剩余数据留在端点里，
     * 下一次读直接撞 STALL。
     */
    fun readFrame(timeoutMs: Int): FastbootResponse {
        val n = transport.read(acc, 0, acc.size, timeoutMs)
        if (n <= 0) return FastbootResponse.Timeout
        if (n < 4) throw FastbootException("fastboot 响应头不完整")
        return toResponse(acc.copyOf(n), 4)
    }

    private fun toResponse(frame: ByteArray, headerSize: Int): FastbootResponse {
        val tag = String(frame, 0, 4, Charsets.US_ASCII)
        val payload = if (frame.size > headerSize) {
            String(frame, headerSize, frame.size - headerSize, Charsets.US_ASCII)
        } else {
            ""
        }
        AppLogger.d("收到帧 $tag · ${frame.size} 字节")
        return when (tag) {
            "OKAY" -> FastbootResponse.Okay(payload)
            "FAIL" -> FastbootResponse.Fail(payload)
            "INFO", "TEXT" -> FastbootResponse.Info(payload)
            "DATA" -> FastbootResponse.Data(payload.trim().toLongOrNull(16) ?: 0L)
            else -> FastbootResponse.Unknown("$tag|$payload")
        }
    }

    fun discardPending() {}

    // ---------- 变量 ----------

    /**
     * 探测 + 顺带把变量全收下来。
     *
     * 连接流程以前会先烧一次 getvar:all 探测、再烧一次 getvar:all 读变量，
     * 第二条命令是在上一条流还没收干净的时候发出去的 —— 这本身就可能是
     * 「单条 getvar 全失败」的元凶。现在只发一次。
     */
    fun probe(): Map<String, String> {
        AppLogger.i("协议探测：getvar:all")
        val result = linkedMapOf<String, String>()

        val (first, writeCode, readMs) = send("getvar:all", AppConfig.FASTBOOT_PROBE_TIMEOUT_MS)
        when (first) {
            is FastbootResponse.Info -> parsePair(first.text, result)
            is FastbootResponse.Okay -> parsePair(first.payload, result)
            is FastbootResponse.Fail -> throw FastbootException(
                "设备拒绝返回变量：${first.reason}",
                "部分厂商 bootloader 关闭了 getvar:all，可以改用单条 getvar:product。",
                deviceRejected = true
            )
            FastbootResponse.Timeout -> throw FastbootException(
                "设备没有应答 fastboot 命令",
                "发送返回码 $writeCode、读等待 ${readMs}ms。${timeoutHint()}"
            )
            else -> Unit
        }

        if (first is FastbootResponse.Info) collectVars(result)
        everCommunicated = true
        AppLogger.i("协议探测成功（$channel）：${result.size} 项变量")
        return result
    }

    /** 重复调用就是「读取全部变量」 */
    fun getVarAll(): Map<String, String> {
        AppLogger.i("读取设备变量…")
        val result = probe()
        AppLogger.i("变量读取完成，共 ${result.size} 项")
        return result
    }

    private fun collectVars(out: MutableMap<String, String>) {
        var frames = 0
        while (frames < AppConfig.FASTBOOT_MAX_VAR_FRAMES) {
            when (val next = readFrame(AppConfig.FASTBOOT_FRAME_TIMEOUT_MS)) {
                is FastbootResponse.Info -> {
                    parsePair(next.text, out)
                    frames++
                }
                is FastbootResponse.Okay -> {
                    // 流的结束标志
                    parsePair(next.payload, out)
                    return
                }
                else -> return
            }
        }
    }

    private fun parsePair(raw: String, out: MutableMap<String, String>) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val sep = text.indexOf(": ")
        if (sep > 0) {
            out[text.substring(0, sep)] = text.substring(sep + 2).trim()
            return
        }
        val last = text.lastIndexOf(':')
        if (last > 0) {
            out[text.substring(0, last)] = text.substring(last + 1).trim()
        } else {
            out[text] = ""
        }
    }

    fun getVar(name: String): String? =
        when (val r = execute("getvar:$name")) {
            is FastbootResponse.Okay -> r.payload.trim()
            is FastbootResponse.Info -> r.text.trim()
            else -> null
        }

    override     /**
     * 设备上报的 max-download-size。
     *
     * 部分设备会上报荒唐的大值（实测 MIX 2S 报了 32GB），照单全收会把整个镜像
     * 塞进一个 USB 传输块，Java 通道 2 秒写超时根本搬不完，URB 被取消后设备
     * 只收到半截数据、永远不回 OKAY —— 表现为「卡在第 1 块」。
     * 所以封顶 [SANE_MAX_DOWNLOAD]：大镜像必须使用合法的 resparse；不能假设追加写是
     * 的标准行为，超限镜像由刷写层在写入前拒绝。
     */
    fun maxDownloadSize(): Long {
        val raw = getVar("max-download-size").orEmpty()
        return raw.removePrefix("0x").trim().toLongOrNull(16)?.takeIf { it > 0 }
            ?.coerceAtMost(SANE_MAX_DOWNLOAD)
            ?: minOf(AppConfig.FASTBOOT_DEFAULT_MAX_DOWNLOAD, SANE_MAX_DOWNLOAD)
    }

    fun partitionSize(partition: String): Long? {
        val raw = getVar("partition-size:$partition").orEmpty()
        return raw.removePrefix("0x").trim().toLongOrNull(16)
    }

    fun isUserspaceFastboot(): Boolean = getVar("is-userspace")?.equals("yes", true) == true

    // ---------- 刷写 ----------

    override fun download(data: ByteArray, offset: Int, length: Int) {
        val command = "download:" + length.toString(16).padStart(8, '0')
        AppLogger.d("→ $command")
        val bytes = command.toByteArray(Charsets.US_ASCII)
        val writeCode = transport.write(bytes)
        if (writeCode != bytes.size) throw FastbootException("USB 命令发送不完整：$writeCode/${bytes.size}")

        when (val handshake = finishResponse(readFrame(AppConfig.FASTBOOT_HANDSHAKE_TIMEOUT_MS), AppConfig.FASTBOOT_HANDSHAKE_TIMEOUT_MS)) {
            is FastbootResponse.Data -> if (handshake.maxChunk != length.toLong()) throw FastbootException("设备接收长度不匹配")
            is FastbootResponse.Fail -> throw FastbootException(
                "设备拒绝接收数据块：${handshake.reason}",
                "通常是镜像格式不对，或单块大小超过设备上限。",
                deviceRejected = true
            )
            FastbootResponse.Timeout -> throw FastbootException(
                "等待设备准备接收数据时超时（写入返回码 $writeCode）",
                "连接已中断，重新插拔数据线。"
            )
            else -> throw FastbootException("数据块握手异常：${handshake.describe()}")
        }

        // 数据块分片写入：单片必须在写超时（2s）内搬完，否则 URB 被取消、
        // 设备只收到半截数据、永远不回 OKAY。512KB 在 USB 2.0 最低速率下也 <1s。
        var sent = 0
        while (sent < length) {
            val n = minOf(DATA_WRITE_CHUNK, length - sent)
            val chunkCode = transport.write(data, offset + sent, n)
            if (n >= DATA_WRITE_CHUNK) {
                AppLogger.d("数据块分片：已发 ${sent + n}/$length（单片返回码 $chunkCode）")
            }
            if (chunkCode <= 0 || chunkCode > n) throw FastbootException("USB 数据发送失败：$chunkCode")
            sent += chunkCode
        }
        AppLogger.d("数据块 $length 字节发送完毕（分 ${ (length + DATA_WRITE_CHUNK - 1) / DATA_WRITE_CHUNK } 片）")

        when (val done = finishResponse(readFrame(AppConfig.FASTBOOT_DATA_TIMEOUT_MS), AppConfig.FASTBOOT_DATA_TIMEOUT_MS)) {
            is FastbootResponse.Okay -> Unit
            is FastbootResponse.Fail -> throw FastbootException(
                "写入数据失败：${done.reason}",
                "镜像校验不通过，或分区空间不足。",
                deviceRejected = true
            )
            FastbootResponse.Timeout -> throw FastbootException(
                "写入数据超时",
                "目标机存储慢或线材质量差。"
            )
            else -> throw FastbootException("写入响应异常：${done.describe()}")
        }
    }

    override fun flash(partition: String) {
        when (val r = execute("flash:$partition", AppConfig.FASTBOOT_FLASH_TIMEOUT_MS)) {
            is FastbootResponse.Okay -> Unit
            is FastbootResponse.Fail -> throw FastbootException(
                "刷写 $partition 失败：${r.reason}",
                "常见原因：分区名不存在、bootloader 未解锁、镜像超过分区容量。",
                deviceRejected = true
            )
            else -> throw FastbootException("刷写 $partition 响应异常：${r.describe()}")
        }
    }

    fun erase(partition: String) {
        when (val r = execute("erase:$partition", AppConfig.FASTBOOT_FLASH_TIMEOUT_MS)) {
            is FastbootResponse.Okay -> Unit
            is FastbootResponse.Fail -> throw FastbootException("擦除 $partition 失败：${r.reason}", deviceRejected = true)
            else -> throw FastbootException("擦除 $partition 响应异常：${r.describe()}")
        }
    }

    fun reboot(target: String? = null) {
        val command = if (target.isNullOrBlank()) "reboot" else "reboot-$target"
        runCatching { execute(command, AppConfig.FASTBOOT_REBOOT_TIMEOUT_MS) }
        AppLogger.i("已发送重启指令：$command")
    }

    fun close() = transport.close()

    /**
     * 传输自检。
     *
     * 目的只有一个：弄清「一条连接上能不能连续发命令」。
     * 每条都记原始写返回码、读耗时、以及解析出来的响应，三个数字放在一起看就不会误判。
     */
    fun selfTest() {
        AppLogger.i("===== 传输自检开始（$channel）=====")
        val script = listOf(
            "getvar:current-slot",
            "getvar:unlocked",
            "getvar:product",
            "getvar:version"
        )
        script.forEachIndexed { index, cmd ->
            val (r, writeCode, readMs) = send(cmd, AppConfig.FASTBOOT_CMD_TIMEOUT_MS)
            val verdict = when (r) {
                FastbootResponse.Timeout -> "无响应 → 这条连接已经不可用"
                is FastbootResponse.Fail -> "设备拒绝（命令不支持也算正常）"
                else -> "通道正常"
            }
            AppLogger.i(
                "自检 ${index + 1}/${script.size} · $cmd → ${r.describe()}" +
                    "｜写码 $writeCode · 读 ${readMs}ms｜$verdict"
            )
        }
        AppLogger.i("===== 传输自检结束 =====")
    }

    companion object {
        private val KNOWN_TAGS = setOf("OKAY", "FAIL", "INFO", "DATA", "TEXT")

        /**
         * max-download-size 的可信上限。
         * 超过它的上报值视为不可信（见过 32GB 的），超限镜像必须拒绝。
         */
        const val SANE_MAX_DOWNLOAD = 64L * 1024 * 1024

        /** download 数据块的分片写入大小：保证单片在写超时内能搬完 */
        const val DATA_WRITE_CHUNK = 512 * 1024
    }
}
