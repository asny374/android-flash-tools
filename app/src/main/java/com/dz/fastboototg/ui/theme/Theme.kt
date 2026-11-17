package com.dz.fastboototg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * @param forceDark true 强制深色 / false 强制浅色 / null 跟随系统（设置页可改）
 */
@Composable
fun AppTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        // 全局统一 4dp 圆角：按钮 / 输入框 / 卡片 / 对话框 / chips 全部同一种方形观感
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(4.dp),
            extraLarge = RoundedCornerShape(4.dp)
        ),
        content = content
    )
}
