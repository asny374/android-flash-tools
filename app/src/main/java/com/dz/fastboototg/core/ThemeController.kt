package com.dz.fastboototg.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题控制器：设置页改主题时更新这里，AppTheme 立即响应。
 * dark = true 强制深色 / false 强制浅色 / null 跟随系统。
 */
object ThemeController {

    private lateinit var prefs: Prefs

    private val _dark = MutableStateFlow<Boolean?>(null)
    val dark: StateFlow<Boolean?> = _dark.asStateFlow()

    /** 应用启动时从偏好恢复（在 Application.onCreate 里调用） */
    fun init(appPrefs: Prefs) {
        prefs = appPrefs
        _dark.value = when (prefs.themeMode) {
            "dark" -> true
            "light" -> false
            else -> null
        }
    }

    fun setMode(mode: String) {
        prefs.themeMode = mode
        _dark.value = when (mode) {
            "dark" -> true
            "light" -> false
            else -> null
        }
    }

    /** 当前模式字符串（设置页选中态用） */
    fun currentMode(): String = when (_dark.value) {
        true -> "dark"
        false -> "light"
        null -> "auto"
    }
}
