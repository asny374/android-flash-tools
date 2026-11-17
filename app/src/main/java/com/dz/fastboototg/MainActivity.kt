package com.dz.fastboototg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dz.fastboototg.core.L10n
import com.dz.fastboototg.core.Prefs
import com.dz.fastboototg.core.ThemeController
import com.dz.fastboototg.ui.AppScreen
import com.dz.fastboototg.ui.WizardScreen
import com.dz.fastboototg.ui.theme.AppTheme

/**
 * 薄壳 Activity：只负责主题、权限请求与 VM 装配。
 * 全局初始化（日志/崩溃捕获）在 [FastbootOtgApp]，界面骨架在 ui 包。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        val prefs = Prefs(this)
        setContent {
            val forceDark by ThemeController.dark.collectAsStateWithLifecycle()
            AppTheme(forceDark = forceDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 语言切换后 key 变化，整树重组，所有 L10n.t() 立即换文案
                    val lang by L10n.lang.collectAsStateWithLifecycle()
                    val wizardDone = remember { mutableStateOf(prefs.wizardDone) }
                    if (wizardDone.value) {
                        key(lang) {
                            val vm: FlashViewModel = viewModel()
                            val adbVm: AdbViewModel = viewModel()
                            AppScreen(vm, adbVm)
                        }
                    } else {
                        // 向导保存完偏好再创建主界面的 ViewModel，无需重建 Activity。
                        WizardScreen(prefs) {
                            wizardDone.value = true
                        }
                    }
                }
            }
        }
    }

    /** 长任务会挂常驻通知，Android 13+ 需要通知权限才看得到 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
