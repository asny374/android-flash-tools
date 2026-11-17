package com.dz.fastboototg

import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dz.fastboototg.core.AppConfig
import com.dz.fastboototg.core.AppError
import com.dz.fastboototg.core.AppException
import com.dz.fastboototg.core.FastbootScript
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.Prefs
import com.dz.fastboototg.core.ScriptStore
import com.dz.fastboototg.core.io.ByteArraySource
import com.dz.fastboototg.core.io.RandomAccessSource
import com.dz.fastboototg.core.io.UriSource
import com.dz.fastboototg.core.io.asSource
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.flash.FlashOrchestrator
import com.dz.fastboototg.flash.OtaFlasher
import com.dz.fastboototg.flash.OtaPayload
import com.dz.fastboototg.flash.SparseImage
import com.dz.fastboototg.flash.VbmetaPatch
import com.dz.fastboototg.service.KeepAliveService
import com.dz.fastboototg.ui.DeviceItem
import com.dz.fastboototg.ui.OtaPartitionInfo
import com.dz.fastboototg.ui.PendingFlash
import com.dz.fastboototg.ui.PendingOta
import com.dz.fastboototg.ui.QuickCommand
import com.dz.fastboototg.ui.QueueItem
import com.dz.fastboototg.ui.UiState
import com.dz.fastboototg.usb.FastbootException
import com.dz.fastboototg.usb.UsbDeviceScanner
import com.dz.fastboototg.usb.UsbPermissionController
import com.dz.fastboototg.usb.describe
import com.dz.fastboototg.usb.fastboot.FastbootSession
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * fastboot 主流程的 ViewModel。
 *
 * 职责边界：只做「业务结果 → 界面状态」的映射。
 * USB 会话（打开/重试/关闭）在 [FastbootSession]，刷写编排在 [FlashOrchestrator]，
 * 设备枚举在 [UsbDeviceScanner] —— 每一层都可以单独理解和测试。
 */
class FlashViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = UsbDeviceScanner(application)
    private val permission = UsbPermissionController(application)
    private val session = FastbootSession(scanner)
    private val scriptStore = ScriptStore(application)
    private val prefs = Prefs(application)

    /** OTA 解析期间保持打开的数据源（确认刷写 / 取消 / 销毁时关闭） */
    private var otaPayloadSource: RandomAccessSource? = null
    private var otaManifest: OtaPayload.OtaManifest? = null
    private var otaTempFile: File? = null

    /** 初始状态：脚本列表 + 偏好里的默认分区 / 详细日志默认值 / 日志上限 */
    private val initialState: UiState = run {
        var s = UiState(scripts = scriptStore.load())
        prefs.defaultPartition.takeIf { it.isNotBlank() }?.let { s = s.copy(partition = it) }
        AppLogger.setDetailed(prefs.defaultDetailedLog)
        AppLogger.setMaxSizeMb(prefs.maxLogMb)
        s = s.copy(
            detailedLog = AppLogger.detailed.value,
            maxLogMb = AppLogger.maxSizeMb.value
        )
        s
    }

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        runEnvironmentCheck()
        refreshDevices()
    }

    // ---------- 设置 ----------

    /** 设置页改默认分区：持久化并同步当前输入框 */
    fun setDefaultPartition(value: String) {
        prefs.defaultPartition = value.trim()
        if (_state.value.partition.isBlank()) {
            _state.value = _state.value.copy(partition = value.trim())
        }
    }

    /** 设置页改详细日志默认值：立即生效并持久化 */
    fun setDefaultDetailedLog(enabled: Boolean) {
        prefs.defaultDetailedLog = enabled
        AppLogger.setDetailed(enabled)
        _state.value = _state.value.copy(detailedLog = enabled)
    }

    // ---------- 设备 ----------

    fun refreshDevices() {
        val items = scanner.fastbootCandidates().map {
            DeviceItem(it, scanner.describe(it), permission.hasPermission(it), scanner.modeOf(it))
        }
        _state.value = _state.value.copy(devices = items)
        if (items.isNotEmpty()) {
            AppLogger.i("发现 ${items.size} 个疑似 fastboot 设备")
        }
    }

    fun connect(item: DeviceItem) {
        if (_state.value.busy || _state.value.connecting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                connecting = true,
                stage = L10n.t("连接中", "Connecting"),
                error = null,
                connectionLog = listOf("目标：${item.label}"),
                lastConnected = item
            )
            try {
                withContext(Dispatchers.IO) {
                    val diag = scanner.diagnostics(item.device)
                    _state.value = _state.value.copy(diagnostics = diag)
                    AppLogger.i("设备诊断信息：\n$diag")

                    appendConnectionLog(L10n.t("请求 USB 授权…", "Requesting USB permission…"))
                    val variables = session.open(item.device, permission) { event ->
                        appendConnectionLog(event)
                        _state.value = _state.value.copy(stage = event)
                    }

                    val unlocked = variables["unlocked"]
                    val slot = variables["current-slot"]

                    appendConnectionLog(L10n.t("完成，读取到 ${variables.size} 项变量", "Done — ${variables.size} variables read"))
                    _state.value = _state.value.copy(
                        connected = true,
                        deviceLabel = item.label,
                        variables = variables,
                        deviceSummary = buildDeviceSummary(variables),
                        stage = L10n.t("已连接", "Connected") + " · " + when {
                            unlocked.equals("yes", true) -> L10n.t("BL 已解锁", "bootloader unlocked")
                            unlocked.equals("no", true) -> L10n.t("BL 未解锁（无法刷写）", "bootloader locked (flashing forbidden)")
                            else -> L10n.t("解锁状态未知", "unlock state unknown")
                        } + (if (!slot.isNullOrBlank()) L10n.t(" · 当前槽位 ", " · slot ") + slot else "")
                    )
                }
            } catch (t: Throwable) {
                session.close()
                appendConnectionLog(L10n.t("连接失败", "Connection failed") + ": ${t.message}")
                reportError(t, L10n.t("连接失败", "Connection failed"))
            } finally {
                _state.value = _state.value.copy(connecting = false, busy = false)
            }
        }
    }

    /** 一键重连上次设备 */
    fun reconnectLast() {
        _state.value.lastConnected?.let { connect(it) }
    }

    fun disconnect() {
        session.close()
        _state.value = _state.value.copy(
            connected = false,
            deviceLabel = "",
            diagnostics = "",
            variables = emptyMap(),
            deviceSummary = emptyList(),
            progress = 0f,
            progressInfo = null,
            stage = L10n.t("未连接", "Not connected"),
            connectionLog = emptyList()
        )
        AppLogger.i("disconnected")
    }

    /** 连接成功后从变量表里挑出人能看懂的关键信息 */
    private fun buildDeviceSummary(variables: Map<String, String>): List<Pair<String, String>> {
        val labels = linkedMapOf(
            "product" to L10n.t("机型代号", "Product"),
            "device" to L10n.t("设备", "Device"),
            "version-bootloader" to "bootloader",
            "version-baseband" to L10n.t("基带", "Baseband"),
            "serialno" to L10n.t("序列号", "Serial no."),
            "unlocked" to L10n.t("解锁状态", "Unlock"),
            "current-slot" to L10n.t("槽位", "Slot"),
            "battery-soc-ok" to L10n.t("电量充足", "Battery OK"),
            "battery-voltage" to L10n.t("电池电压", "Battery voltage"),
            "kernel" to L10n.t("内核", "Kernel")
        )
        return labels.mapNotNull { (key, label) ->
            variables[key]?.takeIf { it.isNotBlank() }?.let { label to it }
        }
    }

    private fun appendConnectionLog(line: String) {
        _state.value = _state.value.copy(
            connectionLog = (_state.value.connectionLog + line).takeLast(8)
        )
    }

    // ---------- 刷写 ----------

    fun setPartition(value: String) {
        _state.value = _state.value.copy(partition = value)
    }

    /**
     * 选完镜像先做预检：文件信息 + sparse 判断 + 解锁状态校验，
     * 结果放进 pendingFlash，等用户在确认对话框里点头才真正开刷。
     */
    fun prepareFlash(uri: Uri) {
        if (!_state.value.connected) {
            reportError(FastbootException(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")), L10n.t("无法刷写", "Cannot flash"))
            return
        }
        if (_state.value.busy) return
        val partition = _state.value.partition.trim()
        if (partition.isEmpty()) {
            reportError(FastbootException(L10n.t("分区名不能为空", "Partition name is empty"), L10n.t("比如 boot、recovery、vbmeta。", "e.g. boot, recovery, vbmeta.")), L10n.t("无法刷写", "Cannot flash"))
            return
        }
        if (_state.value.variables["unlocked"]?.equals("yes", true) != true) {
            reportError(
                FastbootException(
                    L10n.t("目标机 bootloader 未解锁", "Target bootloader is locked"),
                    L10n.t("锁定状态下任何写入都会失败。先解锁 bootloader 再刷。", "Writes always fail while locked. Unlock the bootloader first.")
                ),
                L10n.t("无法刷写", "Cannot flash")
            )
            return
        }

        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    UriSource(getApplication(), uri).use { source ->
                        val head = ByteArray(minOf(28L, source.length).toInt())
                        source.readAt(0, head, 0, head.size)
                        PendingFlash(
                            uri = uri,
                            fileName = queryFileName(uri),
                            sizeText = formatBytes(source.length),
                            isSparse = SparseImage.isSparse(head)
                        )
                    }
                }
                _state.value = _state.value.copy(pendingFlash = info, error = null)
            } catch (t: Throwable) {
                reportError(t, L10n.t("读取镜像信息失败", "Failed to read image info"))
            }
        }
    }

    fun cancelFlash() {
        _state.value = _state.value.copy(pendingFlash = null)
    }

    fun confirmFlash(disableVerity: Boolean = false, disableVerification: Boolean = false) {
        val pending = _state.value.pendingFlash ?: return
        _state.value = _state.value.copy(pendingFlash = null)
        doFlash(pending.uri, pending.fileName, disableVerity, disableVerification)
    }

    private fun doFlash(
        uri: Uri,
        fileName: String,
        disableVerity: Boolean = false,
        disableVerification: Boolean = false
    ) {
        if (!_state.value.connected) {
            reportError(FastbootException(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")), L10n.t("无法刷写", "Cannot flash"))
            return
        }
        if (_state.value.busy) return
        val partition = _state.value.partition.trim()

        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true, progress = 0f, progressInfo = null,
                stage = L10n.t("准备刷写", "Preparing to flash") + " $partition", error = null
            )
            KeepAliveService.start(getApplication(), L10n.t("正在刷写 $partition（$fileName），请勿断开数据线", "Flashing $partition ($fileName) — do not unplug"))
            val meter = ProgressMeter()
            try {
                withContext(Dispatchers.IO) {
                    // vbmeta + 勾选禁用标志：先打 AVB flags 补丁，再刷补丁后的镜像
                    val source = if (partition.equals("vbmeta", true) &&
                        (disableVerity || disableVerification)
                    ) {
                        val patched = VbmetaPatch.patch(
                            UriSource(getApplication(), uri), disableVerity, disableVerification
                        )
                        AppLogger.i(
                            "vbmeta 补丁完成：verity=${if (disableVerity) "禁用" else "保持"}，" +
                                "verification=${if (disableVerification) "禁用" else "保持"}"
                        )
                        patched.asSource()
                    } else {
                        UriSource(getApplication(), uri)
                    }
                    flashImage(source, partition, fileName, meter)
                }
                _state.value = _state.value.copy(
                    stage = "$partition " + L10n.t("刷写完成", "flash complete"),
                    progress = 1f,
                    progressInfo = null
                )
                AppLogger.i("$partition " + L10n.t("刷写完成", "flash complete") + " ($fileName)")
            } catch (t: Throwable) {
                reportError(t, L10n.t("刷写失败", "Flash failed"))
            } finally {
                _state.value = _state.value.copy(busy = false)
                KeepAliveService.stop(getApplication())
            }
        }
    }

    /** 单个镜像的下载 + 刷写（单镜像与队列共用）；进度同步到状态与前台通知 */
    private suspend fun flashImage(
        source: RandomAccessSource,
        partition: String,
        fileName: String,
        meter: ProgressMeter
    ) {
        withContext(Dispatchers.IO) {
            source.use {
                val orchestrator = FlashOrchestrator(
                    protocol = session.requireProtocol(),
                    onProgress = { done, total ->
                        val info = meter.update(done, total)
                        _state.value = _state.value.copy(
                            progress = (done.toFloat() / total).coerceIn(0f, 1f),
                            progressInfo = info
                        )
                        KeepAliveService.update(
                            getApplication(),
                            L10n.t("正在刷写", "Flashing") + " $partition ($fileName) · ${(done.toFloat() / total * 100).toInt()}%" +
                                (info?.let { " · $it" } ?: "")
                        )
                    },
                    onStage = { stage -> _state.value = _state.value.copy(stage = stage) }
                )
                orchestrator.flash(partition, source)
            }
        }
    }

    // ---------- 多镜像队列 ----------

    /** 文件名推断分区名：boot.img → boot；推不出的留空由用户补 */
    fun enqueueImages(uris: List<Uri>) {
        if (_state.value.busy) return
        val items = uris.mapNotNull { uri ->
            val rawName = queryFileName(uri)
            val base = rawName.substringBeforeLast('.').trim().lowercase()
            if (base.isBlank()) return@mapNotNull null
            QueueItem(fileName = rawName, uri = uri, partition = base)
        }
        if (items.isNotEmpty()) {
            _state.value = _state.value.copy(flashQueue = items)
            AppLogger.i("镜像队列已加入 ${items.size} 个文件")
        }
    }

    fun setQueuePartition(fileName: String, partition: String) {
        _state.value = _state.value.copy(
            flashQueue = _state.value.flashQueue.map {
                if (it.fileName == fileName) it.copy(partition = partition.trim().lowercase()) else it
            }
        )
    }

    fun removeQueueItem(fileName: String) {
        _state.value = _state.value.copy(
            flashQueue = _state.value.flashQueue.filter { it.fileName != fileName }
        )
    }

    fun clearQueue() {
        _state.value = _state.value.copy(flashQueue = emptyList())
    }

    /** 顺序刷写整个队列：任一步失败即中止（已刷入的部分无法回滚） */
    fun flashQueue() {
        val queue = _state.value.flashQueue
        if (queue.isEmpty()) return
        if (!_state.value.connected) {
            reportError(FastbootException(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")), L10n.t("无法刷写", "Cannot flash"))
            return
        }
        if (_state.value.busy) return
        if (queue.any { it.partition.isBlank() }) {
            reportError(FastbootException("队列里有分区名未填写", "每行都要有分区名才能开始。"), L10n.t("无法刷写", "Cannot flash"))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, progress = 0f, progressInfo = null, error = null)
            KeepAliveService.start(getApplication(), L10n.t("队列刷写 ${queue.size} 个镜像，请勿断开数据线", "Queue flashing ${queue.size} images — do not unplug"))
            try {
                queue.forEachIndexed { index, item ->
                    val meter = ProgressMeter()
                    _state.value = _state.value.copy(
                        progress = index.toFloat() / queue.size,
                        stage = L10n.t("队列", "Queue") + " ${index + 1}/${queue.size}: " + L10n.t("刷写", "flashing") + " ${item.partition}"
                    )
                    withContext(Dispatchers.IO) {
                        flashImage(UriSource(getApplication(), item.uri), item.partition, item.fileName, meter)
                    }
                    AppLogger.i("queue ${index + 1}/${queue.size} done: ${item.partition} ← ${item.fileName}")
                }
                _state.value = _state.value.copy(
                    stage = L10n.t("队列刷写完成（${queue.size} 个）", "Queue complete (${queue.size})"),
                    progress = 1f
                )
            } catch (t: Throwable) {
                reportError(t, L10n.t("队列刷写失败", "Queue flash failed"))
            } finally {
                _state.value = _state.value.copy(busy = false)
                KeepAliveService.stop(getApplication())
            }
        }
    }

    /** 刷写完成后重启目标机 */
    fun rebootDevice() {
        if (!_state.value.connected) return
        viewModelScope.launch {
            _state.value = _state.value.copy(stage = L10n.t("正在重启设备", "Rebooting device"), busy = true)
            try {
                withContext(Dispatchers.IO) {
                    session.requireProtocol().reboot(null)
                }
                delay(1_200)
                disconnect()
            } catch (t: Throwable) {
                // 设备重启导致连接断开属预期行为
                AppLogger.i("reboot sent; disconnect expected")
                disconnect()
            }
        }
    }

    // ---------- OTA 包 ----------

    /**
     * 选完 OTA 包（zip 或裸 payload.bin）先解析出分区清单，
     * 用户在对话框里选定分区并二次确认后才开刷。
     */
    fun prepareOta(uri: Uri) {
        if (!_state.value.connected) {
            reportError(FastbootException(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")), L10n.t("无法刷写", "Cannot flash"))
            return
        }
        if (_state.value.busy) return
        if (_state.value.variables["unlocked"]?.equals("yes", true) != true) {
            reportError(
                FastbootException(
                    L10n.t("目标机 bootloader 未解锁", "Target bootloader is locked"),
                    L10n.t("锁定状态下任何写入都会失败。先解锁 bootloader 再刷。", "Writes always fail while locked. Unlock the bootloader first.")
                ),
                L10n.t("无法刷写", "Cannot flash")
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = L10n.t("解析 OTA 包…", "Parsing OTA…"), error = null)
            try {
                withContext(Dispatchers.IO) {
                    closeOtaResources()

                    val displayName = queryFileName(uri)
                    val isZip = displayName.endsWith(".zip", ignoreCase = true)

                    val source = if (isZip) {
                        appendConnectionLog(L10n.t("提取 zip 里的 payload.bin…", "Extracting payload.bin from zip…"))
                        val temp = File(
                            getApplication<Application>().cacheDir,
                            "ota-payload-${System.currentTimeMillis()}.bin"
                        )
                        otaTempFile = temp
                        extractPayloadFromZip(uri, temp)
                        temp.asSource()
                    } else {
                        UriSource(getApplication(), uri)
                    }

                    otaPayloadSource = source
                    val manifest = OtaPayload.parse(source)
                    otaManifest = manifest

                    val partitions = manifest.partitions.map {
                        OtaPartitionInfo(it.name, it.size)
                    }
                    _state.value = _state.value.copy(
                        busy = false,
                        pendingOta = PendingOta(uri, isZip, partitions),
                        stage = L10n.t("OTA 解析完成，共 ${partitions.size} 个分区", "OTA parsed — ${partitions.size} partitions")
                    )
                    AppLogger.i("OTA 解析完成：$displayName，${partitions.size} 个分区，块 ${manifest.blockSize}")
                }
            } catch (t: Throwable) {
                closeOtaResources()
                reportError(t, L10n.t("OTA 解析失败", "OTA parse failed"))
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** 从 zip 里流式提取 payload.bin（文件可达数 GB，边读边写不进内存） */
    private fun extractPayloadFromZip(uri: Uri, target: File) {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw AppException("无法打开所选 zip", "文件可能被移动或删除了。")
        input.use { raw ->
            ZipInputStream(raw.buffered(1024 * 1024)).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: throw AppException(
                        "zip 里没有 payload.bin",
                        "确认选的是完整刷机包；或手动解压出 payload.bin 再直接选它。"
                    )
                    if (entry.name.equals("payload.bin", ignoreCase = true) ||
                        entry.name.endsWith("/payload.bin", ignoreCase = true)
                    ) {
                        target.outputStream().buffered(1024 * 1024).use { out ->
                            zip.copyTo(out, 1024 * 1024)
                        }
                        AppLogger.i("已提取 payload.bin（${target.length()} 字节）")
                        return
                    }
                }
            }
        }
    }

    fun cancelOta() {
        _state.value = _state.value.copy(pendingOta = null)
        closeOtaResources()
    }

    /** 用户在对话框里选定分区并确认，开始流式刷写 */
    fun confirmOtaPartition(partitionName: String) {
        val pending = _state.value.pendingOta ?: return
        val manifest = otaManifest ?: return
        val source = otaPayloadSource ?: return
        val partition = manifest.partitions.firstOrNull { it.name == partitionName } ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(pendingOta = null)

        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true, progress = 0f, progressInfo = null,
                stage = L10n.t("准备刷写", "Preparing to flash") + " $partitionName (OTA)", error = null
            )
            KeepAliveService.start(getApplication(), L10n.t("正在刷写 $partitionName（OTA），请勿断开数据线", "Flashing $partitionName (OTA) — do not unplug"))
            val meter = ProgressMeter()
            try {
                withContext(Dispatchers.IO) {
                    val protocol = session.requireProtocol()
                    val capacity = protocol.partitionSize(partition.name)
                    if (capacity != null && partition.size > capacity) throw FastbootException("OTA 镜像超过分区容量")
                    OtaFlasher(protocol).flashPartition(
                        partition,
                        manifest,
                        source,
                        onProgress = { done, total ->
                            val info = meter.update(done, total)
                            _state.value = _state.value.copy(
                                progress = (done.toFloat() / total).coerceIn(0f, 1f),
                                progressInfo = info
                            )
                            KeepAliveService.update(
                                getApplication(),
                                L10n.t("OTA 刷写", "OTA flashing") + " $partitionName · ${(done.toFloat() / total * 100).toInt()}%" +
                                    (info?.let { " · $it" } ?: "")
                            )
                        },
                        onStage = { stage -> _state.value = _state.value.copy(stage = stage) }
                    )
                }
                _state.value = _state.value.copy(
                    stage = "$partitionName " + L10n.t("刷写完成", "flash complete"),
                    progress = 1f,
                    progressInfo = null
                )
                AppLogger.i("OTA $partitionName 刷写完成")
            } catch (t: Throwable) {
                reportError(t, L10n.t("OTA 刷写失败", "OTA flash failed"))
            } finally {
                _state.value = _state.value.copy(busy = false)
                KeepAliveService.stop(getApplication())
            }
        }
    }

    private fun closeOtaResources() {
        runCatching { otaPayloadSource?.close() }
        otaPayloadSource = null
        otaManifest = null
        runCatching { otaTempFile?.delete() }
        otaTempFile = null
    }

    /** 刷写进度计时器：估算速度与剩余时间 */
    private class ProgressMeter {
        private var lastTime = 0L
        private var lastDone = 0L
        private var cached: String? = null

        fun update(done: Long, total: Long): String? {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            if (lastTime == 0L) { lastTime = now; lastDone = done; return cached }
            if (elapsed < 800) return cached
            val speed = (done - lastDone) * 1000.0 / elapsed
            lastTime = now
            lastDone = done
            if (speed <= 0) return cached
            val remainSec = if (done < total) (total - done) / speed else 0.0
            cached = "%.1f MB/s · 剩余约 %s".format(
                speed / 1048576.0,
                if (remainSec >= 90) "${(remainSec / 60).toInt()} 分钟" else "${remainSec.toInt()} 秒"
            )
            return cached
        }
    }

    private fun queryFileName(uri: Uri): String {
        return runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: "所选镜像"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---------- 终端与快捷命令 ----------

    fun runTerminal(raw: String) {
        if (_state.value.busy) return
        val command = raw.trim()
        if (command.isEmpty()) return
        if (!_state.value.connected) {
            reportError(
                FastbootException(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")),
                L10n.t("无法执行命令", "Cannot run command")
            )
            return
        }

        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { executeTerminalCommand(command, throwOnError = false) }
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    /**
     * 执行单条终端命令并回显。
     * 业务错误默认只回显不抛出；脚本模式传 throwOnError=true 让外层中止脚本。
     */
    private fun executeTerminalCommand(command: String, throwOnError: Boolean) {
        appendTerminal("› $command")
        AppLogger.i("终端命令：$command")
        try {
            when {
                command == "getvar:all" -> {
                    val vars = session.requireProtocol().getVarAll()
                    vars.forEach { (k, v) -> appendTerminal("  $k = $v") }
                    appendTerminal("  （共 ${vars.size} 项）")
                }

                command.startsWith("getvar:") -> {
                    val r = session.executeWithRetry(command)
                    if (r is com.dz.fastboototg.usb.FastbootResponse.Fail) throw FastbootException(r.reason, deviceRejected = true)
                    appendTerminal("  ${r.describe()}")
                }

                else -> {
                    try {
                        val r = if (command.startsWith("reboot")) {
                            // reboot 会让设备立刻消失，重试只会浪费时间
                            session.requireProtocol()
                                .execute(command, AppConfig.FASTBOOT_CMD_TIMEOUT_MS)
                        } else {
                            session.executeWithRetry(command)
                        }
                        if (r is com.dz.fastboototg.usb.FastbootResponse.Fail) throw FastbootException(r.reason, deviceRejected = true)
                    appendTerminal("  ${r.describe()}")
                    } catch (t: FastbootException) {
                        if (command.startsWith("reboot") && !t.deviceRejected) {
                            appendTerminal("  设备已重启，连接断开是正常的")
                        } else {
                            throw t
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            val hint = (t as? AppException)?.hint.orEmpty()
            appendTerminal("  ✗ ${t.message}")
            if (hint.isNotBlank()) appendTerminal("    提示：$hint")
            AppLogger.e("终端命令失败：$command → ${t.message}")
            if (throwOnError) throw t
        }
    }

    // ---------- 一键脚本 ----------

    fun saveScript(name: String, commands: List<String>) {
        val cleanName = name.trim()
        val cleanCommands = commands.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanName.isEmpty() || cleanCommands.isEmpty()) return
        val existing = _state.value.scripts.filter { it.name != cleanName }
        scriptStore.save(existing + FastbootScript(cleanName, cleanCommands))
        _state.value = _state.value.copy(scripts = scriptStore.load())
        AppLogger.i("script saved: $cleanName (${cleanCommands.size} steps)")
    }

    fun deleteScript(name: String) {
        scriptStore.save(_state.value.scripts.filter { it.name != name })
        _state.value = _state.value.copy(scripts = scriptStore.load())
    }

    /** 顺序执行脚本：任一步失败立即中止（终端里会标注失败位置） */
    fun runScript(script: FastbootScript) {
        if (!_state.value.connected) {
            reportError(
                FastbootException(L10n.t("还没有连接设备", "No device connected"), L10n.t("先扫描并连接目标机。", "Scan and connect the target first.")),
                L10n.t("无法执行脚本", "Cannot run script")
            )
            return
        }
        if (_state.value.busy) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = L10n.t("脚本", "Script") + ": ${script.name}", error = null)
            try {
                script.commands.forEachIndexed { index, command ->
                    try {
                        withContext(Dispatchers.IO) {
                            executeTerminalCommand(command, throwOnError = true)
                        }
                    } catch (t: Throwable) {
                        appendTerminal("  " + L10n.t("脚本在第 ${index + 1}/${script.commands.size} 步中止", "Script aborted at step ${index + 1}/${script.commands.size}") + ": ${t.message}")
                        _state.value = _state.value.copy(
                            stage = L10n.t("脚本失败（第 ${index + 1} 步）", "Script failed (step ${index + 1})"),
                            busy = false
                        )
                        AppLogger.e("script ${script.name} failed at step ${index + 1}: ${t.message}")
                        return@launch
                    }
                    _state.value = _state.value.copy(stage = L10n.t("脚本", "Script") + ": ${script.name} (${index + 1}/${script.commands.size})")
                }
                _state.value = _state.value.copy(
                    stage = L10n.t("脚本", "Script") + " ${script.name} " + L10n.t("执行完成（${script.commands.size} 步）", "finished (${script.commands.size} steps)"),
                    busy = false
                )
                AppLogger.i("script ${script.name} finished")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun runQuickCommand(cmd: QuickCommand) {
        if (cmd.dangerous) {
            AppLogger.w("执行高危命令：${cmd.command}")
        }
        runTerminal(cmd.command)
        if (cmd.command.startsWith("reboot")) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(1_200)
                disconnect()
            }
        }
    }

    private fun appendTerminal(line: String) {
        _state.value = _state.value.copy(
            terminal = (_state.value.terminal + line).takeLast(AppConfig.TERMINAL_HISTORY_MAX)
        )
    }

    fun clearTerminal() {
        _state.value = _state.value.copy(terminal = emptyList())
    }

    // ---------- 环境与日志 ----------

    fun runEnvironmentCheck() {
        val report = scanner.environmentReport()
        _state.value = _state.value.copy(environment = report)
        AppLogger.i("环境自检结果：")
        report.forEach { (k, v) -> AppLogger.i("  $k = $v") }
        if (report["支持 USB Host"]?.startsWith("否") == true) {
            AppLogger.e("本机不支持 USB Host，无法作为刷机主机")
        }
    }

    fun setDetailedLog(enabled: Boolean) {
        AppLogger.setDetailed(enabled)
        _state.value = _state.value.copy(detailedLog = enabled)
    }

    /** 设置页改日志上限：持久化，下次启动自动应用 */
    fun setMaxLogSize(mb: Int) {
        prefs.maxLogMb = mb
        AppLogger.setMaxSizeMb(mb)
        _state.value = _state.value.copy(maxLogMb = AppLogger.maxSizeMb.value)
    }

    fun clearLog() {
        AppLogger.clear()
    }

    fun exportLog() {
        viewModelScope.launch {
            val environment = buildMap {
                putAll(scanner.environmentReport())
                put("当前设备", _state.value.deviceLabel.ifBlank { "无" })
                put("设备变量数", _state.value.variables.size.toString())
                put("最近错误", _state.value.error?.title ?: "无")
                put("详细日志", if (_state.value.detailedLog) "开启" else "关闭")
                put("日志上限", "${_state.value.maxLogMb} MB")
            }
            val file = withContext(Dispatchers.IO) {
                AppLogger.exportZip(getApplication(), environment)
            }
            _state.value = _state.value.copy(exportedFile = file?.absolutePath)
        }
    }

    fun clearExportedFile() {
        _state.value = _state.value.copy(exportedFile = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun reportError(t: Throwable, prefix: String) {
        // 标题不带前缀（避免「连接失败：连接失败…」的重复）；前缀只进日志与 stage
        val error = AppError.from(t)
        AppLogger.e("$prefix: ${error.title}")
        error.hint?.let { AppLogger.w("排查建议：$it") }
        _state.value = _state.value.copy(
            stage = prefix,
            error = error,
            connected = false
        )
    }

    override fun onCleared() {
        session.close()
        closeOtaResources()
        super.onCleared()
    }
}
