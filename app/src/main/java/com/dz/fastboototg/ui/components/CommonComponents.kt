package com.dz.fastboototg.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.dz.fastboototg.core.AppError
import com.dz.fastboototg.core.L10n

/** 错误横幅：标题 + 排查建议 + 复制/关闭，fastboot 与 ADB 两个页签共用 */
@Composable
fun ErrorBanner(error: AppError?, onDismiss: () -> Unit) {
    val current = error ?: return
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                current.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            current.hint?.let { hint ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "排查建议：$hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Row {
                // 一键复制完整错误，方便发给他人排查
                TextButton(shape = RoundedCornerShape(0.dp), onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            buildString {
                                append(current.title)
                                current.hint?.let { append("\n${L10n.t("排查建议", "Hint")}: $it") }
                            }
                        )
                    )
                }) { Text(L10n.t("复制详情", "Copy")) }
                TextButton(shape = RoundedCornerShape(0.dp), onClick = onDismiss) { Text(L10n.t("知道了", "OK")) }
            }
        }
    }
}

/** 诊断信息卡（等宽字体展示原始 USB 信息） */
@Composable
fun DiagnosticsCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("设备诊断", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
