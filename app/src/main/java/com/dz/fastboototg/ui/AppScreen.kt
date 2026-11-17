package com.dz.fastboototg.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dz.fastboototg.AdbViewModel
import com.dz.fastboototg.FlashViewModel
import com.dz.fastboototg.core.log.AppLogger
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.ui.tabs.AdbTab
import com.dz.fastboototg.ui.tabs.CommandTab
import com.dz.fastboototg.ui.tabs.ConnectTab
import com.dz.fastboototg.ui.tabs.FlashTab
import com.dz.fastboototg.ui.tabs.LogTab
import com.dz.fastboototg.ui.tabs.SettingsTab

/** 主界面骨架：顶栏 + 页签导航。 */
/** 页签标题随语言实时切换（AppScreen 在 key(lang) 内重组） */
private fun tabTitles() = listOf(
    L10n.t("连接", "Connect"),
    L10n.t("刷写", "Flash"),
    L10n.t("命令", "Shell"),
    "ADB",
    L10n.t("日志", "Logs"),
    L10n.t("设置", "Settings")
)

private const val TAB_CONNECT = 0
private const val TAB_FLASH = 1
private const val TAB_COMMAND = 2
private const val TAB_ADB = 3
private const val TAB_LOG = 4
private const val TAB_SETTINGS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: FlashViewModel, adbVm: AdbViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(hostContext: Context, intent: Intent) {
                val attached = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
                AppLogger.i(if (attached) "检测到 USB 设备接入" else "检测到 USB 设备拔出")
                vm.refreshDevices()
                adbVm.refreshDevices()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("android-flash-tools") },
                    actions = {
                        Text(
                            text = state.stage,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
                TabRow(selectedTabIndex = tab) {
                    tabTitles().forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (tab) {
                TAB_CONNECT -> ConnectTab(state, vm)
                TAB_FLASH -> FlashTab(state, vm)
                TAB_COMMAND -> CommandTab(state, vm)
                TAB_ADB -> AdbTab(adbVm.state.collectAsStateWithLifecycle().value, adbVm)
                TAB_LOG -> LogTab(state, vm)
                else -> SettingsTab(state, vm)
            }
        }
    }
}
