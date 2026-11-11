package com.mytvphone.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内诊断日志。
 *
 * 目的：用户遇到「导入失败 / 某个源打不开 / 闪退」时，能在设置页导出一份日志给我排查，
 * 不用连电脑 adb、不申请任何存储权限。
 *
 * 设计：
 *  - 日志落在 **应用私有目录** `filesDir/logs/app.log`，仅本 App 可读，卸载即清除；
 *    超过 [MAX_FILE_BYTES] 自动轮转成 app.log.old，避免长期堆积撑爆存储。
 *  - 同时输出到 Logcat（导出时会把 Logcat 一并抓进来，含 Media3 / 系统日志）。
 *  - [init] 挂全局未捕获异常处理器，崩溃堆栈会先落盘再交给系统默认处理，闪退也有据可查。
 *  - 所有文件 IO 都 `runCatching` 兜底：日志写失败绝不能影响播放、导入等主流程。
 *  - [redact] 给埋点用：记录 URL 时去掉 `?` 之后的鉴权参数，避免把 token 写进日志。
 */
object AppLog {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val LOGCAT_LINES = 400

    private val LOCK = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val fullStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Volatile
    private var logDir: File? = null
    private var crashHandlerInstalled = false

    private fun current(): File? = logDir?.let { File(it, "app.log") }
    private fun rolled(): File? = logDir?.let { File(it, "app.log.old") }

    /** 在 Application/主 Activity 最早处调用一次；重复调用安全。 */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(LOCK) {
            logDir = File(app.filesDir, "logs").apply { mkdirs() }
        }
        if (!crashHandlerInstalled) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { e("Crash", "未捕获异常 · 线程=${thread.name}", error) }
                // 链式交回系统默认处理器，保证该崩的还是崩、该上报的还是上报
                previous?.uncaughtException(thread, error)
            }
            crashHandlerInstalled = true
        }
        i(
            "App",
            "==== 启动 ${versionLabel(app)} · ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) ====",
        )
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = write("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val line = buildString {
            append(stamp.format(Date())).append(' ')
            append(level).append(" [").append(tag).append("] ").append(msg)
            if (tr != null) append('\n').append(Log.getStackTraceString(tr))
        }
        runCatching {
            synchronized(LOCK) {
                val f = current()
                if (f != null) {
                    if (f.exists() && f.length() > MAX_FILE_BYTES) {
                        val old = rolled()
                        if (old != null) {
                            old.delete()
                            f.renameTo(old)
                        } else {
                            f.delete()
                        }
                    }
                    f.appendText(line + "\n")
                }
            }
        }
        when (level) {
            "D" -> Log.d(tag, msg, tr)
            "I" -> Log.i(tag, msg, tr)
            "W" -> Log.w(tag, msg, tr)
            else -> Log.e(tag, msg, tr)
        }
    }

    /** 完整日志文本（轮转的旧文件在前，保证时间顺序）；设置页「查看 / 复制」用。 */
    fun text(): String = synchronized(LOCK) {
        buildString {
            val old = rolled()
            if (old != null && old.exists()) runCatching { append(old.readText()) }
            val f = current()
            if (f != null && f.exists()) runCatching { append(f.readText()) }
        }
    }.ifEmpty { "（暂无日志）" }

    /** 仅取末尾若干行，避免把几百 KB 一次性塞进对话框的 Text 导致卡顿。 */
    fun tail(maxLines: Int = 200): String {
        val lines = text().lines()
        val shown = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        val prefix = if (lines.size > maxLines) {
            "…（仅显示最近 $maxLines 行，共 ${lines.size} 行；完整内容请「导出日志」）\n\n"
        } else {
            ""
        }
        return prefix + shown.joinToString("\n")
    }

    fun clear() {
        synchronized(LOCK) {
            runCatching {
                current()?.delete()
                rolled()?.delete()
            }
        }
        i("App", "日志已清空")
    }

    /**
     * 导出为可分享的 .txt：设备信息头 + 应用运行日志 + Logcat 最近若干行。
     * 文件位于 filesDir/logs/export/，由 FileProvider 授权给接收方读取。
     */
    fun export(context: Context): File {
        val app = context.applicationContext
        val dir = File(app.filesDir, "logs/export").apply { mkdirs() }
        val out = File(dir, "mytv-log-${fileStamp.format(Date())}.txt")
        val body = buildString {
            appendLine("口袋电视 · 诊断日志")
            appendLine(deviceHeader(app))
            appendLine()
            appendLine("========== 应用运行日志 ==========")
            append(text())
            appendLine()
            appendLine("========== Logcat 最近 $LOGCAT_LINES 行 ==========")
            append(readLogcat())
        }
        out.writeText(body)
        return out
    }

    private fun readLogcat(): String = runCatching {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString()),
        )
        proc.inputStream.bufferedReader().use { it.readText() }.also { proc.destroy() }
    }.getOrElse { "（读取 logcat 失败：${it.message}；应用运行日志不受影响）" }

    /** 版本号从 PackageManager 读取，永远与用户实际装到的包一致，不写死。 */
    fun versionLabel(context: Context): String = runCatching {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "${pi.versionName} ($vc)"
    }.getOrDefault("未知版本")

    private fun deviceHeader(context: Context): String = buildString {
        appendLine("应用版本: ${versionLabel(context)}")
        appendLine("包名: ${context.packageName}")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("系统: Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("语言/地区: ${Locale.getDefault()}")
        appendLine("导出时间: ${fullStamp.format(Date())}")
    }.trimEnd()

    /** URL 脱敏：保留 scheme://host/path，丢掉 `?` 之后的鉴权参数与 `#` 片段。 */
    fun redact(url: String?): String {
        if (url.isNullOrBlank()) return "（空）"
        return runCatching {
            val cut = url.substringBefore('?').substringBefore('#')
            if (cut.length > 120) cut.take(120) + "…" else cut
        }.getOrDefault("（无法解析）")
    }
}
