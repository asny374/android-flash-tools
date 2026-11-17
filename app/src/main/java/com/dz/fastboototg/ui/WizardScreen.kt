package com.dz.fastboototg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dz.fastboototg.core.AppLang
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.Prefs
import com.dz.fastboototg.core.ThemeController
import com.dz.fastboototg.core.log.AppLogger

/**
 * 首次启动向导：语言 → 外观与日志 → 完成。
 * 三步走完写 Prefs.wizardDone，之后不再出现；所有选项在设置页都能改。
 */
@Composable
fun WizardScreen(prefs: Prefs, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    // 语言选择即时生效：改了 L10n 之后整棵树重组，向导自己的文案也跟着切
    val lang by L10n.lang.collectAsStateWithLifecycle()
    val dark by ThemeController.dark.collectAsStateWithLifecycle()
    var defaultPartition by rememberSaveable { mutableStateOf(prefs.defaultPartition.ifBlank { "boot" }) }
    var detailedLog by rememberSaveable { mutableStateOf(prefs.defaultDetailedLog) }
    var maxLogMb by rememberSaveable { mutableIntStateOf(prefs.maxLogMb) }

    fun t(zh: String, en: String) = L10n.t(zh, en)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            t("欢迎使用 android-flash-tools", "Welcome to android-flash-tools"),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(6.dp))
        Text(
            t(
                "一部手机当主机，通过 OTG 线给另一部手机刷 fastboot / 调 ADB。先花一分钟完成初始设置。",
                "Use one phone as the USB host to flash fastboot / run ADB on another phone over OTG. Take a minute for initial setup."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        when (step) {
            // 第 1 步：语言
            0 -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            t("选择界面语言 / Choose language", "Choose language / 选择界面语言"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
            shape = RoundedCornerShape(4.dp),
                                selected = L10n.effective() == AppLang.ZH,
                                onClick = { L10n.setLang(AppLang.ZH) },
                                label = { Text("中文") }
                            )
                            FilterChip(
            shape = RoundedCornerShape(4.dp),
                                selected = L10n.effective() == AppLang.EN,
                                onClick = { L10n.setLang(AppLang.EN) },
                                label = { Text("English") }
                            )
                            FilterChip(
            shape = RoundedCornerShape(4.dp),
                                selected = lang == AppLang.FOLLOW_SYSTEM,
                                onClick = { L10n.setLang(AppLang.FOLLOW_SYSTEM) },
                                label = { Text(t("跟随系统", "System")) }
                            )
                        }
                    }
                }
            }

            // 第 2 步：外观与日志
            1 -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("外观", "Appearance"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("日志", "Logging"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t("默认开启详细日志", "Enable detailed logging by default"),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    t("记录每一条 USB 收发，排障更快，日志量更大", "Logs every USB transfer; faster debugging, bigger logs"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = detailedLog, onCheckedChange = { detailedLog = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t("日志文件上限", "Log file size limit"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 5, 10).forEach { mb ->
                                FilterChip(
            shape = RoundedCornerShape(4.dp),
                                    selected = maxLogMb == mb,
                                    onClick = { maxLogMb = mb },
                                    label = { Text("${mb}MB") }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("默认刷写分区", "Default flash partition"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = defaultPartition,
                            onValueChange = { defaultPartition = it },
                            label = { Text(t("分区名", "Partition")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 第 3 步：完成
            else -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("一切就绪", "You're all set"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t(
                                "使用流程：目标机进 fastboot → OTG 线连接 → 连接页点连接 → 刷写页选镜像。\n\n这些选项随时可以在「设置」页修改。目标机 bootloader 必须已解锁才能刷写。",
                                "Flow: target into fastboot → connect OTG cable → Connect tab → Flash tab to pick images.\n\nEverything can be changed later in Settings. Target bootloader must be unlocked for flashing."
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                t("第 ${step + 1} 步，共 3 步", "Step ${step + 1} of 3"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) {
                    OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { step-- }) { Text(t("上一步", "Back")) }
                }
                Button(
            shape = RoundedCornerShape(0.dp),
                    onClick = {
                        if (step < 2) {
                            step++
                        } else {
                            prefs.defaultPartition = defaultPartition.trim()
                            prefs.defaultDetailedLog = detailedLog
                            prefs.maxLogMb = maxLogMb
                            AppLogger.setMaxSizeMb(maxLogMb)
                            AppLogger.setDetailed(detailedLog)
                            prefs.wizardDone = true
                            onDone()
                        }
                    }
                ) {
                    Text(if (step < 2) t("下一步", "Next") else t("开始使用", "Get started"))
                }
            }
        }
    }
}
