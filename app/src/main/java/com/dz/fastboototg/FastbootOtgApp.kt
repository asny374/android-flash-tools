package com.dz.fastboototg

import android.app.Application
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.Prefs
import com.dz.fastboototg.core.ThemeController
import com.dz.fastboototg.core.log.AppLogger

/**
 * 应用入口：统一做全局初始化。
 *
 * 之前日志初始化和崩溃捕获散在 Activity 与 ViewModel 两处，
 * 时序上互相猜，现在收敛到 Application，进程启动即生效。
 */
class FastbootOtgApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        AppLogger.init(this)
        ThemeController.init(prefs)
        L10n.init(prefs)
        installCrashHandler()
        AppLogger.i("应用启动")
    }

    /** 捕获未处理异常，先落日志再交给系统，避免「闪退后什么都没有」 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLogger.e("应用崩溃：${throwable.javaClass.simpleName} - ${throwable.message}")
                throwable.stackTrace.take(25).forEach { AppLogger.e("    at $it") }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
