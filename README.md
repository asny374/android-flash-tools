# android-flash-tools

Android 手机通过 USB OTG 管理另一台 Android 设备的实验性工具。主机支持 Android 11+、ARM64、USB Host；目标机需允许 USB 调试，fastboot 刷写需解锁 bootloader。

## 功能

- fastboot 变量查询、单镜像与队列刷写、vbmeta 标志补丁、脚本。
- USB ADB 与经典 TCP/IP ADB：shell、文件推送/拉取、重启。
- NTP 服务器预设与自定义设置，读取与恢复默认。
- 目标画面截图预览，全屏查看，1/2/5/10/15/30 FPS 目标档位；非视频流，无音频与触控，实际速度取决于设备。
- 实时 logcat，自动滚动开关、文本保存；屏幕约 64 KB，单次文件最多 32 MB。
- 可滚动设置页、主题、日志导出。

## 当前限制

这是实验版本，构建通过不等于真机兼容性已验证。请使用可恢复的测试设备。

- 镜像必须不超过设备 max-download-size 与 64 MiB 中的较小值；不支持大镜像 resparse，超限在写入前拒绝。
- OTA 仅支持顺序覆盖的 REPLACE/REPLACE_BZ/ZERO 小分区；不是完整 OTA 更新引擎，不支持动态分区重建、增量更新或完整包签名验证。
- 无线仅支持经典 adb tcpip，不支持 TLS 配对码。
- 预览/日志占用当前 ADB 会话。正常停止保留连接，设备失联或协议错误时仍可能需要重连。
- 新增工具界面主要为中文。受保护画面可能为黑屏。

## 构建

JDK 21，Android SDK Platform 35，Build Tools 35.0.0，NDK 30.0.16248370，CMake 3.22.1。Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21。

在 Android Studio SDK Manager 安装依赖，设置 JAVA_HOME 和 ANDROID_HOME，或在未跟踪的 local.properties 中设置 sdk.dir。仓库不含个人 JDK 路径、代理和签名密码。

```sh
# Linux/macOS
sh ./gradlew assembleDebug testDebugUnitTest lintDebug
# Windows PowerShell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK 位于 app/build/outputs/apk/debug/app-debug.apk。依赖下载需要网络；如需代理，请配置个人 ~/.gradle/gradle.properties，不要提交代理到仓库。

## 签名

Debug 自动使用开发签名。Release 未配置时为未签名 APK。正式发布请自行创建并妥善保存密钥，将 release.storeFile、release.storePassword、release.keyAlias、release.keyPassword 放在 local.properties，再执行 assembleRelease。不要提交密钥或密码。

## GitHub

上传本目录的内容（包括 .github 和 gradle/wrapper），不要只上传 ZIP。推送或 PR 后 Actions 会构建、测试、Lint，并保存 Debug APK 与报告。云端流程尚待首次推送验证，不会自动发布 Release。

## 代码结构

app/src/main/java/com/dz/fastboototg 下：ui 为 Compose 页面，usb 为 fastboot/ADB 协议，flash 为镜像校验与刷写，core 为状态与日志。app/src/main/cpp 为 JNI 和 libusb；app/src/test 为 JVM 回归测试。

## 许可证

项目自有代码暂未指定许可证，不应视为已授予开源使用许可。第三方代码按其原有许可证保留，见 THIRD_PARTY_NOTICES.md。发布者需要在选择项目许可证前核实代码来源与分发要求。

