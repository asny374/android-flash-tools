package com.dz.fastboototg.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLang { FOLLOW_SYSTEM, ZH, EN }

/**
 * 应用内双语（中文/English）文案中心。
 *
 * 设计取舍：不走 values-en 资源 —— 全项目字符串都在 Kotlin 里，
 * 集中到 t(zh, en) 一个入口，改文案、审翻译、切语言都只看这一个文件；
 * 切语言后由 AppScreen 的 key(lang) 强制整树重组，即时生效。
 */
object L10n {

    private lateinit var prefs: Prefs

    private val _lang = MutableStateFlow(AppLang.FOLLOW_SYSTEM)
    val lang: StateFlow<AppLang> = _lang.asStateFlow()

    /** 应用启动时从偏好恢复（Application.onCreate 里调用） */
    fun init(appPrefs: Prefs) {
        prefs = appPrefs
        _lang.value = when (prefs.appLang) {
            "zh" -> AppLang.ZH
            "en" -> AppLang.EN
            else -> AppLang.FOLLOW_SYSTEM
        }
    }

    fun setLang(l: AppLang) {
        prefs.appLang = when (l) {
            AppLang.ZH -> "zh"
            AppLang.EN -> "en"
            else -> "auto"
        }
        _lang.value = l
    }

    /** 实际生效语言：跟随系统时按系统语言判断 */
    fun effective(): AppLang = when (_lang.value) {
        AppLang.FOLLOW_SYSTEM ->
            if (Locale.getDefault().language.startsWith("en")) AppLang.EN else AppLang.ZH
        else -> _lang.value
    }

    fun isEnglish(): Boolean = effective() == AppLang.EN

    /** 取当前语言的文案。所有界面字符串统一走这里。 */
    fun t(zh: String, en: String): String = if (isEnglish()) en else zh
}
