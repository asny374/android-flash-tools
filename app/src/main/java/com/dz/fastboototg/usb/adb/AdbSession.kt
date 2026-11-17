package com.dz.fastboototg.usb.adb

import android.hardware.usb.UsbDevice
import com.dz.fastboototg.core.AppConfig
import com.dz.fastboototg.core.io.RandomAccessSource
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.usb.BulkChannel
import com.dz.fastboototg.usb.UsbBulkChannel
import com.dz.fastboototg.usb.UsbDeviceScanner
import com.dz.fastboototg.usb.UsbPermissionController
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair

/**
 * ADB 会话：连接握手、命令执行、文件传输的完整实现。
 *
 * 对外接口（与 fastboot 侧对称）：
 * - [connect]：USB 连接（授权 → 打开 ADB 接口 → CNXN/AUTH 握手）
 * - [connectOverNetwork]：经典 tcpip 模式的无线连接（TCP 直连 adbd，协议栈复用）
 * - [enableTcpip]：让目标机 adbd 重启到 TCP 监听模式（相当于 adb tcpip 5555）
 * - [shell]：执行 shell 命令并收集输出
 * - [push] / [pull]：走 sync 协议传输文件
 * - [reboot]：重启目标机（可指定目标模式）
 * - [close]：释放通道
 *
 * 会话不持有任何 Android UI 依赖；进度与阶段通过回调上抛。
 */
class AdbSession private constructor(private val scanner: UsbDeviceScanner?, initialChannel: BulkChannel?) {
    constructor(scanner: UsbDeviceScanner) : this(scanner, null)
    internal constructor(channel: BulkChannel) : this(null, channel)

    private var channel: BulkChannel? = initialChannel
    private var keyPair: KeyPair? = null
    private var remoteMaxPayload: Int = AppConfig.ADB_MAX_PAYLOAD
    private var nextLocalId = 1
    private var verifyChecksum = true
    private val incoming = ByteQueue()
    private val pendingSync = ByteQueue()

    val connected: Boolean get() = channel != null

    /** 当前连接是否走网络（true = tcpip 无线模式；false = USB OTG） */
    val viaNetwork: Boolean get() = channel is TcpChannel

    // ================= 连接 =================

    /**
     * USB 连接并完成认证。
     *
     * 握手流程（USB 与无线共用，见 [connectOverChannel]）：
     * 1. 主机发 CNXN（协议版本 + 载荷上限）
     * 2. 设备回 CNXN（直接认可）或 AUTH TOKEN（要求签名）
     * 3. AUTH：先回 SIGNATURE（本机已有密钥签名）；设备不认则要求 RSAPUBLICKEY，
     *    此时目标机屏幕会弹「允许 USB 调试」，用户允许后设备回 CNXN
     */
    suspend fun connect(
        target: UsbDevice,
        permission: UsbPermissionController,
        filesDir: File,
        onEvent: (String) -> Unit = {}
    ) {
        close()
        AppLogger.i("ADB 开始连接：${requireNotNull(scanner).describe(target)}")

        if (!permission.request(target)) {
            throw AdbException(
                "USB 授权被拒绝",
                "断开重插设备，再点一次连接，系统弹窗里选允许。"
            )
        }

        // USB 只走 libusb（Java bulkTransfer 在部分主机上拿不到 ACK，已移除）
        try {
            connectOverChannel(requireNotNull(scanner).openAdb(target), filesDir, onEvent)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /**
     * 无线连接（经典 tcpip 模式）：TCP 直连目标机 adbd。
     *
     * 前提：目标机已进入 tcpip 监听模式（先用 USB 连接点「开启无线模式」，
     * 或目标机自己执行过 adb tcpip），且与本机在同一 Wi-Fi 网络。
     * RSA 认证与 USB 共用同一把密钥 —— USB 上授权过的目标机，无线直接通过。
     */
    fun connectOverNetwork(
        host: String,
        port: Int,
        filesDir: File,
        onEvent: (String) -> Unit = {}
    ) {
        close()
        AppLogger.i("ADB 无线连接：$host:$port")
        onEvent("连接 $host:$port…")

        val channel = TcpChannel.connect(host, port, TCP_CONNECT_TIMEOUT_MS)
        try {
            connectOverChannel(channel, filesDir, onEvent)
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    /** 握手核心：USB 与 TCP 共用 */
    private fun connectOverChannel(
        channel: BulkChannel,
        filesDir: File,
        onEvent: (String) -> Unit
    ) {
        this.channel = channel
        verifyChecksum = true
        nextLocalId = 1
        keyPair = AdbCrypto.loadOrCreate(filesDir)

        var deadline = now() + AppConfig.ADB_CONNECT_TIMEOUT_MS
        var signatureSent = false
        var publicKeySent = false

        send(AdbMessage.encode(AdbCommand.CNXN, AppConfig.ADB_VERSION, AppConfig.ADB_MAX_PAYLOAD,
            "host::\u0000".toByteArray(Charsets.US_ASCII)))

        while (true) {
            val message = try {
                readMessage(remaining(deadline))
            } catch (t: AdbException) {
                throw if (publicKeySent) {
                    AdbException(
                        "等待授权确认超时",
                        "请在目标机屏幕上点「允许 USB 调试」（可勾选一律允许），然后重新连接。"
                    )
                } else {
                    t
                }
            } ?: throw AdbException(
                "设备没有应答 ADB 连接请求",
                "确认目标机已开启 USB 调试（开发者选项里），并换一根能传数据的数据线。"
            )

            when (message.command) {
                AdbCommand.CNXN -> {
                    verifyChecksum = minOf(message.arg0, AppConfig.ADB_VERSION) < 0x01000001
                    remoteMaxPayload = message.arg1.coerceIn(1, AppConfig.ADB_MAX_PAYLOAD)
                    AppLogger.i("ADB 握手完成：版本=%08x 设备载荷上限=$remoteMaxPayload".format(message.arg0.toLong() and 0xFFFFFFFFL))
                    return
                }

                AdbCommand.AUTH -> {
                    when (message.arg0) {
                        AdbAuthType.TOKEN -> {
                            if (!signatureSent) {
                                signatureSent = true
                                send(AdbMessage.encode(
                                    AdbCommand.AUTH, AdbAuthType.SIGNATURE, 0,
                                    AdbCrypto.sign(keyPair!!, message.data)
                                ))
                                AppLogger.i("ADB：已发送 TOKEN 签名")
                            } else if (!publicKeySent) {
                                publicKeySent = true
                                send(AdbMessage.encode(
                                    AdbCommand.AUTH, AdbAuthType.RSAPUBLICKEY, 0,
                                    AdbCrypto.publicKeyBlock(keyPair!!)
                                ))
                                AppLogger.i("ADB：设备不认识本机密钥，已上交公钥，等待用户授权")
                                onEvent("请在目标机屏幕上允许 USB 调试")
                                // 用户去点弹窗需要时间，延长等待窗口
                                deadline = now() + AUTH_WAIT_TIMEOUT_MS
                            }
                            // 公钥已交但设备还在重新发 TOKEN：继续等它确认
                        }
                        else -> AppLogger.w("ADB：未知 AUTH 类型 ${message.arg0}，忽略")
                    }
                }

                else -> AppLogger.w("ADB：握手期间收到 ${message.command}，忽略")
            }
        }
    }

    fun close() {
        runCatching { channel?.close() }
        channel = null
        incoming.clear()
        pendingSync.clear()
    }

    @Volatile private var stopRequested = false
    private var activePreviewStream: Pair<Int, Int>? = null
    private var stopDeadline = Long.MAX_VALUE
    private fun resetStreamControl() { activePreviewStream = null; stopRequested = false; stopDeadline = Long.MAX_VALUE }
    private fun beginStream(local: Int, remote: Int) { activePreviewStream = local to remote; stopDeadline = Long.MAX_VALUE }
    private fun processStreamStop() {
        val stream = activePreviewStream
        if (stopRequested && stream != null && stopDeadline == Long.MAX_VALUE) {
            closeStream(stream.first, stream.second)
            stopDeadline = now() + 5000
        }
    }
    fun requestStreamStop() { stopRequested = true }

    fun streamShell(command: String, shouldContinue: () -> Boolean, onBytes: (ByteArray) -> Unit) {
        stopRequested = false
        if (!shouldContinue()) return
        val (local, remote) = openStream("shell:$command", now() + 10000)
        beginStream(local, remote)
        try {
            while (true) {
                if (!shouldContinue()) stopRequested = true
                processStreamStop()
                val message = readMessage(60 * 60 * 1000L) ?: break
                if (message.arg1 != local) continue
                when (message.command) {
                    AdbCommand.WRTE -> { if (!stopRequested) onBytes(message.data); ack(local, remote) }
                    AdbCommand.CLSE -> return
                }
            }
        } catch (e: Exception) { close(); throw e }
        finally { closeStream(local, remote); resetStreamControl() }
    }

    fun captureScreen(): ByteArray {
        stopRequested = false
        val deadline = now() + 60000
        val (local, remote) = openStream("exec:screencap -p 2>/dev/null", deadline)
        beginStream(local, remote)
        val out = java.io.ByteArrayOutputStream()
        try {
            while (true) {
                val message = readMessage(remaining(deadline)) ?: throw AdbException("截图超时")
                if (message.arg1 != local) continue
                when (message.command) {
                    AdbCommand.WRTE -> {
                        if (out.size() + message.data.size > 24 * 1024 * 1024) throw AdbException("截图过大")
                        out.write(message.data); ack(local, remote)
                    }
                    AdbCommand.CLSE -> return out.toByteArray()
                }
            }
        } catch (e: Exception) { close(); throw e }
        finally { closeStream(local, remote); resetStreamControl() }
    }

    // ================= shell =================

    /**
     * 执行 shell 命令并返回全部输出。
     *
     * 传统 shell 服务没有退出码，adbd 把输出流完后直接 CLSE；
     * 这里以 CLSE 作为完成标志，超时则携带已收到的部分输出报错。
     */
    fun shell(command: String, timeoutMs: Long = AppConfig.ADB_CMD_TIMEOUT_MS.toLong()): String {
        val deadline = now() + timeoutMs
        AppLogger.i("ADB shell：$command")
        val (localId, remoteId) = openStream("shell:$command", deadline)

        val output = StringBuilder()
        while (true) {
            val message = readMessage(remaining(deadline)) ?: throw AdbException(
                "shell 命令超时未完成",
                "命令可能仍在目标机上执行。长时间任务建议 nohup 到后台，或加大超时。"
            )
            when (message.command) {
                AdbCommand.WRTE -> {
                    output.append(String(message.data, Charsets.UTF_8))
                    ack(localId, remoteId)
                }
                AdbCommand.CLSE -> {
                    if (message.arg1 != localId) continue
                    closeStream(localId, remoteId)
                    val text = output.toString()
                    AppLogger.i("ADB shell 完成（${text.length} 字符输出）")
                    return text
                }
                AdbCommand.OKAY -> Unit
                else -> AppLogger.w("ADB：shell 期间收到 ${message.command}，忽略")
            }
        }
    }

    // ================= 文件传输（sync 协议） =================

    /**
     * 推送文件到目标机。
     *
     * sync 流程：SEND(路径+权限) → DATA(分块)… → DONE(时间戳) → 设备回 OKAY/FAIL。
     */
    fun push(
        source: RandomAccessSource,
        remotePath: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val deadline = now() + SYNC_TRANSFER_TIMEOUT_MS
        AppLogger.i("ADB push：${source.length} 字节 → $remotePath")
        val (localId, remoteId) = openStream("sync:", deadline)

        try {
            val pathBytes = "$remotePath,$FILE_MODE".toByteArray(Charsets.UTF_8)
            val sendFrame = ByteBuffer.allocate(8 + pathBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("SEND".toByteArray(Charsets.US_ASCII))
                .putInt(pathBytes.size)
                .put(pathBytes)
                .array()
            sendFrameData(localId, remoteId, sendFrame)

            val chunkSize = minOf(AppConfig.ADB_SYNC_CHUNK_MAX, remoteMaxPayload - 16)
                .coerceAtLeast(1024)
            val buffer = ByteArray(chunkSize)
            var offset = 0L
            val total = source.length

            while (offset < total) {
                val length = minOf(chunkSize.toLong(), total - offset).toInt()
                source.readAt(offset, buffer, 0, length)
                val frame = ByteBuffer.allocate(8 + length)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put("DATA".toByteArray(Charsets.US_ASCII))
                    .putInt(length)
                    .put(buffer, 0, length)
                    .array()
                sendFrameData(localId, remoteId, frame)
                offset += length
                onProgress(offset, total)
            }

            val doneFrame = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("DONE".toByteArray(Charsets.US_ASCII))
                .putInt((System.currentTimeMillis() / 1000L).toInt())
                .array()
            sendFrameData(localId, remoteId, doneFrame)

            awaitSyncResult(localId, remoteId, deadline, "push $remotePath")
            AppLogger.i("ADB push 完成：$remotePath")
        } finally {
            closeStream(localId, remoteId)
        }
    }

    /**
     * 从目标机拉取文件。
     *
     * sync 流程：RECV(路径) → 设备回 DATA(分块)… → DONE；出错回 FAIL(错误消息)。
     * 数据边收边落盘，不整块进内存。
     */
    fun pull(
        remotePath: String,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val deadline = now() + SYNC_TRANSFER_TIMEOUT_MS
        AppLogger.i("ADB pull：$remotePath → ${target.name}")
        val (localId, remoteId) = openStream("sync:", deadline)
        val queue = ByteQueue()

        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val recvFrame = ByteBuffer.allocate(8 + pathBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RECV".toByteArray(Charsets.US_ASCII))
                .putInt(pathBytes.size)
                .put(pathBytes)
                .array()
            sendFrameData(localId, remoteId, recvFrame)

            target.parentFile?.mkdirs()
            var done = 0L

            FileOutputStream(target).use { out ->
                while (true) {
                    when (val frame = readSyncFrame(localId, remoteId, queue, deadline)) {
                        SyncFrame.End -> {
                            AppLogger.i("ADB pull 完成：$remotePath（$done 字节）")
                            return
                        }
                        is SyncFrame.Data -> {
                            out.write(frame.data)
                            done += frame.data.size
                            onProgress(done, -1L)
                        }
                        is SyncFrame.Fail -> throw AdbException(
                            "拉取 $remotePath 失败：${frame.message}",
                            "确认路径存在且应用有读取权限（/data 下的私有文件需要 root）。"
                        )
                        else -> Unit
                    }
                }
            }
        } finally {
            closeStream(localId, remoteId)
        }
    }

    /** 重启目标机。target 为 null 时普通重启，如 "bootloader"、"recovery"。 */
    fun reboot(target: String?) {        val service = if (target.isNullOrBlank()) "reboot:" else "reboot:$target"
        AppLogger.i("ADB 重启：$service")
        val deadline = now() + AppConfig.ADB_CMD_TIMEOUT_MS.toLong()
        runCatching {
            val (localId, remoteId) = openStream(service, deadline)
            closeStream(localId, remoteId)
        }.onFailure {
            // 设备收到重启指令后可能立刻断开，失败不一定是错误
            AppLogger.i("ADB 重启连接已关闭（设备重启属预期行为）")
        }
        AppLogger.i("ADB 重启指令已发送：$service")
    }

    // ================= 无线模式 =================

    /**
     * 让目标机 adbd 重启到 TCP 监听模式（等价于 `adb tcpip:5555`）。
     *
     * 仅在 USB 连接下有效；执行后当前连接会断开（adbd 重启），
     * 随后用 [connectOverNetwork] 连回同一个端口即可无线上用。
     */
    fun enableTcpip(port: Int = 5555) {
        val deadline = now() + AppConfig.ADB_CMD_TIMEOUT_MS.toLong()
        val (localId, remoteId) = openStream("tcpip:$port", deadline)
        val response = StringBuilder()
        try {
            while (true) {
                val message = readMessage(remaining(deadline)) ?: throw AdbException(
                    "开启无线模式超时", "请重新连接设备后重试。"
                )
                if (message.arg1 != localId || message.arg0 != remoteId) continue
                when (message.command) {
                    AdbCommand.WRTE -> {
                        response.append(String(message.data, Charsets.UTF_8))
                        ack(localId, remoteId)
                        if (response.contains("restarting in TCP mode")) {
                            AppLogger.i("ADB：$response")
                            return
                        }
                    }
                    AdbCommand.CLSE -> {
                        if (!response.contains("restarting in TCP mode")) throw AdbException(
                            "设备未确认开启无线模式", response.toString().ifBlank { "目标机可能不支持 tcpip 服务。" }
                        )
                        AppLogger.i("ADB：$response")
                        return
                    }
                }
            }
        } finally {
            closeStream(localId, remoteId)
        }
    }

    /** 查询目标机 wlan0 的 IPv4 地址，给无线连接地址回填用；失败返回 null */
    fun deviceWifiIp(): String? {
        return runCatching {
            val text = shell("ip -f inet addr show wlan0", timeoutMs = 5_000)
            Regex("inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(text)?.groupValues?.get(1)
        }.getOrNull()
    }

    // ================= 流管理 =================

    /** 打开一个流（OPEN → OKAY）。返回 (localId, remoteId) */
    private fun openStream(service: String, deadline: Long): Pair<Int, Int> {
        pendingSync.clear()
        val localId = nextLocalId++
        send(AdbMessage.encode(AdbCommand.OPEN, localId, 0, (service + "\u0000").toByteArray(Charsets.UTF_8)))

        while (true) {
            val message = readMessage(remaining(deadline)) ?: throw AdbException(
                "打开服务「$service」超时",
                "设备可能已断开，重新插拔数据线再试。"
            )
            when (message.command) {
                AdbCommand.OKAY -> {
                    if (message.arg1 != localId) continue
                    return localId to message.arg0
                }
                AdbCommand.CLSE -> {
                    if (message.arg1 != localId) continue
                    throw AdbException(
                        "设备拒绝了服务「$service」",
                        "adbd 可能不支持该服务，或设备状态异常。尝试重新连接。"
                    )
                }
                AdbCommand.WRTE -> {
                    // OKAY 之前收到数据属于异常但无害，照样 ack 以免 adbd 卡住
                    ack(message.arg1, message.arg0)
                }
                else -> AppLogger.w("ADB：等待 OKAY 时收到 ${message.command}，忽略")
            }
        }
    }

    private fun closeStream(localId: Int, remoteId: Int) {
        runCatching {
            send(AdbMessage.encode(AdbCommand.CLSE, localId, remoteId))
        }
    }

    private fun ack(localId: Int, remoteId: Int) {
        send(AdbMessage.encode(AdbCommand.OKAY, localId, remoteId))
    }

    // ================= 底层收发 =================

    private fun send(message: AdbMessage) {
        val bytes = AdbMessage.serialize(message)
        val current = channel ?: throw AdbException("ADB 连接已关闭", "重新连接设备。")
        fun writeAll(offset: Int, length: Int) {
            var sent = 0
            while (sent < length) {
                val n = current.write(bytes, offset + sent, length - sent)
                if (n <= 0 || n > length - sent) throw AdbException(
                    "ADB 写入失败（$n）", "连接已中断，请重新连接设备。"
                )
                sent += n
            }
        }
        // USB adbd expects a header transfer followed by a payload transfer.
        writeAll(0, AdbMessage.HEADER_SIZE)
        if (message.data.isNotEmpty()) {
            writeAll(AdbMessage.HEADER_SIZE, message.data.size)
            (current as? UsbBulkChannel)?.finishAdbPayload(message.data.size)
        }
        AppLogger.d("ADB → ${message.describe()}")
    }

    /** 读一条完整消息；超时返回 null，校验失败抛 AdbException */
    private fun readMessage(timeoutMs: Long): AdbMessage? {
        val deadline = now() + timeoutMs
        val header = readFully(AdbMessage.HEADER_SIZE, deadline)

        // 先取载荷长度，再补读载荷，最后整体解析（复用 parse 的校验逻辑）
        val dataLength = ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (dataLength < 0 || dataLength > AdbMessage.MAX_PAYLOAD_HARD_LIMIT) {
            throw AdbException(
                "ADB 消息载荷异常（$dataLength 字节）",
                "可能不是 ADB 设备，或流已错位，请重插数据线。"
            )
        }
        if (dataLength > 0) {
            val payload = readFully(dataLength, deadline)
            val combined = ByteArray(AdbMessage.HEADER_SIZE + dataLength)
            System.arraycopy(header, 0, combined, 0, AdbMessage.HEADER_SIZE)
            System.arraycopy(payload, 0, combined, AdbMessage.HEADER_SIZE, dataLength)
            val parsed = AdbMessage.parse(combined, combined.size, verifyChecksum)
                ?: throw AdbException("ADB 消息解析失败", "流已错位，请重插数据线。")
            AppLogger.d("ADB ← ${parsed.first.describe()}")
            return parsed.first
        }

        val parsed = AdbMessage.parse(header, AdbMessage.HEADER_SIZE, verifyChecksum)
            ?: throw AdbException("ADB 消息解析失败", "流已错位，请重插数据线。")
        AppLogger.d("ADB ← ${parsed.first.describe()}")
        return parsed.first
    }

    /** Buffer complete USB packets so reading a 24-byte header cannot truncate a packet. */
    private fun readFully(count: Int, deadline: Long): ByteArray {
        val packet = ByteArray(16 * 1024)
        while (incoming.size < count) {
            processStreamStop()
            val remainingMs = minOf(deadline, stopDeadline) - now()
            if (remainingMs <= 0) throw AdbException("ADB 读取超时", "请确认连接并在目标机允许调试。")
            val current = channel ?: throw AdbException("ADB 连接已关闭", "重新连接设备。")
            val want = AdbReadSizing.requestSize(count - incoming.size)
            val n = current.read(packet, 0, want, remainingMs.coerceAtMost(500L).toInt())
            when {
                n > 0 -> incoming.append(packet.copyOf(n))
                n == -7 || n == 0 -> Thread.sleep(5)
                else -> throw AdbException("ADB 读取失败（$n）", "设备已断开或 USB 传输失败，请重新连接。")
            }
        }
        return incoming.take(count)
    }

    // ================= sync 帧解析 =================

    /** 把一个 sync 命令+载荷包进 WRTE 发出去 */
    private fun sendFrameData(localId: Int, remoteId: Int, frame: ByteArray) {
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + remoteMaxPayload, frame.size)
            sendFrameChunk(localId, remoteId, frame.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun sendFrameChunk(localId: Int, remoteId: Int, frame: ByteArray) {
        send(AdbMessage.encode(AdbCommand.WRTE, localId, remoteId, frame))
        // adbd 会为每条 WRTE 回 OKAY，收掉它避免对方窗口堵死
        val deadline = now() + ACK_TIMEOUT_MS
        while (true) {
            val message = readMessage(remaining(deadline)) ?: throw AdbException(
                "等待设备确认数据块超时",
                "设备可能已断开，重新插拔数据线再试。"
            )
            when (message.command) {
                AdbCommand.OKAY -> if (message.arg1 == localId && message.arg0 == remoteId) return
                AdbCommand.WRTE -> {
                    if (message.arg1 == localId && message.arg0 == remoteId) {
                        pendingSync.append(message.data)
                        ack(localId, remoteId)
                    }
                }
                AdbCommand.CLSE -> throw AdbException("设备关闭了传输流", "sync 通道被中断，请重试。")
                else -> Unit
            }
        }
    }

    /** push 的最终结果：等设备回 OKAY/FAIL sync 帧 */
    private fun awaitSyncResult(localId: Int, remoteId: Int, deadline: Long, what: String) {
        val queue = ByteQueue()
        while (true) {
            when (val frame = readSyncFrame(localId, remoteId, queue, deadline)) {
                SyncFrame.Okay -> return
                is SyncFrame.Fail -> throw AdbException(
                    "$what 失败：${frame.message}",
                    "确认目标路径存在、可写（一般写 /sdcard/），且设备存储空间充足。"
                )
                else -> Unit
            }
        }
    }

    private sealed interface SyncFrame {
        data class Data(val data: ByteArray) : SyncFrame
        /** "DONE"：pull 数据流结束 */
        object End : SyncFrame
        /** "OKAY"：push 最终成功 */
        object Okay : SyncFrame
        data class Fail(val message: String) : SyncFrame
    }

    /** 从 WRTE 流中读出一个 sync 帧（跨消息缓冲已由调用方 ByteQueue 处理） */
    private fun readSyncFrame(localId: Int, remoteId: Int, queue: ByteQueue, deadline: Long): SyncFrame {
        if (pendingSync.size > 0) queue.append(pendingSync.take(pendingSync.size))
        ensureSyncBytes(localId, remoteId, queue, 4, deadline)
        val command = String(queue.take(4), Charsets.US_ASCII)
        return when (command) {
            "DATA" -> {
                ensureSyncBytes(localId, remoteId, queue, 4, deadline)
                val length = ByteBuffer.wrap(queue.take(4)).order(ByteOrder.LITTLE_ENDIAN).int
                if (length < 0) throw AdbException("sync DATA 长度异常（$length）", "流已错位，请重试。")
                if (length == 0) return SyncFrame.Data(ByteArray(0))
                ensureSyncBytes(localId, remoteId, queue, length, deadline)
                SyncFrame.Data(queue.take(length))
            }
            "DONE" -> SyncFrame.End
            "OKAY" -> SyncFrame.Okay
            "FAIL" -> {
                ensureSyncBytes(localId, remoteId, queue, 4, deadline)
                val length = ByteBuffer.wrap(queue.take(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val capped = length.coerceIn(0, 4096)
                ensureSyncBytes(localId, remoteId, queue, capped, deadline)
                SyncFrame.Fail(String(queue.take(capped), Charsets.UTF_8))
            }
            else -> throw AdbException(
                "sync 帧异常（$command）",
                "设备端响应不符合协议，请重试。"
            )
        }
    }

    /** 确保队列里有至少 n 字节，不足时继续从 WRTE 里补 */
    private fun ensureSyncBytes(localId: Int, remoteId: Int, queue: ByteQueue, need: Int, deadline: Long) {
        while (queue.size < need) {
            val message = readMessage(remaining(deadline)) ?: throw AdbException(
                "等待 sync 数据超时",
                "大文件传输慢或连接不稳。确认数据线质量后重试。"
            )
            when (message.command) {
                AdbCommand.WRTE -> {
                    queue.append(message.data)
                    ack(localId, remoteId)
                }
                AdbCommand.CLSE -> throw AdbException("设备提前关闭了 sync 流", "传输被中断，请重试。")
                else -> Unit
            }
        }
    }

    // ================= 小工具 =================

    private class ByteQueue {
        private var buf = ByteArray(8192)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun clear() { head = 0; tail = 0 }

        fun append(bytes: ByteArray) {
            if (tail + bytes.size > buf.size) {
                // 压缩已消费空间，仍不够则扩容
                if (size > 0) System.arraycopy(buf, head, buf, 0, size)
                tail -= head
                head = 0
                if (tail + bytes.size > buf.size) {
                    buf = buf.copyOf(maxOf(buf.size * 2, tail + bytes.size))
                }
            }
            System.arraycopy(bytes, 0, buf, tail, bytes.size)
            tail += bytes.size
        }

        fun take(n: Int): ByteArray {
            require(n <= size) { "ByteQueue 欠 $n 字节（现有 $size）" }
            val out = buf.copyOfRange(head, head + n)
            head += n
            if (head == tail) { head = 0; tail = 0 }
            return out
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private fun remaining(deadline: Long): Long = (deadline - now()).coerceAtLeast(1L)

    companion object {
        /** 用户在目标机上点「允许 USB 调试」的等待窗口 */
        const val AUTH_WAIT_TIMEOUT_MS = 60_000L

        /** 无线连接的 TCP 建连超时 */
        const val TCP_CONNECT_TIMEOUT_MS = 10_000

        /** push/pull 大文件的总体超时 */
        const val SYNC_TRANSFER_TIMEOUT_MS = 10 * 60_000L

        /** 等单条 WRTE 的 OKAY 回执 */
        const val ACK_TIMEOUT_MS = 15_000L

        /** 推送文件的默认权限 0644 */
        const val FILE_MODE = 0x81A4
    }
}
