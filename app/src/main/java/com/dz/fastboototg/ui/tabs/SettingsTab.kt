package com.dz.fastboototg.ui.tabs

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dz.fastboototg.FlashViewModel
import com.dz.fastboototg.core.AppLang
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.ThemeController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dz.fastboototg.ui.UiState

// ---------------- 设置页 ----------------

private fun t(zh: String, en: String) = L10n.t(zh, en)

@Composable
fun SettingsTab(state: UiState, vm: FlashViewModel) {
    val dark by ThemeController.dark.collectAsStateWithLifecycle()
    var defaultPartition by remember { mutableStateOf(state.partition) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // 语言（即时生效）
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("语言 / Language", "Language / 语言"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppLang.FOLLOW_SYSTEM to t("跟随系统", "System"),
                        AppLang.ZH to "中文",
                        AppLang.EN to "English"
                    ).forEach { (langMode, label) ->
                        FilterChip(
            shape = RoundedCornerShape(4.dp),
                            selected = L10n.lang.value == langMode,
                            onClick = { L10n.setLang(langMode) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 主题
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("主题", "Theme"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to t("跟随系统", "System"), "light" to t("浅色", "Light"), "dark" to t("深色", "Dark")).forEach { (mode, label) ->
                        FilterChip(
            shape = RoundedCornerShape(4.dp),
                            selected = when (mode) {
                                "dark" -> dark == true
                                "light" -> dark == false
                                else -> dark == null
                            },
                            onClick = { ThemeController.setMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 默认分区
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("刷写默认分区", "Default flash partition"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    t("连接后刷写页自动填入；对 vbmeta 补丁等高频操作省一步输入。", "Pre-fills the flash tab after connecting — handy for frequent vbmeta patches."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = defaultPartition,
                        onValueChange = { defaultPartition = it },
                        label = { Text(t("分区名", "Partition")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.setDefaultPartition(defaultPartition) }) {
                        Text(t("保存", "Save"))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 详细日志默认值
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("详细日志默认开启", "Detailed logging by default"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        t("每次启动自动记录每条 USB 收发，排障更省事，日志量也随之增大", "Log every USB transfer from startup — faster debugging, bigger logs"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.detailedLog,
                    onCheckedChange = { vm.setDefaultDetailedLog(it) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("关于", "About"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    state.environment.entries.joinToString("\n") { "${it.key}：${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
