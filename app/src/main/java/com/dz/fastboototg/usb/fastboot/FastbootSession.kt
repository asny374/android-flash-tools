package com.dz.fastboototg.usb.fastboot

import android.hardware.usb.UsbDevice
import com.dz.fastboototg.core.AppConfig
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.usb.FastbootException
import com.dz.fastboototg.usb.FastbootResponse
import com.dz.fastboototg.usb.NativeUsb
import com.dz.fastboototg.usb.UsbDeviceScanner
import com.dz.fastboototg.usb.UsbPermissionController
import com.dz.fastboototg.usb.describe

/**
 * fastboot 会话：设备连接的持有者与生命周期管理者。
 *
 * 之前这套逻辑长在 ViewModel 里，导致 UI 状态和 USB 会话纠缠不清：
 * 重试要重开 USB、断开要清状态、清屏要防泄漏，全混在一起。
 * 现在会话独立成类，ViewModel 只负责把会话的结果映射到界面。
 */
class FastbootSession(private val scanner: UsbDeviceScanner) {

    private var protocol: FastbootProtocol? = null
    private var device: UsbDevice? = null

    /** 上次连接成功所用通道。true = libusb；重连（含重试重开）直接走成功过的通道 */

    val connected: Boolean get() = protocol != null
    val channel: String? get() = protocol?.channel

    /** 已连接时返回协议实例；未连接抛业务异常（提示先连接） */
    fun requireProtocol(): FastbootProtocol = protocol ?: throw notConnected()

    /**
     * 连接并探测目标设备。
     *
     * 流程：授权 → 打开 libusb 通道 → getvar:all 探测 + 传输自检，返回变量表。
     *
     * 通道策略（1.2 起）：USB 只走 libusb —— Java bulkTransfer 在部分主机上
     * bulk OUT 拿不到 ACK（数据半路丢失），已整体移除。
     * 连接失败时对设备做一次 USB 级重置，重新枚举后再试一轮。
     *
     * @param onEvent 连接过程事件（USB 重置等），给界面实时上屏用
     */
    suspend fun open(
        target: UsbDevice,
        permission: UsbPermissionController,
        onEvent: (String) -> Unit = {}
    ): Map<String, String> {
        close()
        AppLogger.i("开始连接：${scanner.describe(target)}")

        if (!permission.request(target)) {
            throw FastbootException(
                "USB 授权被拒绝",
                "断开重插设备，再点一次连接，系统弹窗里选允许。"
            )
        }

        val failures = mutableListOf<Pair<String, FastbootException>>()
        var usbResetAttempted = false
        var currentTarget = target

        // 外层两轮：第一轮失败后做一次 USB 设备级重置
        // （救活「枚举成功但协议引擎卡死」的设备端），设备重新枚举后自动再试一轮
        for (round in 0..1) {
            if (round == 1) {
                if (usbResetAttempted || !NativeUsb.isAvailable) break
                usbResetAttempted = true
                onEvent("无应答，执行 USB 级重置…")
                if (!attemptUsbReset(currentTarget)) break
                onEvent("设备重新枚举完成，自动重试连接…")
                // reset 后设备重新枚举，取新的 UsbDevice 引用（旧对象已失效）
                currentTarget = scanner.findByName(currentTarget.deviceName) ?: target
            }

            var opened: FastbootProtocol? = null
            try {
                opened = FastbootProtocol(scanner.open(currentTarget))
                protocol = opened
                device = currentTarget

                val vars = opened.probe()
                // 连接成功后立刻跑一遍传输自检：一条连接能不能连续发命令，
                // 只有实测数字能说清（写返回码 / 读耗时 / 是否无响应）
                opened.selfTest()
                if (round > 0) {
                    AppLogger.i("连接成功：${scanner.describe(currentTarget)}（USB 重置后）")
                    onEvent("连接成功（USB 重置后）")
                } else {
                    AppLogger.i("连接成功：${scanner.describe(currentTarget)}（${opened.channel} 通道）")
                }
                return vars
            } catch (t: FastbootException) {
                runCatching { opened?.close() }
                protocol = null
                device = null
                failures += "libusb" to t
            }
        }
        throw wrapFailures(failures, currentTarget)
    }

    /**
     * 对设备做 USB 级重置（libusb_reset_device）。
     *
     * 针对「枚举成功但 bulk 端点无任何数据」的目标机 —— 控制传输活着说明 EP0 通，
     * 协议引擎卡死时只有强制设备重新枚举才可能救回来。
     * reset 后设备会重新枚举，原句柄立即失效，调用方必须重开通道。
     *
     * @return true 表示重置成功
     */
    private fun attemptUsbReset(target: UsbDevice): Boolean {
        AppLogger.i("连接无应答，尝试对设备执行 USB 级重置（强制重新枚举）")
        return runCatching {
            val channel = scanner.open(target)
            val code = runCatching { NativeUsb.nativeReset(channel.nativeHandle) }.getOrDefault(-1)
            channel.close()
            AppLogger.i("USB 重置完成（返回码 $code），设备将重新枚举")
            code == 0
        }.getOrElse {
            AppLogger.w("USB 重置失败：${it.message}")
            false
        }
    }

    /** 把各通道的失败信息合并成一条面向用户的错误 */
    private fun wrapFailures(
        failures: List<Pair<String, FastbootException>>,
        target: UsbDevice
    ): FastbootException {
        val vid = target.vendorId and 0xFFFF
        val pid = target.productId and 0xFFFF
        val vidHint = if (AppConfig.isKnownFastbootId(vid, pid)) {
            " VID:PID = %04x:%04x 说明目标机是标准 fastboot 模式，".format(vid, pid) +
                "问题在主机侧的传输。优先换一根 USB-A 转 C 的短线，再换 OTG 转接头。"
        } else {
            ""
        }
        return if (failures.size == 1) {
            val (label, t) = failures[0]
            FastbootException("${t.message}（$label 通道）", t.hint + vidHint)
        } else {
            FastbootException(
                "各通道均无法通信：" + failures.joinToString("；") { "${it.first}：${it.second.message}" },
                "已自动尝试 Java / libusb 两条通道并做过 USB 重置，主机侧软件栈确认健康。" +
                    "按顺序排查：" +
                    "① 目标机屏幕此刻是否显示 FASTBOOT 字样；" +
                    "② 拔掉目标机数据线等 3 秒重插（重新枚举）；" +
                    "③ 目标机长按电源强制重启，重新按音量下+电源进 fastboot；" +
                    "④ 有电脑的话把目标机插电脑跑 fastboot devices 做对照；" +
                    "⑤ 换一根确定能传数据的短线。$vidHint"
            )
        }
    }

    fun close() {
        runCatching { protocol?.close() }
        protocol = null
        device = null
    }

    /**
     * 执行命令，失败时重开一次 USB 会话再试。
     *
     * bootloader 上实测过「一条连接只肯好好服务一条命令」的情况：
     * 第二次写会瞬时返回 -1、读也瞬时 -1，但关掉连接重新 claim 之后又能正常。
     * 重连比改协议参数便宜得多，所以先重试一次再说。
     */
    fun executeWithRetry(
        command: String,
        timeoutMs: Int = AppConfig.FASTBOOT_CMD_TIMEOUT_MS
    ): FastbootResponse {
        val current = protocol ?: throw notConnected()
        return try {
            current.execute(command, timeoutMs)
        } catch (t: FastbootException) {
            // 设备明确拒绝（FAIL）说明链路是好的，重试只会得到同样的拒绝 —— 直接上抛。
            // 只有传输层失败（超时 / 通道异常）才值得重开 USB 会话再试一次。
            if (t.deviceRejected || !command.startsWith("getvar:")) throw t
            val dev = device ?: throw t
            AppLogger.w("命令「$command」失败（${t.message}），重开 USB 会话后重试")
            runCatching { current.close() }
            // 重开时沿用上次连接成功的通道类型
            val fresh = runCatching {
                FastbootProtocol(scanner.open(dev))
            }.getOrElse { throw t }
            protocol = fresh
            fresh.execute(command, timeoutMs).also {
                AppLogger.i("重连后命令「$command」成功：${it.describe()}")
            }
        }
    }

    private fun notConnected() = FastbootException(
        "还没有连接设备",
        "先扫描并连接目标机。"
    )
}
