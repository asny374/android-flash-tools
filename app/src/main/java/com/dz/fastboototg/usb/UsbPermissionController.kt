package com.dz.fastboototg.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dz.fastboototg.core.log.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * USB 设备授权。
 *
 * 系统要求每个 USB 设备在使用前经过用户授权，授权结果通过广播返回。
 * 这里把「请求 → 等广播 → 给结论」封装成一个挂起函数，调用方写起来就是一行 if。
 */
class UsbPermissionController(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    suspend fun request(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val action = "${context.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    AppLogger.i("USB 授权：${if (granted) "已允许" else "被拒绝"}")
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            // 必须是 MUTABLE：系统要往 Intent 里塞 EXTRA_DEVICE，
            // 用 IMMUTABLE 会表现成「授权弹窗点了允许但依然连不上」
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName), flags
            )
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            try { usbManager.requestPermission(device, pendingIntent) } catch (t: Exception) {
                runCatching { context.unregisterReceiver(receiver) }; throw t
            }
        }
    }
}
