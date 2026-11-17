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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dz.fastboototg.FlashViewModel
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.ui.UiState
import com.dz.fastboototg.ui.components.ErrorBanner

/** 分区大小格式化（对话框里显示用） */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 常用分区快捷选择 */
private val COMMON_PARTITIONS = listOf("boot", "recovery", "dtbo", "vbmeta", "super")

// ---------------- 刷写页 ----------------

private fun t(zh: String, en: String) = L10n.t(zh, en)

@Composable
fun FlashTab(state: UiState, vm: FlashViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareFlash(uri)
    }
    val otaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareOta(uri)
    }
    val queuePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.enqueueImages(uris.toList())
    }
    var otaConfirmPartition by remember { mutableStateOf<String?>(null) }
    var disableVerity by remember { mutableStateOf(false) }
    var disableVerification by remember { mutableStateOf(false) }
    var queueConfirm by remember { mutableStateOf(false) }

    // 刷写前二次确认：分区名 + 镜像信息，杜绝手滑刷错分区
    state.pendingFlash?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cancelFlash() },
            title = { Text(t("确认刷写", "Confirm flash")) },
            text = {
                Column {
                    Text(t("目标分区", "Target partition") + ":", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        state.partition.ifBlank { t("（空）", "(empty)") },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(t("镜像文件", "Image file") + ":", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pending.fileName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${pending.sizeText} · ${if (pending.isSparse) t("sparse 格式", "sparse") else t("raw 原始镜像", "raw")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("刷错分区可能导致设备无法开机，请再次核对分区名。", "Flashing the wrong partition can brick the device — double-check the name."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    // vbmeta 专属：禁用 avb 验证开关（刷 GSI / 关加密校验场景）
                    if (state.partition.equals("vbmeta", true)) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = disableVerity,
                                onCheckedChange = { disableVerity = it }
                            )
                            Text(t("禁用 verity（hashtree）", "Disable verity (hashtree)"), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = disableVerification,
                                onCheckedChange = { disableVerification = it }
                            )
                            Text(t("禁用 verification", "Disable verification"), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            t("需 bootloader 已解锁；会改写 vbmeta 头部 flags，原签名将失效。", "Requires unlocked bootloader; rewrites vbmeta header flags, invalidating its signature."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = {
                        vm.confirmFlash(disableVerity, disableVerification)
                        disableVerity = false
                        disableVerification = false
                    }
                ) { Text(t("开始刷写", "Flash now")) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.cancelFlash() }) { Text(t("取消", "Cancel")) }
            }
        )
    }

    // OTA 分区选择对话框：列出解析出的分区，点一个进入二次确认
    state.pendingOta?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cancelOta() },
            title = { Text(t("选择要刷写的分区", "Pick a partition")) },
            text = {
                LazyColumn(Modifier.height(320.dp)) {
                    items(pending.partitions) { partition ->
                        TextButton(
            shape = RoundedCornerShape(0.dp),
                            onClick = { otaConfirmPartition = partition.name },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(partition.name)
                                Text(
                                    formatSize(partition.size),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.cancelOta() }) { Text(t("取消", "Cancel")) }
            }
        )
    }

    // OTA 二次确认
    otaConfirmPartition?.let { name ->
        val info = state.pendingOta?.partitions?.firstOrNull { it.name == name }
        AlertDialog(
            onDismissRequest = { otaConfirmPartition = null },
            title = { Text(t("确认刷写 OTA 分区", "Confirm OTA flash")) },
            text = {
                Column {
                    Text(t("目标分区", "Target partition") + ":", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    info?.let {
                        Text(
                            "数据量：${formatSize(it.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("OTA 流式写入不可中断，请保持电量与连接稳定。", "OTA streaming write cannot be interrupted — keep power and connection stable."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = {
                        otaConfirmPartition = null
                        vm.confirmOtaPartition(name)
                    }
                ) { Text(t("开始刷写", "Flash now")) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { otaConfirmPartition = null }) { Text(t("取消", "Cancel")) }
            }
        )
    }

    // 队列刷写二次确认
    if (queueConfirm) {
        AlertDialog(
            onDismissRequest = { queueConfirm = false },
            title = { Text(t("确认队列刷写", "Confirm queue flash")) },
            text = {
                Column {
                    state.flashQueue.forEach { item ->
                        Text(
                            "${item.partition}  ←  ${item.fileName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("按顺序执行；任一步失败即中止。已刷入的部分无法回滚。", "Runs in order; aborts on first failure. Already-flashed parts cannot be rolled back."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = {
                        queueConfirm = false
                        vm.flashQueue()
                    }
                ) { Text(t("开始刷写", "Flash now")) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { queueConfirm = false }) { Text(t("取消", "Cancel")) }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.error?.let { item { ErrorBanner(it, vm::dismissError) } }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (state.connected) state.deviceLabel else t("未连接设备", "No device connected"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    val unlocked = state.variables["unlocked"]
                    Text(
                        t("解锁状态", "Unlock") + ": " + when {
                            unlocked.equals("yes", true) -> t("已解锁，可以刷写", "unlocked — flashing allowed")
                            unlocked.equals("no", true) -> t("未解锁，禁止刷写", "locked — flashing forbidden")
                            else -> t("未知", "unknown")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unlocked.equals("yes", true)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    state.variables["current-slot"]?.let {
                        Text(t("当前槽位", "Slot") + ": $it", style = MaterialTheme.typography.bodySmall)
                    }
                    state.variables["is-userspace"]?.let {
                        Text(
                            t("fastbootd", "fastbootd") + ": " + if (it.equals("yes", true)) t("是（可刷动态分区）", "yes (dynamic partitions flashable)") else t("否", "no"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 分区输入 + 常用分区快捷选择
        item {
            OutlinedTextField(
                value = state.partition,
                onValueChange = vm::setPartition,
                label = { Text(t("目标分区", "Target partition")) },
                singleLine = true,
                supportingText = { Text(t("常见值：boot / recovery / vbmeta / super", "Common: boot / recovery / vbmeta / super")) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COMMON_PARTITIONS.take(3).forEach { partition ->
                    FilterChip(
            shape = RoundedCornerShape(4.dp),
                        selected = state.partition == partition,
                        onClick = { vm.setPartition(partition) },
                        label = { Text(partition) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COMMON_PARTITIONS.drop(3).forEach { partition ->
                    FilterChip(
            shape = RoundedCornerShape(4.dp),
                        selected = state.partition == partition,
                        onClick = { vm.setPartition(partition) },
                        label = { Text(partition) }
                    )
                }
            }
        }

        item {
            Button(
            shape = RoundedCornerShape(0.dp),
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = state.connected && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(t("选择镜像并刷写", "Pick image and flash")) }
        }

        item {
            OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                onClick = { otaPicker.launch(arrayOf("*/*")) },
                enabled = state.connected && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(t("刷写 OTA 整包（payload.bin / zip）", "Flash OTA package (payload.bin / zip)")) }
        }

        // 多镜像队列
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(t("多镜像队列", "Image queue"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                            onClick = { queuePicker.launch(arrayOf("*/*")) },
                            enabled = !state.busy
                        ) { Text(t("添加", "Add")) }
                    }
                    if (state.flashQueue.isEmpty()) {
                        Text(
                            t("一次选择多个镜像（如 boot.img + vbmeta.img），文件名即分区名，可在下方修改，按顺序依次刷写。", "Pick multiple images (e.g. boot.img + vbmeta.img). File name becomes the partition name; edit below; flashed in order."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.flashQueue.forEach { item ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                OutlinedTextField(
                                    value = item.partition,
                                    onValueChange = { vm.setQueuePartition(item.fileName, it) },
                                    label = { Text(t("分区", "Partition")) },
                                    singleLine = true,
                                    modifier = Modifier.width(110.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
            shape = RoundedCornerShape(0.dp),
                                    onClick = { vm.removeQueueItem(item.fileName) },
                                    enabled = !state.busy
                                ) { Text(t("移除", "Remove"), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
            shape = RoundedCornerShape(0.dp),
                                onClick = { queueConfirm = true },
                                enabled = state.connected && !state.busy &&
                                    state.flashQueue.none { it.partition.isBlank() },
                                modifier = Modifier.weight(1f)
                            ) { Text(t("开始刷写队列（${state.flashQueue.size}）", "Flash queue (${state.flashQueue.size})")) }
                            TextButton(
            shape = RoundedCornerShape(0.dp),
                                onClick = { vm.clearQueue() },
                                enabled = !state.busy
                            ) { Text(t("清空", "Clear")) }
                        }
                    }
                }
            }
        }

        if (state.busy || state.progress > 0f) {
            item {
                Column {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(state.progress * 100).toInt()}% · ${state.stage}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    state.progressInfo?.let { info ->
                        Text(
                            info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 刷写完成：提供重启入口，不用再去命令页敲 reboot
        if (!state.busy && state.connected && state.stage.endsWith(t("刷写完成", "flash complete"))) {
            item {
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = { vm.rebootDevice() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(t("重启设备", "Reboot device")) }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(t("刷写前请注意", "Before you flash"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t(
                            "· 分区名写错会导致刷到别的地方，务必确认\n· bootloader 未解锁时任何写入都会失败\n· 刷写过程不要拔线，中断可能导致无法开机\n· 建议先只刷 boot，确认能开机再动其他分区",
                            "· A wrong partition name flashes to the wrong place — double-check\n· Writes always fail while the bootloader is locked\n· Never unplug during a flash; interruptions can brick the device\n· Flash boot first, verify it boots, then touch other partitions"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
