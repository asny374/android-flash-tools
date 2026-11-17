package com.dz.fastboototg.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.dz.fastboototg.core.AppConfig
import com.dz.fastboototg.core.log.AppLogger

/**
 * USB 设备扫描与打开。
 *
 * 职责边界：只做「枚举设备、识别端点、拿到通道」这一层；
 * 协议行为（fastboot / ADB）由上层模块基于 [BulkChannel] 自行实现。
 */
class UsbDeviceScanner(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun isHostSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun attachedDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun fastbootCandidates(): List<UsbDevice> = attachedDevices().filter { looksLikeFastboot(it) }

    /** 标准 ADB 接口：class=0xFF(VENDOR_SPEC) subclass=0x42 protocol=0x01 */
    fun adbCandidates(): List<UsbDevice> = attachedDevices().filter { looksLikeAdb(it) }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** 依据接口协议推断设备当前模式：ADB（0x42/0x01）优先于 fastboot（其余 vendor bulk） */
    fun modeOf(device: UsbDevice): String = when {
        findAdbInterface(device) != null -> "ADB"
        findBulkInterface(device) != null -> "fastboot"
        else -> "未知"
    }

    fun looksLikeFastboot(device: UsbDevice): Boolean = findBulkInterface(device) != null

    fun looksLikeAdb(device: UsbDevice): Boolean = findAdbInterface(device) != null

    private fun findBulkInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
            val eps = pickBulkEndpoints(itf) ?: continue
            return Triple(itf, eps.first, eps.second)
        }
        return null
    }

    private fun findAdbInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            val isAdb = itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                itf.interfaceSubclass == ADB_SUBCLASS && itf.interfaceProtocol == ADB_PROTOCOL
            if (!isAdb) continue
            val eps = pickBulkEndpoints(itf) ?: continue
            return Triple(itf, eps.first, eps.second)
        }
        return null
    }

    private fun pickBulkEndpoints(itf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        return if (inEp != null && outEp != null) inEp to outEp else null
    }

    fun describe(device: UsbDevice): String {
        val vendor = device.manufacturerName?.takeIf { it.isNotBlank() } ?: "未知厂商"
        val product = device.productName?.takeIf { it.isNotBlank() } ?: "未知设备"
        return "%s %s (%04x:%04x)".format(
            vendor, product, device.vendorId and 0xFFFF, device.productId and 0xFFFF
        )
    }

    fun diagnostics(device: UsbDevice): String {
        val sb = StringBuilder()
        sb.appendLine("设备：${describe(device)}")
        sb.appendLine("VID:PID = %04x:%04x".format(device.vendorId and 0xFFFF, device.productId and 0xFFFF))
        sb.appendLine("接口数：${device.interfaceCount}")
        sb.appendLine("已授权：${if (usbManager.hasPermission(device)) "是" else "否"}")
        sb.appendLine("native 库：${if (NativeUsb.isAvailable) "已加载" else "未加载（${NativeUsb.error}）"}")
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            sb.appendLine(
                "  接口[$i] id=${itf.id} class=0x%02X sub=0x%02X proto=0x%02X 端点=${itf.endpointCount}".format(
                    itf.interfaceClass, itf.interfaceSubclass, itf.interfaceProtocol
                )
            )
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                sb.appendLine(
                    "      0x%02X %s 包长=%d".format(
                        ep.address,
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "bulk" else "type${ep.type}",
                        ep.maxPacketSize
                    )
                )
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 打开设备的 fastboot 接口（libusb 通道）。
     * Java bulkTransfer 路径已移除 —— 实测部分主机上它 bulk OUT 拿不到 ACK，
     * libusb 是唯一被证实可靠的路径，不再保留会随机失败的那条。
     */
    fun open(device: UsbDevice): UsbBulkChannel =
        openChannel(device, findBulkInterface(device), "fastboot")

    /** 按设备节点名（如 /dev/bus/usb/001/002）在当前设备列表里找重枚举后的新引用 */
    fun findByName(deviceName: String): UsbDevice? =
        attachedDevices().firstOrNull { it.deviceName == deviceName }

    /**
     * 打开设备的 ADB 接口（class=0xFF subclass=0x42 protocol=0x01）。
     * 与 [open] 分开是为了避免多接口设备上选错端点。
     */
    fun openAdb(device: UsbDevice): UsbBulkChannel =
        openChannel(device, findAdbInterface(device), "ADB")

    private fun openChannel(
        device: UsbDevice,
        found: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>?,
        purpose: String
    ): UsbBulkChannel {
        AppLogger.i("打开设备（$purpose·libusb）：${describe(device)}")
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: throw FastbootException(
                "无法打开 USB 设备",
                "授权可能被系统回收了。断开重插，重新点连接。"
            )

        val (itf, epIn, epOut) = found ?: run {
            connection.close()
            throw FastbootException(
                "设备没有可用的批量端点",
                "这个接口不是 $purpose 用的，确认目标机处于对应模式。"
            )
        }
        AppLogger.i(
            "接口 id=${itf.id} 端点 IN=0x%02X(%d) OUT=0x%02X(%d)".format(
                epIn.address, epIn.maxPacketSize, epOut.address, epOut.maxPacketSize
            )
        )

        if (!NativeUsb.isAvailable) {
            connection.close()
            throw FastbootException(
                "libusb native 库未加载",
                "USB 通信只支持 libusb 通道（Java 路径在部分主机上不可靠，已移除）。重新安装应用或反馈设备型号。"
            )
        }
        // dup 出的 fd 交给 libusb 独立使用，claim 由 libusb 自己完成，
        // 不再调用 Java 的 claimInterface（两条栈各占各的 fd，互不干扰）。
        val handle = NativeUsb.nativeOpen(connection.fileDescriptor)
        if (handle == 0L) {
            connection.close()
            throw FastbootException(
                "libusb 打开设备失败",
                "native 初始化异常，logcat 里 libusb 标签有具体原因。"
            )
        }
        val claimed = runCatching { NativeUsb.nativeClaim(handle, itf.id) }.getOrDefault(-1)
        AppLogger.i("libusb_claim_interface(${itf.id}) → $claimed")
        if (claimed != 0) {
            runCatching { NativeUsb.nativeClose(handle) }
            connection.close()
            throw FastbootException(
                "libusb 占用接口失败（错误码 $claimed）",
                "接口可能被其他程序占用，先关掉它们再试。"
            )
        }
        AppLogger.i("libusb 通道就绪（接口 ${itf.id}）")
        return UsbBulkChannel(connection, epIn, epOut, handle, itf.id)
    }

    fun environmentReport(): Map<String, String> = linkedMapOf(
        "应用版本" to com.dz.fastboototg.BuildConfig.VERSION_NAME,
        "Android 版本" to "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "设备型号" to "${Build.MODEL}",
        "支持 USB Host" to if (isHostSupported()) "是" else "否（不能作为刷机主机）",
        "native 通道" to if (NativeUsb.isAvailable) "libusb 已加载" else "未加载（USB 通信不可用）",
        "当前 USB 设备数" to attachedDevices().size.toString(),
        "疑似 fastboot 设备数" to fastbootCandidates().size.toString(),
        "疑似 ADB 设备数" to adbCandidates().size.toString(),
        "系统架构" to Build.SUPPORTED_ABIS.joinToString(", ")
    )

    companion object {
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }
}
