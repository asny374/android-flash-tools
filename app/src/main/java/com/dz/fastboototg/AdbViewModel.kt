package com.dz.fastboototg

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dz.fastboototg.core.AppError
import com.dz.fastboototg.core.AppException
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.Prefs
import com.dz.fastboototg.core.io.UriSource
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.service.KeepAliveService
import com.dz.fastboototg.ui.AdbUiState
import com.dz.fastboototg.ui.DeviceItem
import com.dz.fastboototg.usb.UsbDeviceScanner
import com.dz.fastboototg.usb.UsbPermissionController
import com.dz.fastboototg.usb.adb.AdbSession
import com.dz.fastboototg.usb.adb.LanScanner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ADB 页签的 ViewModel。
 *
 * 职责：把 AdbSession 的结果映射到界面状态，持有设备列表与连接生命周期。
 * 与 FlashViewModel 完全独立 —— fastboot 和 ADB 的目标机模式互斥，
 * 两边会话互不干扰，一边失败不影响另一边。
 */
class AdbViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = UsbDeviceScanner(application)
    private val permission = UsbPermissionController(application)
    private val session = AdbSession(scanner)
    private val prefs = Prefs(application)

    private val _state = MutableStateFlow(
        AdbUiState(
            commandHistory = prefs.adbCommandHistory(),
            savedPushPath = prefs.adbPushPath,
            savedPullPath = prefs.adbPullPath
        )
    )
    val state: StateFlow<AdbUiState> = _state.asStateFlow()

    init {
        refreshDevices()
    }

    val liveLog = MutableStateFlow("")
    val logRunning = MutableStateFlow(false)
    val mirroring = MutableStateFlow(false)
    private val toolPrefs = application.getSharedPreferences("adb-tools", 0)
    val mirrorFps = MutableStateFlow(toolPrefs.getInt("fps", 1).takeIf { it in listOf(1,2,5,10,15,30) } ?: 1)
    val actualMirrorFps = MutableStateFlow(0f)
    fun setMirrorFps(fps: Int) { if (fps in listOf(1, 2, 5, 10, 15, 30)) { mirrorFps.value = fps; toolPrefs.edit().putInt("fps", fps).apply() } }
    val screen = MutableStateFlow<android.graphics.Bitmap?>(null)
    private var logFile: File? = null
    private val logLock = Any()
    private var mirrorJob: kotlinx.coroutines.Job? = null

    fun setNtp(host: String) {
        val clean = host.trim()
        if (!com.dz.fastboototg.usb.adb.NtpHost.isValid(clean)) {
            setState(error = AppError("NTP 地址无效", "请输入域名或 IPv4 地址，不要输入网址、空格或命令。")); return
        }
        runShell("settings put global ntp_server '$clean' && settings get global ntp_server")
    }
    fun readNtp() = runShell("settings get global ntp_server; settings get global auto_time")
    fun resetNtp() = runShell("settings delete global ntp_server && settings get global ntp_server")

    fun startLogs() {
        if (!ensureConnected()) return
        setState(busy = true, stage = "正在采集目标机 logcat")
        logRunning.value = true; liveLog.value = ""
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir, "adb-logs").apply { mkdirs() }
                    val file = File(dir, "logcat.txt")
                    synchronized(logLock) { file.writeBytes(byteArrayOf()); logFile = file }
                    var previewBytes = byteArrayOf()
                    session.streamShell("logcat -v threadtime -T 1", { logRunning.value }) { bytes ->
                        synchronized(logLock) {
                            if (file.length() + bytes.size > 32 * 1024 * 1024) throw AppException("日志达到 32 MB 上限，请保存后重新采集")
                            file.appendBytes(bytes)
                        }
                        previewBytes = (previewBytes + bytes).takeLast(64000).toByteArray()
                        liveLog.value = String(previewBytes, Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) {
                if (logRunning.value) reportError(e, "日志采集结束")
            } finally {
                logRunning.value = false
                setState(busy = false, connected = session.connected, stage = "采集已结束，可保存日志")
            }
        }
    }
    fun stopLogs() { logRunning.value = false; session.requestStreamStop() }
    fun saveLogs(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        synchronized(logLock) {
                            val file = logFile ?: throw AppException("还没有采集日志")
                            file.inputStream().use { it.copyTo(out) }
                        }
                    } ?: throw AppException("无法打开保存位置")
                }
                appendOutput("日志已保存到所选位置")
            } catch (e: Exception) { reportError(e, "保存日志失败") }
        }
    }
    fun startMirror() {
        if (!ensureConnected()) return
        setState(busy = true, stage = "目标机画面预览")
        mirroring.value = true; screen.value = null
        mirrorJob = viewModelScope.launch {
            try {
                while (mirroring.value) {
                    val frameStart = android.os.SystemClock.elapsedRealtime()
                    val bitmap = withContext(Dispatchers.IO) {
                        val bytes = session.captureScreen()
                        if (!mirroring.value) return@withContext null
                        val png = com.dz.fastboototg.usb.adb.ScreenPng.extract(bytes)
                        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                            ?: throw AppException("无法解码目标画面", "目标系统可能不支持截图或当前内容受保护。")
                    }
                    if (!mirroring.value || bitmap == null) break
                    screen.value = bitmap
                    val interval = 1000L / mirrorFps.value
                    val elapsed = android.os.SystemClock.elapsedRealtime() - frameStart
                    actualMirrorFps.value = 1000f / maxOf(interval, elapsed, 1L)
                    kotlinx.coroutines.delay((interval - elapsed).coerceAtLeast(1L))
                }
            } catch (e: Exception) {
                setState(connected = session.connected)
                if (mirroring.value) reportError(e, "画面预览结束")
            } finally { mirroring.value = false; setState(busy = false) }
        }
    }
    fun stopMirror() { mirroring.value = false; session.requestStreamStop() }

    // ---------- 设备 ----------

    fun refreshDevices() {
        val items = scanner.adbCandidates().map {
            DeviceItem(it, scanner.describe(it), permission.hasPermission(it))
        }
        _state.value = _state.value.copy(devices = items)
        if (items.isNotEmpty()) {
            AppLogger.i("发现 ${items.size} 个 ADB 设备")
        }
    }

    fun connect(item: DeviceItem) {
        if (_state.value.busy || _state.value.connecting) return
        viewModelScope.launch {
            setState(connecting = true, stage = L10n.t("连接中", "Connecting"), error = null)
            try {
                withContext(Dispatchers.IO) {
                    session.connect(
                        item.device,
                        permission,
                        getApplication<Application>().filesDir,
                        onEvent = { event -> setState(stage = event) }
                    )
                }
                setState(
                    connected = true,
                    connecting = false,
                    deviceLabel = item.label,
                    stage = L10n.t("已连接", "Connected") + " · ${item.label}",
                    viaNetwork = false
                )
                AppLogger.i("ADB connected: ${item.label}")
            } catch (t: Throwable) {
                session.close()
                reportError(t, L10n.t("连接失败", "Connection failed"))
                setState(connecting = false, connected = false, viaNetwork = false, deviceLabel = "")
            }
        }
    }

    fun disconnect() {
        if (_state.value.busy || _state.value.connecting) return
        session.close()
        setState(
            connected = false,
            deviceLabel = "",
            stage = L10n.t("未连接", "Not connected"),
            progress = -1f,
            viaNetwork = false
        )
        AppLogger.i("ADB disconnected")
    }

    // ---------- 无线 ADB ----------

    /** 同网段扫描：找出开放 tcpip 端口的设备，点结果回填 IP */
    fun scanLan(port: Int = 5555) {
        if (_state.value.lanScanning || _state.value.busy || _state.value.connecting) return
        if (port !in 1..65535) {
            setState(error = AppError("端口无效", "端口必须在 1–65535 之间。"))
            return
        }
        viewModelScope.launch {
            setState(lanScanning = true, stage = "扫描局域网（端口 $port）…", error = null, lanFound = emptyList())
            try {
                val found = LanScanner.scanTcpip(getApplication<Application>(), port)
                setState(
                    lanFound = found,
                    stage = if (found.isEmpty()) {
                        "扫描完成：未发现开放端口 $port 的设备；请确认目标机已开启 adb tcpip。"
                    } else {
                        "扫描完成：发现 ${found.size} 台设备，点击回填 IP。"
                    }
                )
                AppLogger.i("LAN 扫描完成：${found.size} 台设备，端口 $port")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                reportError(t, L10n.t("扫描失败", "Scan failed"))
            } finally {
                setState(lanScanning = false)
            }
        }
    }

    /** 经典 tcpip 模式的无线连接：直连目标机 adbd 监听端口 */
    fun connectWireless(ip: String, port: Int) {
        if (_state.value.busy || _state.value.connecting) return
        val host = ip.trim()
        if (port !in 1..65535) {
            setState(error = AppError("端口无效", "端口必须在 1–65535 之间。"))
            return
        }
        if (host.isEmpty()) {
            setState(error = AppError(L10n.t("IP 不能为空", "IP is empty"), L10n.t("填目标机的 Wi-Fi IP，例如 192.168.1.100。", "Use the target Wi-Fi IP, e.g. 192.168.1.100.")))
            return
        }
        viewModelScope.launch {
            setState(connecting = true, stage = L10n.t("无线连接", "Wireless connect") + " $host:$port…", error = null)
            try {
                withContext(Dispatchers.IO) {
                    session.connectOverNetwork(
                        host, port,
                        getApplication<Application>().filesDir,
                        onEvent = { event -> setState(stage = event) }
                    )
                }
                setState(
                    connected = true,
                    connecting = false,
                    deviceLabel = "$host:$port (" + L10n.t("无线", "wireless") + ")",
                    stage = L10n.t("已无线连接", "Wireless connected") + " · $host:$port",
                    viaNetwork = true
                )
                AppLogger.i("ADB wireless connected: $host:$port")
            } catch (t: Throwable) {
                session.close()
                reportError(t, L10n.t("无线连接失败", "Wireless connect failed"))
                setState(connecting = false, connected = false, viaNetwork = false, deviceLabel = "")
            }
        }
    }

    /**
     * 一键开启无线模式（仅 USB 连接时可用）：
     * 发送 tcpip:5555 让目标机 adbd 转为 TCP 监听，并自动探测目标机 IP 回填输入框。
     */
    fun enableWirelessMode() {
        if (!_state.value.connected || _state.value.viaNetwork || _state.value.busy || _state.value.connecting) return
        viewModelScope.launch {
            setState(busy = true, stage = L10n.t("开启无线模式…", "Enabling wireless mode…"), error = null)
            try {
                val ip = withContext(Dispatchers.IO) {
                    val detected = session.deviceWifiIp()
                    session.enableTcpip()
                    detected
                }
                session.close()
                // adbd 重启后原 USB 连接已断开
                setState(
                    busy = false,
                    connected = false,
                    viaNetwork = false,
                    stage = if (ip != null) {
                        L10n.t("无线模式已开启 · 目标机 IP：$ip（端口 5555），同 Wi-Fi 下点「无线连接」", "Wireless mode on - target IP: $ip (port 5555). Tap Wireless connect on the same Wi-Fi.")
                    } else {
                        L10n.t("无线模式已开启 · 端口 5555（未能自动获取 IP，请在目标机 WLAN 设置里查看）", "Wireless mode on - port 5555 (could not auto-detect IP; check the target Wi-Fi settings)")
                    },
                    suggestedIp = ip
                )
                appendOutput("✓ tcpip:5555 " + L10n.t("已发送", "sent") + (ip?.let { ", IP: $it" } ?: ""))
            } catch (t: Throwable) {
                reportError(t, L10n.t("开启无线模式失败", "Failed to enable wireless mode"))
                setState(busy = false)
            }
        }
    }

    // ---------- shell ----------

    fun runShell(raw: String) {
        val command = raw.trim()
        if (command.isEmpty()) return
        if (!ensureConnected()) return

        viewModelScope.launch {
            setState(busy = true, stage = L10n.t("执行", "Running") + ": $command", error = null)
            try {
                val output = withContext(Dispatchers.IO) {
                    session.shell(command)
                }
                prefs.rememberAdbCommand(command)
                appendOutput("› $command")
                output.lines().forEach { appendOutput("  $it") }
                setState(
                    busy = false,
                    stage = L10n.t("命令完成", "Command finished"),
                    commandHistory = prefs.adbCommandHistory()
                )
            } catch (t: Throwable) {
                appendOutput("› $command")
                appendOutput("  ✗ ${t.message}")
                (t as? AppException)?.hint?.takeIf { it.isNotBlank() }?.let {
                    appendOutput("    提示：$it")
                }
                AppLogger.e(t, "ADB shell 失败")
                setState(busy = false, stage = L10n.t("命令失败", "Command failed"))
            }
        }
    }

    fun reboot(target: String?) {
        if (!ensureConnected()) return
        viewModelScope.launch {
            setState(busy = true, stage = L10n.t("正在重启设备", "Rebooting device"), error = null)
            withContext(Dispatchers.IO) {
                session.reboot(target)
            }
            appendOutput("› " + L10n.t("重启指令已发送", "Reboot command sent") + (target?.let { " ($it)" } ?: ""))
            setState(busy = false, stage = L10n.t("设备重启中，连接即将断开", "Device rebooting, connection will drop"))
            kotlinx.coroutines.delay(1_200)
            disconnect()
        }
    }

    // ---------- 文件传输 ----------

    fun push(uri: Uri, remotePath: String) {
        if (!ensureConnected()) return
        val path = remotePath.trim()
        if (path.isEmpty() || !path.contains('/')) {
            setState(
                error = AppError(
                    L10n.t("目标路径不合法", "Invalid remote path"),
                    L10n.t("请写完整路径，例如 /sdcard/logo.png。", "Use a full path, e.g. /sdcard/logo.png.")
                )
            )
            return
        }

        viewModelScope.launch {
            val label = path.substringAfterLast('/')
            prefs.adbPushPath = path
            setState(busy = true, progress = 0f, stage = L10n.t("推送", "Pushing") + " $label", error = null, lastTransfer = null, savedPushPath = path)
            KeepAliveService.start(getApplication(), "正在推送 $label，请勿断开数据线")
            try {
                withContext(Dispatchers.IO) {
                    UriSource(getApplication(), uri).use { source ->
                        session.push(source, path) { done, total ->
                            setState(
                                progress = if (total > 0) (done.toFloat() / total) else -1f,
                                stage = L10n.t("推送", "Pushing") + " $label · ${formatBytes(done)}"
                            )
                        }
                    }
                }
                setState(busy = false, stage = L10n.t("推送完成", "Push complete"), lastTransfer = L10n.t("已推送到", "Pushed to") + " $path")
                appendOutput("✓ " + L10n.t("推送完成", "Push complete") + " → $path")
            } catch (t: Throwable) {
                reportError(t, L10n.t("推送失败", "Push failed"))
                setState(busy = false)
            } finally {
                KeepAliveService.stop(getApplication())
            }
        }
    }

    fun pull(remotePath: String, fileName: String) {
        if (!ensureConnected()) return
        val path = remotePath.trim()
        val name = fileName.trim()
        if (path.isEmpty() || name.isEmpty()) {
            setState(
                error = AppError(
                    L10n.t("路径或文件名不完整", "Path or file name incomplete"),
                    L10n.t("远程路径写完整路径，文件名只写名字，例如 dump.png。", "Full remote path; file name only, e.g. dump.png.")
                )
            )
            return
        }

        viewModelScope.launch {
            prefs.adbPullPath = path
            setState(busy = true, progress = -1f, stage = L10n.t("拉取", "Pulling") + " $name", error = null, lastTransfer = null, savedPullPath = path)
            try {
                val target = withContext(Dispatchers.IO) {
                    val base = getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir
                    val dir = File(base, "adb").apply { mkdirs() }
                    File(dir, name)
                }
                withContext(Dispatchers.IO) {
                    session.pull(path, target) { done, _ ->
                        setState(stage = L10n.t("拉取", "Pulling") + " $name · ${formatBytes(done)}")
                    }
                }
                setState(
                    busy = false,
                    stage = L10n.t("拉取完成", "Pull complete"),
                    lastTransfer = L10n.t("已保存", "Saved") + ": ${target.absolutePath}",
                    lastPulledPath = target.absolutePath
                )
                appendOutput("✓ " + L10n.t("拉取完成", "Pull complete") + ": ${target.absolutePath}")
            } catch (t: Throwable) {
                reportError(t, L10n.t("拉取失败", "Pull failed"))
                setState(busy = false)
            }
        }
    }

    // ---------- 输出与状态 ----------

    fun dismissError() = setState(error = null)

    fun clearOutput() = setState(output = emptyList())

    private fun ensureConnected(): Boolean {
        if (_state.value.busy || _state.value.connecting) return false
        if (_state.value.connected) return true
        setState(error = AppError(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")))
        return false
    }

    private fun setState(
        devices: List<DeviceItem> = _state.value.devices,
        connected: Boolean = _state.value.connected,
        connecting: Boolean = _state.value.connecting,
        deviceLabel: String = _state.value.deviceLabel,
        busy: Boolean = _state.value.busy,
        stage: String = _state.value.stage,
        output: List<String> = _state.value.output,
        progress: Float = _state.value.progress,
        error: AppError? = _state.value.error,
        lastTransfer: String? = _state.value.lastTransfer,
        commandHistory: List<String> = _state.value.commandHistory,
        savedPushPath: String = _state.value.savedPushPath,
        savedPullPath: String = _state.value.savedPullPath,
        lastPulledPath: String? = _state.value.lastPulledPath,
        viaNetwork: Boolean = _state.value.viaNetwork,
        suggestedIp: String? = _state.value.suggestedIp,
        lanScanning: Boolean = _state.value.lanScanning,
        lanFound: List<String> = _state.value.lanFound
    ) {
        _state.value = AdbUiState(
            devices, connected, connecting, deviceLabel, busy, stage, output, progress,
            error, lastTransfer, commandHistory, savedPushPath, savedPullPath,
            lastPulledPath, viaNetwork, suggestedIp, lanScanning, lanFound
        )
    }

    private fun appendOutput(line: String) {
        setState(output = (_state.value.output + line).takeLast(300))
    }

    private fun reportError(t: Throwable, prefix: String) {
        val error = AppError.from(t)
        AppLogger.e("$prefix：${error.title}")
        error.hint?.let { AppLogger.w("排查建议：$it") }
        setState(error = error, stage = prefix)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    override fun onCleared() {
        mirroring.value = false
        session.requestStreamStop()
        session.close()
        super.onCleared()
    }
}
