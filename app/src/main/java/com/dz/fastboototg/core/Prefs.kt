package com.dz.fastboototg.core

import android.content.Context

/**
 * 轻量本地偏好：ADB 路径记忆、shell 命令历史。
 * 只存使用习惯，不存敏感数据。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var adbPushPath: String
        get() = sp.getString(KEY_PUSH_PATH, "/sdcard/") ?: "/sdcard/"
        set(value) = sp.edit().putString(KEY_PUSH_PATH, value).apply()

    var adbPullPath: String
        get() = sp.getString(KEY_PULL_PATH, "/sdcard/") ?: "/sdcard/"
        set(value) = sp.edit().putString(KEY_PULL_PATH, value).apply()

    /** shell 命令历史，最近在上，最多保留 [MAX_HISTORY] 条 */
    fun adbCommandHistory(): List<String> =
        sp.getString(KEY_HISTORY, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberAdbCommand(command: String) {
        if (command.isBlank()) return
        val next = (listOf(command) + adbCommandHistory().filter { it != command })
            .take(MAX_HISTORY)
        sp.edit().putString(KEY_HISTORY, next.joinToString("\n")).apply()
    }

    /** fastboot 默认分区 */
    var defaultPartition: String
        get() = sp.getString(KEY_DEFAULT_PARTITION, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEFAULT_PARTITION, value).apply()

    /** 详细日志是否默认开启 */
    var defaultDetailedLog: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_DETAILED, false)
        set(value) = sp.edit().putBoolean(KEY_DEFAULT_DETAILED, value).apply()

    /**
     * 主题模式："auto" 跟随系统 / "light" / "dark"
     */
    var themeMode: String
        get() = sp.getString(KEY_THEME, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    /** 日志文件上限（MB），设置一次永久记住 */
    var maxLogMb: Int
        get() = sp.getInt(KEY_MAX_LOG_MB, 2)
        set(value) = sp.edit().putInt(KEY_MAX_LOG_MB, value.coerceIn(1, 10)).apply()

    /** 界面语言："auto" 跟随系统 / "zh" / "en" */
    var appLang: String
        get() = sp.getString(KEY_LANG, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_LANG, value).apply()

    /** 首次启动向导是否已完成 */
    var wizardDone: Boolean
        get() = sp.getBoolean(KEY_WIZARD_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_WIZARD_DONE, value).apply()

    companion object {
        private const val KEY_PUSH_PATH = "adb_push_path"
        private const val KEY_PULL_PATH = "adb_pull_path"
        private const val KEY_HISTORY = "adb_cmd_history"
        private const val MAX_HISTORY = 10
        private const val KEY_DEFAULT_PARTITION = "default_partition"
        private const val KEY_DEFAULT_DETAILED = "default_detailed_log"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MAX_LOG_MB = "max_log_mb"
        private const val KEY_LANG = "app_lang"
        private const val KEY_WIZARD_DONE = "wizard_done"
    }
}
