package com.dz.fastboototg.ui.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dz.fastboototg.AdbViewModel
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.ui.AdbUiState
import com.dz.fastboototg.ui.DeviceItem
import com.dz.fastboototg.ui.components.ErrorBanner

// ---------------- ADB 页 ----------------

private fun t(zh: String, en: String) = L10n.t(zh, en)

/**
 * ADB 功能页：连接 ADB 设备、执行 shell、推送/拉取文件。
 *
 * 与 fastboot 页的定位互补：fastboot 面向 bootloader 里的底层操作，
 * ADB 面向系统内的调试操作。目标机在两种模式之间互斥，扫描是独立的。
 */
@Composable
fun AdbTab(state: AdbUiState, vm: AdbViewModel) {
    var command by remember { mutableStateOf("") }
    var pushPath by remember { mutableStateOf(state.savedPushPath) }
    var pullPath by remember { mutableStateOf(state.savedPullPath) }
    var pullName by remember { mutableStateOf("") }
    var wirelessIp by remember { mutableStateOf("") }
    var wirelessPort by remember { mutableStateOf("5555") }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 开启无线模式后自动回填探测到的 IP
    LaunchedEffect(state.suggestedIp) {
        state.suggestedIp?.let { wirelessIp = it }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.output.size) {
        if (state.output.isNotEmpty()) listState.animateScrollToItem(state.output.lastIndex)
    }

    val pushPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.push(uri, pushPath)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.error?.let { item { ErrorBanner(it, vm::dismissError) } }

        // ---- 设备 ----
        item { Text(t("检测到的 ADB 设备", "Detected ADB devices"), style = MaterialTheme.typography.titleSmall) }
        if (state.devices.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(t("没有检测到 ADB 设备", "No ADB devices found"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t(
                                "排查顺序：\n1. 目标机开启开发者选项 → USB 调试\n2. OTG 转接头插本机，数据线插目标机\n3. 首次连接目标机会弹「允许 USB 调试」，点允许\n4. 目标机处于 fastboot 模式时是连不上 ADB 的，先重启回系统",
                                "Checklist://n1. Enable Developer options → USB debugging on the target\n2. Plug the OTG adapter into this phone, data cable into the target\n3. First connection pops \"Allow USB debugging\" on the target — accept it\n4. ADB cannot connect while the target is in fastboot mode; boot it back to system"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.devices) { item -> AdbDeviceRow(item, state, vm) }
        }
        item {
            Button(shape = RoundedCornerShape(0.dp), onClick = { vm.refreshDevices() }, modifier = Modifier.fillMaxWidth()) {
                Text(t("重新扫描", "Rescan"))
            }
        }

        // ---- 无线调试 ----
        item { Text(t("无线调试（tcpip 模式）", "Wireless debugging (tcpip mode)"), style = MaterialTheme.typography.titleSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // USB 已连接时给一键开关：发送 tcpip:5555 + 自动探测 IP
                    if (state.connected && !state.viaNetwork) {
                        Text(
                            t("目标机已通过 USB 连接，可以一键切到无线（同一 Wi-Fi）。", "Target is connected over USB — switch to wireless (same Wi-Fi) with one tap."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
            shape = RoundedCornerShape(0.dp),
                            onClick = { vm.enableWirelessMode() },
                            enabled = !state.busy && !state.connecting,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(t("开启无线模式（tcpip:5555）", "Enable wireless mode (tcpip:5555)")) }
                    } else {
                        Text(
                            t(
                                "首次使用：先用 USB 连接目标机，点「开启无线模式」，之后拔线也能在 Wi-Fi 下直接连。",
                                "First time: connect the target over USB and tap Enable wireless mode; afterwards it works over Wi-Fi without the cable."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = wirelessIp,
                            onValueChange = { wirelessIp = it },
                            label = { Text(t("目标机 IP", "Target IP")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = wirelessPort,
                            onValueChange = { wirelessPort = it.filter { c -> c.isDigit() } },
                            label = { Text(t("端口", "Port")) },
                            singleLine = true,
                            modifier = Modifier.width(88.dp)
                        )
                    }
                    Button(
            shape = RoundedCornerShape(0.dp),
                        onClick = { vm.connectWireless(wirelessIp, wirelessPort.toIntOrNull() ?: 0) },
                        enabled = !state.busy && !state.connecting && wirelessIp.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.connected && state.viaNetwork) t("重新连接", "Reconnect") else t("无线连接", "Wireless connect"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                            onClick = { vm.scanLan(wirelessPort.toIntOrNull() ?: 0) },
                            enabled = !state.lanScanning && !state.busy && !state.connecting,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (state.lanScanning) t("扫描中…", "Scanning…") else t("扫描同网段设备", "Scan subnet")) }
                    }
                    if (state.lanFound.isNotEmpty()) {
                        Text(
                            t("发现 ${state.lanFound.size} 台（点击回填 IP）", "Found ${state.lanFound.size} — tap to fill IP"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.lanFound.forEach { host ->
                                androidx.compose.material3.SuggestionChip(
            shape = RoundedCornerShape(4.dp),
                                    onClick = { wirelessIp = host },
                                    label = { Text(host) }
                                )
                            }
                        }
                    }
                    Text(
                        t(
                            "说明：这是 adb tcpip 经典模式，需先用 USB 开启一次；开发者选项里的「无线调试」（TLS 配对码）暂不支持。",
                            "Note: classic adb tcpip mode, enabled once over USB. The developer-option \"Wireless debugging\" (TLS pairing) is not supported yet."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { AdbToolsCard(state, vm) }

        // ---- shell ----
        if (state.connected) {
            item { Text(t("Shell 命令", "Shell"), style = MaterialTheme.typography.titleSmall) }

            // 命令历史：点一下直接重发
            if (state.commandHistory.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            t("最近命令", "Recent"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.commandHistory.take(3).forEach { history ->
                                androidx.compose.material3.SuggestionChip(
            shape = RoundedCornerShape(4.dp),
                                    onClick = { vm.runShell(history) },
                                    label = {
                                        Text(
                                            history,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = { Text(t("例如 getprop ro.product.model", "e.g. getprop ro.product.model")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
            shape = RoundedCornerShape(0.dp),
                        onClick = {
                            vm.runShell(command)
                            command = ""
                        },
                        enabled = !state.busy
                    ) { Text(t("执行", "Run")) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                        onClick = { vm.reboot(null) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(t("重启设备", "Reboot device")) }
                    OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                        onClick = { vm.reboot("bootloader") },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(t("重启到 bootloader", "Reboot to bootloader")) }
                    OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                        onClick = { vm.reboot("recovery") },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(t("重启到 recovery", "Reboot to recovery")) }
                }
            }

            // ---- 文件 ----
            item { Text(t("文件传输", "File transfer"), style = MaterialTheme.typography.titleSmall) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("推送到目标机", "Push to target"), style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = pushPath,
                            onValueChange = { pushPath = it },
                            label = { Text(t("目标路径（含文件名）", "Remote path (with file name)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
            shape = RoundedCornerShape(0.dp),
                            onClick = { pushPicker.launch(arrayOf("*/*")) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(t("选择文件并推送", "Pick file and push")) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("从目标机拉取", "Pull from target"), style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = pullPath,
                            onValueChange = { pullPath = it },
                            label = { Text(t("远程路径", "Remote path")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pullName,
                            onValueChange = { pullName = it },
                            label = { Text(t("保存文件名", "Save as")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
            shape = RoundedCornerShape(0.dp),
                            onClick = { vm.pull(pullPath, pullName) },
                            enabled = !state.busy && pullName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(t("拉取文件", "Pull")) }
                    }
                }
            }

            state.lastTransfer?.let { path ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        // 拉取完成后可直接把文件分享出去（微信/网盘/其他应用）
                        state.lastPulledPath?.let { pulledPath ->
                            androidx.compose.material3.TextButton(shape = RoundedCornerShape(0.dp), onClick = {
                                shareFile(context, pulledPath)
                            }) { Text(t("分享", "Share")) }
                        }
                    }
                }
            }
        }

        // ---- 进度 ----
        if (state.busy) {
            item {
                Column {
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${(state.progress * 100).toInt()}% · ${state.stage}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(state.stage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ---- 输出 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("输出", "Output"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.clearOutput() }) { Text(t("清空", "Clear")) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().height(220.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    if (state.output.isEmpty()) {
                        item {
                            Text(
                                t("还没有执行过操作。\n先连接设备，再执行 shell 或传输文件。", "Nothing yet.\nConnect a device first, then run shell or transfer files."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(state.output) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

/** 通过系统分享把拉取下来的文件发给其他应用 */
private fun shareFile(context: android.content.Context, filePath: String) {
    runCatching {
        val file = java.io.File(filePath)
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, t("分享文件", "Share file"))
        )
    }
}

@Composable
private fun AdbDeviceRow(item: DeviceItem, state: AdbUiState, vm: AdbViewModel) {    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (item.authorized) t("已授权", "Authorized") else t("尚未授权，连接时会弹出系统对话框", "Not authorized; a system dialog appears on connect"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = { vm.connect(item) },
                    enabled = !state.connecting && !state.connected
                ) {
                    Text(if (state.connecting) t("连接中…", "Connecting…") else t("连接", "Connect"))
                }
                if (state.connected) {
                    OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { vm.disconnect() }) { Text(t("断开", "Disconnect")) }
                }
            }
        }
    }
}
