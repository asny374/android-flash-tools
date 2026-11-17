package com.dz.fastboototg.ui.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dz.fastboototg.AdbViewModel
import com.dz.fastboototg.ui.AdbUiState

@Composable
fun AdbToolsCard(state: AdbUiState, vm: AdbViewModel) {
    val log by vm.liveLog.collectAsStateWithLifecycle()
    val recording by vm.logRunning.collectAsStateWithLifecycle()
    val mirroring by vm.mirroring.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val fps by vm.mirrorFps.collectAsStateWithLifecycle()
    val actualFps by vm.actualMirrorFps.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var restartLogs by remember { mutableStateOf(false) }
    var fpsMenu by remember { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("time.android.com") }
    var expanded by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        if (it != null) vm.saveLogs(it)
    }
    LaunchedEffect(log, follow) { if (follow) scroll.scrollTo(scroll.maxValue) }
    val ready = state.connected && !state.busy && !state.connecting
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("修改目标机 NTP 配置") },
            text = { Text(if (action.isEmpty()) "删除自定义服务器并恢复系统默认。" else "将目标机 NTP 服务器设为 $action。此操作修改配置，不代表已经完成校时；部分系统需要重启或下一次网络校时才生效。") },
            confirmButton = { TextButton(onClick = { if (action.isEmpty()) vm.resetNtp() else vm.setNtp(action); confirm = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
    if (fullscreen && screen != null) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row {
                    TextButton(onClick = { fullscreen = false }) { Text("返回工具") }
                    TextButton(onClick = vm::stopMirror, enabled = mirroring) { Text("停止预览") }
                }
                Image(screen!!.asImageBitmap(), "目标手机画面", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
            }
        }
    }
    if (restartLogs) AlertDialog(onDismissRequest = { restartLogs = false }, title = { Text("开始新的日志采集？") },
        text = { Text("这会替换上一段日志。需要保留时请先另存为文件。") },
        confirmButton = { TextButton(onClick = { restartLogs = false; vm.startLogs() }) { Text("重新采集") } },
        dismissButton = { TextButton(onClick = { restartLogs = false }) { Text("取消") } })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ADB 快捷工具", style = MaterialTheme.typography.titleMedium)
            Text(if (state.connected) state.stage else "请先连接目标设备；已有日志仍可保存", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NTP 校时", "画面预览", "实时日志").forEachIndexed { index, title ->
                    FilterChip(selected = tool == index, onClick = { tool = index }, label = { Text(title) })
                }
            }
            if (tool == 0) {
            Text("NTP 时间服务器", style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = ready) { Text("选择预设服务器") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("time.android.com", "ntp.aliyun.com", "ntp.tencent.com", "time.cloudflare.com", "pool.ntp.org").forEach { server ->
                        DropdownMenuItem(text = { Text(server) }, onClick = { host = server; expanded = false })
                    }
                }
            }
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("服务器域名 / IPv4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { confirm = host.trim() }, enabled = ready && host.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("应用所选服务器") }
            OutlinedButton(onClick = vm::readNtp, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("读取当前配置（见下方输出）") }
            OutlinedButton(onClick = { confirm = "" }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("恢复系统默认") }
            Text("厂商可能限制修改。读取结果 null 表示使用系统默认；不是校时成功状态。", style = MaterialTheme.typography.bodySmall)
            }
            if (tool == 1) {
            Text("目标手机投屏 · 只读预览", style = MaterialTheme.typography.titleSmall)
            Text("截图预览的实际帧率受设备与连接速度限制；不含音频或触控。受保护内容可能显示黑屏。", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { fpsMenu = true }) { Text("目标帧率：$fps FPS ▾") }
                DropdownMenu(expanded = fpsMenu, onDismissRequest = { fpsMenu = false }) {
                    listOf(1, 2, 5, 10, 15, 30).forEach { rate ->
                        DropdownMenuItem(text = { Text("$rate FPS") }, onClick = { vm.setMirrorFps(rate); fpsMenu = false })
                    }
                }
            }
            if (mirroring) Text("实际约 %.1f FPS".format(actualFps), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (mirroring) vm.stopMirror() else vm.startMirror() }, enabled = mirroring || ready, modifier = Modifier.fillMaxWidth()) { Text(if (mirroring) "停止预览" else "开始预览") }
            screen?.let { Image(it.asImageBitmap(), "目标手机画面", modifier = Modifier.fillMaxWidth().height(360.dp), contentScale = ContentScale.Fit) }
            if (screen != null) OutlinedButton(onClick = { fullscreen = true }, modifier = Modifier.fillMaxWidth()) { Text("全屏查看") }
            if (mirroring && screen == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在获取第一帧…") }
            }
            if (tool == 2) {
            Text("实时 logcat", style = MaterialTheme.typography.titleSmall)
            Text("采集期间独占当前 ADB 连接；停止时保留正常的 ADB 连接。屏幕保留最近约 64 KB，保存包含本次完整采集（最多 32 MB）。开始新采集会替换旧日志。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (recording) vm.stopLogs() else if (log.isNotEmpty()) restartLogs = true else vm.startLogs() }, enabled = recording || ready, modifier = Modifier.fillMaxWidth()) { Text(if (recording) "停止采集" else "开始采集") }
            Row {
                Checkbox(checked = follow, onCheckedChange = { follow = it })
                Text("自动滚动（关闭后可滑动查看）", modifier = Modifier.padding(top = 12.dp))
            }
            SelectionContainer {
                Text(log.ifEmpty { "暂无日志" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().height(260.dp).verticalScroll(scroll).padding(8.dp))
            }
            OutlinedButton(onClick = { save.launch("logcat-${System.currentTimeMillis()}.txt") }, enabled = log.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("另存为日志文件") }
            }
        }
    }
}
