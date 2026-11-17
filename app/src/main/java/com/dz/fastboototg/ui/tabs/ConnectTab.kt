package com.dz.fastboototg.ui.tabs

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dz.fastboototg.FlashViewModel
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.ui.DeviceItem
import com.dz.fastboototg.ui.UiState
import com.dz.fastboototg.ui.components.DiagnosticsCard
import com.dz.fastboototg.ui.components.ErrorBanner

// ---------------- 连接页 ----------------

private fun t(zh: String, en: String) = L10n.t(zh, en)

@Composable
fun ConnectTab(state: UiState, vm: FlashViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.error?.let { item { ErrorBanner(it, vm::dismissError) } }

        // 一键重连：断开后回到本页直接恢复上次连接
        val last = state.lastConnected
        if (last != null && !state.connected && !state.connecting) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t("上次连接", "Last connection"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(last.label, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.reconnectLast() }) { Text(t("一键重连", "Reconnect")) }
                    }
                }
            }
        }

        item { EnvironmentCard(state, vm) }

        // 连接过程实时上屏：让「Java → libusb → USB 重置」的自动恢复看得见
        if (state.connecting || state.connectionLog.isNotEmpty()) {
            item { ConnectionProgressCard(state) }
        }

        // 设备信息摘要：连接成功后一屏看全关键参数
        if (state.deviceSummary.isNotEmpty()) {
            item { DeviceSummaryCard(state.deviceSummary) }
        }

        // 模式智能提示：扫到的设备里混入了 ADB 模式的目标机时，提醒去对的页
        val adbCount = state.devices.count { it.mode == "ADB" }
        if (adbCount > 0) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        t(
                        "检测到 $adbCount 台设备处于 ADB 模式 —— 在「连接」页只能刷 fastboot 模式的设备，ADB 操作请切到「ADB」页。",
                        "Detected $adbCount device(s) in ADB mode — this tab only flashes fastboot devices; use the ADB tab instead."
                    ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        item {
            Text(t("检测到的设备", "Detected devices"), style = MaterialTheme.typography.titleSmall)
        }

        if (state.devices.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(t("没有检测到疑似 fastboot 设备", "No fastboot devices found"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t(
                                "排查顺序：\n1. 目标机按「音量下 + 电源」进入，屏幕应显示 FASTBOOT\n2. OTG 转接头插本机，数据线插目标机（方向不能反）\n3. 一加 / OPPO 需要手动打开「OTG 连接」\n4. 换一根确定能传数据的线，很多线只供电\n5. 目标机电量保持 30% 以上",
                                "Checklist://n1. Boot target with Volume Down + Power; screen should show FASTBOOT\n2. Plug the OTG adapter into this phone, data cable into the target (direction matters)\n3. OnePlus/OPPO: enable \"OTG connection\" in settings\n4. Try a cable known to carry data; many are charge-only\n5. Keep target battery above 30%"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.devices) { item ->
                DeviceRow(item, state, vm)
            }
        }

        item {
            Button(
            shape = RoundedCornerShape(0.dp),
                onClick = { vm.refreshDevices() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(t("重新扫描", "Rescan")) }
        }

        if (state.diagnostics.isNotBlank()) {
            item { DiagnosticsCard(state.diagnostics) }
        }
    }
}

@Composable
private fun DeviceSummaryCard(summary: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(t("设备信息", "Device info"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            summary.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        "$label：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(110.dp)
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionProgressCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.connecting) t("连接中…", "Connecting…") else t("连接过程", "Connection log"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            state.connectionLog.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(item: DeviceItem, state: UiState, vm: FlashViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("模式", "Mode") + ": ${item.mode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.mode == "ADB") {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t("去 ADB 页连接", "Use the ADB tab"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
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
                    enabled = !state.connecting && !state.busy && !state.connected
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

@Composable
private fun EnvironmentCard(state: UiState, vm: FlashViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("运行环境", "Environment"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.runEnvironmentCheck() }) { Text(t("重新检测", "Re-check")) }
            }
            state.environment.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        "$key：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (key == "支持 USB Host" && value.startsWith("否")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}
