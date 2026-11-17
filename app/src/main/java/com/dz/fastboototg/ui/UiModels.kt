package com.dz.fastboototg.ui

import android.hardware.usb.UsbDevice
import android.net.Uri
import com.dz.fastboototg.core.AppError
import com.dz.fastboototg.core.FastbootScript

/** 扫描列表里的一行设备 */
data class DeviceItem(
    val device: UsbDevice,
    val label: String,
    val authorized: Boolean,
    /** 设备当前模式：fastboot / ADB / 未知（由接口协议推断） */
    val mode: String = "未知"
)

/** 多镜像队列里的一项 */
data class QueueItem(
    val fileName: String,
    val uri: Uri,
    val partition: String
)

/** 待确认的刷写任务（选完镜像先预览，确认后才开刷） */
data class PendingFlash(
    val uri: Uri,
    val fileName: String,
    val sizeText: String,
    val isSparse: Boolean
)

/** OTA 包里一个可刷分区（解析结果摘要） */
data class OtaPartitionInfo(
    val name: String,
    val size: Long
)

/** 选完 OTA 包并解析成功后的待确认状态 */
data class PendingOta(
    val uri: Uri,
    val isZip: Boolean,
    val partitions: List<OtaPartitionInfo>
)

/** fastboot 主界面状态 */
data class UiState(
    val devices: List<DeviceItem> = emptyList(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceLabel: String = "",
    val diagnostics: String = "",
    val variables: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
    val partition: String = "boot",
    val busy: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "未连接",
    val terminal: List<String> = emptyList(),
    val error: AppError? = null,
    val exportedFile: String? = null,
    val detailedLog: Boolean = false,
    val maxLogMb: Int = 2,
    // ---- 体验优化新增 ----
    /** 连接过程实时事件流（连接页内嵌显示） */
    val connectionLog: List<String> = emptyList(),
    /** 上次连接过的设备（断开后给一键重连入口） */
    val lastConnected: DeviceItem? = null,
    /** 刷写速度与剩余时间（如 "12.3 MB/s · 剩余约 6 分钟"） */
    val progressInfo: String? = null,
    /** 选完镜像待确认的刷写任务 */
    val pendingFlash: PendingFlash? = null,
    // ---- 1.0.0 新增 ----
    /** 连接后的设备信息摘要（机型/BL/序列号/电量等） */
    val deviceSummary: List<Pair<String, String>> = emptyList(),
    /** 已保存的一键脚本 */
    val scripts: List<FastbootScript> = emptyList(),
    /** 解析完成的 OTA 包（选分区用） */
    val pendingOta: PendingOta? = null,
    /** 多镜像刷写队列 */
    val flashQueue: List<QueueItem> = emptyList()
)

/** ADB 页签状态。progress 为负表示总量未知（pull 场景只显示已传字节） */
data class AdbUiState(
    val devices: List<DeviceItem> = emptyList(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceLabel: String = "",
    val busy: Boolean = false,
    val stage: String = "未连接",
    val output: List<String> = emptyList(),
    val progress: Float = -1f,
    val error: AppError? = null,
    val lastTransfer: String? = null,
    // ---- 体验优化新增 ----
    /** shell 命令历史（最近在上） */
    val commandHistory: List<String> = emptyList(),
    /** 记住的默认远程路径 */
    val savedPushPath: String = "/sdcard/",
    val savedPullPath: String = "/sdcard/",
    /** 最近一次拉取成功的本地文件绝对路径（分享入口用） */
    val lastPulledPath: String? = null,
    /** 当前连接是否走网络（true = tcpip 无线模式） */
    val viaNetwork: Boolean = false,
    /** 开启无线模式后自动探测到的目标机 IP（回填输入框用） */
    val suggestedIp: String? = null,
    /** 同网段扫描中 */
    val lanScanning: Boolean = false,
    /** 同网段扫描发现的开放 5555 端口的主机 */
    val lanFound: List<String> = emptyList()
)

/** 终端快捷命令。dangerous 的命令以醒目颜色展示，执行时记警告日志 */
enum class QuickCommand(val label: String, val command: String, val dangerous: Boolean) {
    REBOOT("重启系统", "reboot", false),
    BOOTLOADER("重启到 bootloader", "reboot-bootloader", false),
    FASTBOOTD("重启到 fastbootd", "reboot-fastboot", false),
    RECOVERY("重启到恢复模式", "reboot-recovery", false),
    VARS("读取全部变量", "getvar:all", false),
    SLOT("查询当前槽位", "getvar:current-slot", false),
    UNLOCK_STATE("查询解锁状态", "getvar:unlocked", false),
    ERASE_BOOT("擦除 boot 分区", "erase:boot", true)
}
