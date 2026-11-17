package com.dz.fastboototg.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dz.fastboototg.core.log.AppLogger

/**
 * 刷写期间的前台服务。
 *
 * Android 12 起后台进程随时可能被回收，一个 4GB 的 super 镜像要写十几分钟，
 * 没有前台服务的话切到别的应用就可能被杀，表现为「刷到一半没了」。
 * 这里只负责挂一个常驻通知把进程优先级提上去，实际刷写仍在 ViewModel 的协程里跑。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "任务进行中，请勿断开数据线"
        startForeground(NOTIFICATION_ID, build(text))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("android-flash-tools")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "刷写任务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "刷写期间的常驻通知" }
            runCatching {
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "fastboot_task"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, KeepAliveService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.d("前台服务已启动：$text")
            }.onFailure {
                AppLogger.w("启动前台服务失败：${it.message}")
            }
        }

        /** 任务进行中刷新通知文案（百分比 / ETA）；无权限或未启动时静默跳过 */
        fun update(context: Context, text: String) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                androidx.core.app.NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                        .setContentTitle("android-flash-tools")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build())
            }.onFailure {
                AppLogger.d("刷新通知失败：${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
            AppLogger.d("前台服务已停止")
        }
    }
}
