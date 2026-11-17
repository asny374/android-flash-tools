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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dz.fastboototg.FlashViewModel
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.ui.QuickCommand
import com.dz.fastboototg.ui.UiState

/** 快捷命令的双语标签 */
private fun QuickCommand.displayLabel(): String = when (this) {
    QuickCommand.REBOOT -> L10n.t("重启系统", "Reboot system")
    QuickCommand.BOOTLOADER -> L10n.t("重启到 bootloader", "Reboot to bootloader")
    QuickCommand.FASTBOOTD -> L10n.t("重启到 fastbootd", "Reboot to fastbootd")
    QuickCommand.RECOVERY -> L10n.t("重启到恢复模式", "Reboot to recovery")
    QuickCommand.VARS -> L10n.t("读取全部变量", "Read all variables")
    QuickCommand.SLOT -> L10n.t("查询当前槽位", "Current slot")
    QuickCommand.UNLOCK_STATE -> L10n.t("查询解锁状态", "Unlock state")
    QuickCommand.ERASE_BOOT -> L10n.t("擦除 boot 分区", "Erase boot (danger)")
}

// ---------------- 命令页 ----------------

@Composable
fun CommandTab(state: UiState, vm: FlashViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var scriptDialog by remember { mutableStateOf(false) }
    var newScriptName by remember { mutableStateOf("") }
    var newScriptBody by remember { mutableStateOf("") }

    fun t(zh: String, en: String) = L10n.t(zh, en)

    LaunchedEffect(state.terminal.size) {
        if (state.terminal.isNotEmpty()) {
            listState.animateScrollToItem(state.terminal.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("一键脚本", "Scripts"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(shape = RoundedCornerShape(0.dp), onClick = { scriptDialog = true }) { Text(t("新建", "New")) }
        }

        if (state.scripts.isEmpty()) {
            Text(
                t(
                    "还没有脚本。点「新建」把常用命令序列（每行一条）保存成卡片，一键按顺序执行。",
                    "No scripts yet. Tap \"New\" to save a command sequence (one per line) as a one-tap card."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.scripts.forEach { script ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(script.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${script.commands.size} ${t("步", "steps")} · ${script.commands.joinToString(" → ").take(60)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                            onClick = { vm.runScript(script) },
                            enabled = state.connected && !state.busy
                        ) { Text(t("运行", "Run")) }
                        TextButton(
            shape = RoundedCornerShape(0.dp),
                            onClick = { vm.deleteScript(script.name) },
                            enabled = !state.busy
                        ) { Text(t("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(t("常用命令", "Quick commands"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        // 全宽行：每行一个命令，标题 + 命令本体两行显示，永不截断
        QuickCommand.entries.forEach { cmd ->
            OutlinedButton(
            shape = RoundedCornerShape(0.dp),
                onClick = { vm.runQuickCommand(cmd) },
                enabled = state.connected && !state.busy,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        cmd.displayLabel(),
                        color = if (cmd.dangerous) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        cmd.command,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(t("终端", "Terminal"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(t("例如 getvar:all", "e.g. getvar:all")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
            shape = RoundedCornerShape(0.dp),
                onClick = {
                    vm.runTerminal(input)
                    input = ""
                },
                enabled = state.connected && !state.busy
            ) { Text(t("执行", "Run")) }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("输出", "Output"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.clearTerminal() }) { Text(t("清空", "Clear")) }
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            ) {
                if (state.terminal.isEmpty()) {
                    item {
                        Text(
                            t(
                                "还没有执行过命令。\n先在上面连接设备，再点快捷按钮或自己输入。",
                                "No commands yet.\nConnect a device above, then tap a quick command or type your own."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(state.terminal) { line ->
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

    // 新建脚本对话框：名字 + 每行一条命令
    if (scriptDialog) {
        AlertDialog(
            onDismissRequest = { scriptDialog = false },
            title = { Text(t("新建脚本", "New script")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newScriptName,
                        onValueChange = { newScriptName = it },
                        label = { Text(t("脚本名称", "Script name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newScriptBody,
                        onValueChange = { newScriptBody = it },
                        label = { Text(t("命令（每行一条）", "Commands (one per line)")) },
                        placeholder = { Text("getvar:unlocked\nreboot-bootloader") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = {
                        vm.saveScript(newScriptName, newScriptBody.lines())
                        newScriptName = ""
                        newScriptBody = ""
                        scriptDialog = false
                    },
                    enabled = newScriptName.isNotBlank() && newScriptBody.isNotBlank()
                ) { Text(t("保存", "Save")) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { scriptDialog = false }) { Text(t("取消", "Cancel")) }
            }
        )
    }
}
