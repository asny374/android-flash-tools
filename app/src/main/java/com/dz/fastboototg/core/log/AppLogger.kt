package com.dz.fastboototg.core.log

import android.content.Context
import android.util.Log
import com.dz.fastboototg.core.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LogLevel(val label: String, val priority: Int) {
    DEBUG("调试", 0),
    INFO("信息", 1),
    WARN("警告", 2),
    ERROR("错误", 3)
}

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val message: String
) {
    fun format(): String = "[$time][${level.label}] $message"
}

/**
 * 全局日志。
 *
 * 设计要点：
 * - 双通道：内存环形缓冲（给界面看）+ 文件追加（给导出看）
 * - 分级：DEBUG 只在「详细日志」开关打开时记录，避免刷屏
 * - 轮转：文件超过上限就改名为 app.log.1，只保留一份备份，不会无限膨胀
 * - 导出：打包成 zip，含日志 + 环境信息，方便贴给别人排查
 */
object AppLogger {

    private const val TAG = "FastbootOtg"
    private const val LOG_FILE_NAME = "app.log"

    private var initialized = false
    private var logDir: File? = null
    private var logFile: File? = null
    private var maxBytes: Long = AppConfig.LOG_DEFAULT_MAX_MB * 1024L * 1024L

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _detailed = MutableStateFlow(false)
    val detailed: StateFlow<Boolean> = _detailed.asStateFlow()

    private val _maxSizeMb = MutableStateFlow(AppConfig.LOG_DEFAULT_MAX_MB)
    val maxSizeMb: StateFlow<Int> = _maxSizeMb.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        logDir = dir
        logFile = File(dir, LOG_FILE_NAME)
        initialized = true
        append(LogEntry(now(), LogLevel.INFO, "日志系统启动"))
    }

    fun setDetailed(enabled: Boolean) {
        _detailed.value = enabled
        append(LogEntry(now(), LogLevel.INFO, if (enabled) "已开启详细日志（会记录每条 USB 收发）" else "已关闭详细日志"))
    }

    fun setMaxSizeMb(mb: Int) {
        _maxSizeMb.value = mb.coerceIn(1, AppConfig.LOG_MAX_MB_LIMIT)
        maxBytes = _maxSizeMb.value * 1024L * 1024L
        append(LogEntry(now(), LogLevel.INFO, "日志上限设为 ${_maxSizeMb.value} MB，超出后自动清理旧文件"))
    }

    fun d(message: String) {
        if (!_detailed.value) return
        append(LogEntry(now(), LogLevel.DEBUG, message))
    }

    fun i(message: String) = append(LogEntry(now(), LogLevel.INFO, message))

    fun w(message: String) = append(LogEntry(now(), LogLevel.WARN, message))

    fun e(message: String) = append(LogEntry(now(), LogLevel.ERROR, message))

    /** 记录异常：消息 + 精简堆栈 */
    fun e(t: Throwable, prefix: String? = null) {
        val title = if (prefix.isNullOrBlank()) {
            t.message ?: t.javaClass.simpleName
        } else {
            "$prefix：${t.message ?: t.javaClass.simpleName}"
        }
        e(title)
        t.stackTrace.take(8).forEach { append(LogEntry(now(), LogLevel.DEBUG, "    at $it")) }
    }

    fun clear() {
        _entries.value = emptyList()
        synchronized(this) {
            runCatching { logFile?.delete() }
            runCatching { File(logDir, "$LOG_FILE_NAME.1").delete() }
            runCatching { logFile?.createNewFile() }
        }
        append(LogEntry(now(), LogLevel.INFO, "日志已清空"))
    }

    /** 当前日志文件的字符数，给界面显示占用用 */
    fun currentFileSize(): Long = logFile?.length() ?: 0L

    /**
     * 导出 zip：日志正文 + 环境信息 + 文件日志（含轮转备份）。
     * 返回导出文件，失败返回 null。
     */
    fun exportZip(context: Context, environment: Map<String, String>): File? {
        return try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(baseDir, "export").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(dir, "fastboot-log-$stamp.zip")

            ZipOutputStream(target.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("log-memory.txt"))
                zos.write(
                    _entries.value.joinToString("\n") { it.format() }.toByteArray(Charsets.UTF_8)
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("environment.txt"))
                zos.write(
                    environment.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        .toByteArray(Charsets.UTF_8)
                )
                zos.closeEntry()

                val file = logFile
                if (file != null && file.exists() && file.length() > 0) {
                    zos.putNextEntry(ZipEntry("log-file.txt"))
                    zos.write(file.readBytes())
                    zos.closeEntry()
                }

                val backup = File(logDir, "$LOG_FILE_NAME.1")
                if (backup.exists() && backup.length() > 0) {
                    zos.putNextEntry(ZipEntry("log-file-old.txt"))
                    zos.write(backup.readBytes())
                    zos.closeEntry()
                }
            }

            append(LogEntry(now(), LogLevel.INFO, "日志已导出到 ${target.absolutePath}"))
            target
        } catch (t: Throwable) {
            append(LogEntry(now(), LogLevel.ERROR, "导出日志失败：${t.message}"))
            null
        }
    }

    @Synchronized
    private fun append(entry: LogEntry) {
        // 内存缓冲的读-改-写必须与 writeToFile 的文件锁同锁 ——
        // USB 收发日志来自多个 IO 线程，不加锁会丢条目
        val snapshot = _entries.value
        _entries.value = if (snapshot.size >= AppConfig.LOG_MAX_IN_MEMORY) {
            snapshot.subList(snapshot.size - AppConfig.LOG_MAX_IN_MEMORY + 1, snapshot.size) + entry
        } else {
            snapshot + entry
        }

        when (entry.level) {
            LogLevel.ERROR -> Log.e(TAG, entry.message)
            LogLevel.WARN -> Log.w(TAG, entry.message)
            LogLevel.DEBUG -> Log.d(TAG, entry.message)
            LogLevel.INFO -> Log.i(TAG, entry.message)
        }

        writeToFile(entry)
    }

    private fun writeToFile(entry: LogEntry) {
        val file = logFile ?: return
        synchronized(this) {
            try {
                if (file.exists() && file.length() > maxBytes) {
                    val backup = File(logDir, "$LOG_FILE_NAME.1")
                    runCatching { backup.delete() }
                    runCatching { file.renameTo(backup) }
                    runCatching { file.createNewFile() }
                }
                file.appendText(entry.format() + "\n", Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "写日志文件失败: ${t.message}")
            }
        }
    }

    @Synchronized
    private fun now(): String = timeFormat.format(Date())
}
