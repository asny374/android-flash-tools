package com.dz.fastboototg.core

/**
 * 全局配置中心。
 *
 * 所有阈值、上限、版本号集中在这里，调参只改这一个文件。
 * 之前这些数字散落在协议层、ViewModel、日志器里，改一个超时要翻三个文件。
 */
object AppConfig {
    // ---------- USB 传输 ----------

    /** 写方向超时。Android 的 bulkTransfer 在 OUT 方向会等满整个 timeout 才返回，
     *  返回码又不可信，所以只给短超时、只记日志，成败由随后的读取判定。 */
    const val USB_WRITE_TIMEOUT_MS = 2_000

    /** 单次 bulk 读取字节数 */
    const val USB_READ_CHUNK = 65_536

    /** 累积缓冲容量：要放得下最多两三个满帧 */
    const val USB_ACC_CAPACITY = 512 * 1024

    /** 单帧载荷上限（防坏数据撑爆缓冲） */
    const val USB_MAX_FRAME_PAYLOAD = 256 * 1024

    // ---------- fastboot 协议 ----------

    /** 默认下载块上限（设备没上报 max-download-size 时的兜底值） */
    const val FASTBOOT_DEFAULT_MAX_DOWNLOAD = 32L * 1024 * 1024

    /** getvar:all 变量帧数上限 */
    const val FASTBOOT_MAX_VAR_FRAMES = 500

    /** 常规命令超时 */
    const val FASTBOOT_CMD_TIMEOUT_MS = 10_000

    /** 连接探测（getvar:all）超时 */
    const val FASTBOOT_PROBE_TIMEOUT_MS = 15_000

    /** 变量流中单帧间隔超时 */
    const val FASTBOOT_FRAME_TIMEOUT_MS = 3_000

    /** download 数据块确认超时 */
    const val FASTBOOT_DATA_TIMEOUT_MS = 180_000

    /** flash / erase 执行超时（大分区擦写可达数分钟） */
    const val FASTBOOT_FLASH_TIMEOUT_MS = 300_000

    /** reboot 类命令超时（设备会直接断开，等它应答是徒劳的） */
    const val FASTBOOT_REBOOT_TIMEOUT_MS = 5_000

    /** 数据块握手超时 */
    const val FASTBOOT_HANDSHAKE_TIMEOUT_MS = 10_000

    // ---------- ADB 协议 ----------

    /** 使用带校验和的 ADB v1，兼容旧版和新版 adbd */
    const val ADB_VERSION = 0x01000000

    /** 主机侧声明的单次消息载荷上限 */
    const val ADB_MAX_PAYLOAD = 4096

    /** 连接握手总超时（含可能的 RSA 授权等待） */
    const val ADB_CONNECT_TIMEOUT_MS = 15_000

    /** 通用命令 / shell 执行超时 */
    const val ADB_CMD_TIMEOUT_MS = 10_000

    /** sync 传输单块上限 */
    const val ADB_SYNC_CHUNK_MAX = 64 * 1024

    // ---------- UI / 日志 ----------

    /** 终端历史保留行数 */
    const val TERMINAL_HISTORY_MAX = 400

    /** 内存日志环形缓冲条数 */
    const val LOG_MAX_IN_MEMORY = 2_000

    /** 日志文件默认上限（MB） */
    const val LOG_DEFAULT_MAX_MB = 2

    /** 日志文件上限的可选范围（MB） */
    const val LOG_MAX_MB_LIMIT = 50

    // ---------- 设备识别 ----------

    /**
     * 已知的标准 fastboot VID:PID。
     * Google 的 bootloader：0xD00D 是通用标识，0x4EE0~0x4EE2 是 Nexus/Pixel 老序列。
     * 其他厂商的 PID 规律不够统一，宁可不说，避免误导。
     */
    fun isKnownFastbootId(vid: Int, pid: Int): Boolean = when (vid) {
        0x18D1 -> pid == 0xD00D || pid in 0x4EE0..0x4EE2
        else -> false
    }
}
