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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dz.fastboototg.FlashViewModel
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.core.log.LogEntry
import com.dz.fastboototg.core.log.LogLevel
import com.dz.fastboototg.ui.UiState

// ---------------- 日志页 ----------------

private fun t(zh: String, en: String) = L10n.t(zh, en)

@Composable
fun LogTab(state: UiState, vm: FlashViewModel) {
    val entries by AppLogger.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var query by remember { mutableStateOf("") }

    // 过滤 + 搜索后的条目；自动滚动跟随过滤后的列表
    val filtered = remember(entries, levelFilter, query) {
        entries.filter { entry ->
            (levelFilter == null || entry.level == levelFilter) &&
                (query.isBlank() || entry.message.contains(query, ignoreCase = true))
        }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("详细日志", "Detailed log"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    t("开启后会记录每一条 USB 收发，用于排查连接问题", "Logs every USB transfer — invaluable for connection issues"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.detailedLog, onCheckedChange = vm::setDetailedLog)
        }

        Spacer(Modifier.height(10.dp))

        // 级别过滤
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
            shape = RoundedCornerShape(4.dp),
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text(t("全部", "All")) }
            )
            listOf(LogLevel.WARN, LogLevel.ERROR).forEach { level ->
                FilterChip(
            shape = RoundedCornerShape(4.dp),
                    selected = levelFilter == level,
                    onClick = { levelFilter = if (levelFilter == level) null else level },
                    label = { Text(level.label) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 内容搜索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(t("搜索日志内容…", "Search logs…")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("文件上限", "File size limit"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp)
            )
            listOf(1, 2, 5, 10).forEach { mb ->
                FilterChip(
            shape = RoundedCornerShape(4.dp),
                    selected = state.maxLogMb == mb,
                    onClick = { vm.setMaxLogSize(mb) },
                    label = { Text("${mb}MB") },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            t("超过上限后自动把旧日志转存为备份并清空当前文件，不会无限占用空间", "When exceeded, old logs rotate to a backup; space stays bounded"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(shape = RoundedCornerShape(0.dp), onClick = { vm.exportLog() }) { Text(t("导出 zip", "Export zip")) }
            OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { vm.clearLog() }) { Text(t("清空日志", "Clear logs")) }
        }

        state.exportedFile?.let { path ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("已导出", "Exported") + ": $path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { vm.clearExportedFile() }) { Text(t("关闭", "Close")) }
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (entries.isEmpty()) t("暂无日志", "No logs yet")
                            else t("没有匹配的日志（试试调整过滤条件）", "No matching entries (adjust the filters)"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(filtered) { entry ->
                    Text(
                        text = entry.format(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = when (entry.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}
