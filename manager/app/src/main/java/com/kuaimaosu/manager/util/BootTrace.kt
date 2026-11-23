package com.kuaimaosu.manager.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动全链路打点（只读诊断，不改变任何功能）。
 *
 * 每次冷启动写一条分隔（PID + 时间），并读取系统记录的「上次进程死亡原因」
 * (ApplicationExitInfo) ——这是判定进程是被 force-stop / crash / 系统杀 / ANR
 * 的权威依据，比任何推测都可靠。
 *
 * 写到三处，方便取：
 *   1) 内部 filesDir/boot_trace.txt
 *   2) 外部私有 Android/data/<包名>/files/boot_trace.txt
 *   3) /sdcard/Download/kuaimao_boot_trace.txt（普通文件管理器可见，尽力而为）
 */
object BootTrace {
    private const val FILE_NAME = "boot_trace.txt"
    private const val DOWNLOAD_NAME = "kuaimao_boot_trace.txt"
    private const val MAX_LINES = 600

    @Volatile
    private var installed = false

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 在 Application.onCreate 里最先调用。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        bindContext(app)
        val sep = buildString {
            appendLine()
            appendLine("========== PROCESS START pid=${Process.myPid()} ${now()} ==========")
            appendLine(
                "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} " +
                    "api=${Build.VERSION.SDK_INT} pkg=${app.packageName}",
            )
        }
        writeRaw(app, sep)
        mark("Application.onCreate enter")
        dumpLastExitReasons(app)
    }

    /** 打一个带时间戳/PID 的节点。 */
    fun mark(tag: String) {
        val ctx = appContext ?: return
        writeRaw(ctx, "${now()} pid=${Process.myPid()} $tag\n")
    }

    /** 记录异常，带堆栈。 */
    fun markThrowable(tag: String, t: Throwable) {
        val ctx = appContext ?: return
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        writeRaw(ctx, "${now()} pid=${Process.myPid()} $tag\n$sw")
    }

    /**
     * 读取系统记录的本应用历史进程死亡原因。
     * reason 码：1=USER_REQUESTED 2=LOW_MEMORY 3=CRASH? 4=CRASH 5=USER_STOPPED(force-stop)
     *           6=ANR 10=CRASH_NATIVE 11=DEPENDENCY 12=SDK 等；description 有更详细的说明。
     */
    private fun dumpLastExitReasons(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val infos: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(null, 0, 8)
            if (infos.isEmpty()) {
                mark("lastExit: <none>")
                return
            }
            var dumpedTrace = false
            for (info in infos) {
                val desc = info.description?.replace('\n', ' ')?.take(160) ?: ""
                mark(
                    "lastExit: pid=${info.pid} proc=${info.processName} " +
                        "reason=${info.reason}(${reasonName(info.reason)}) " +
                        "importance=${info.importance} ts=${info.timestamp} " +
                        "desc=[$desc]",
                )
                // 对最近一次 CRASH 抓取系统保存的崩溃堆栈（决定性证据）
                if (!dumpedTrace && info.reason == ApplicationExitInfo.REASON_CRASH) {
                    dumpedTrace = true
                    runCatching {
                        val stream = info.traceInputStream
                        if (stream != null) {
                            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            writeRaw(context, "----- lastExit CRASH STACK (pid=${info.pid}) -----\n$text\n----- END STACK -----\n")
                        } else {
                            mark("lastExit: traceInputStream is null")
                        }
                    }.onFailure { markThrowable("lastExit: read trace failed", it) }
                }
            }
        }.onFailure { markThrowable("lastExit: READ FAILED", it) }
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED(force-stop)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        else -> "OTHER"
    }

    private fun now(): String = timeFmt.format(Date())

    @Volatile
    private var appContext: Context? = null

    internal fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    fun externalPath(context: Context): String =
        context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).absolutePath }
            ?: File(context.filesDir, FILE_NAME).absolutePath

    private fun writeRaw(context: Context, text: String) {
        runCatching { appendCapped(File(context.filesDir, FILE_NAME), text) }
        runCatching {
            context.getExternalFilesDir(null)?.let { appendCapped(File(it, FILE_NAME), text) }
        }
        // 尽力写一份到公开 Download，普通文件管理器可见；失败不影响主流程
        runCatching {
            val dl = File("/sdcard/Download", DOWNLOAD_NAME)
            if (dl.parentFile?.exists() == true) appendCapped(dl, text)
        }
    }

    @Synchronized
    private fun appendCapped(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(text)
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }
    }
}
