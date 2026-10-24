package com.tvhelper.app.log

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志：把本 App 关键 tag 的 logcat 输出落盘到 App 专属目录，
 * 供事后用 `adb pull /sdcard/Android/data/com.tvhelper.app/files/logs/` 排查问题。
 *
 * 设计要点：
 * - 用 logcat 子进程（`--pid` 限定本进程 + tag 过滤），业务代码零改动，release 同样可用
 * - `-T 1` 只读新日志，避免把 ring buffer 里的历史会话重复写进新文件
 * - 按天一个文件；单文件超 5MB 轮转（换时间戳后缀），保留最近 7 天
 * - getExternalFilesDir 属 App 专属外部目录，无需存储权限；目录不可用时静默降级（不影响功能）
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val DIR_NAME = "logs"
    private const val RETAIN_DAYS = 7
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024

    private val filterTags = listOf(
        "TvRepository", "RcClient", "UdtClient", "TvViewModel",
        "ConnectionService", "ApkHttpServer", "FileLogger",
    )

    @Volatile
    var currentFile: File? = null
        private set

    fun start(context: Context) {
        val dir = File(context.getExternalFilesDir(null), DIR_NAME)
        try {
            dir.mkdirs()
            cleanupOld(dir)
            val file = rotateIfTooBig(File(dir, "tvhelper-${today()}.log"))
            currentFile = file
            val cmd = mutableListOf(
                "logcat", "-v", "time", "-T", "1", "--pid=${Process.myPid()}",
            )
            filterTags.forEach { cmd += "$it:V" }
            cmd += "*:S"
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // daemon 线程：App 进程结束时随之退出，不阻塞虚拟机
            Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val f = currentFile ?: return@forEach
                            if (f.length() > MAX_FILE_BYTES) rotate(f, dir)
                            currentFile?.appendText(line + "\n")
                        }
                    }
                } catch (_: Exception) {
                    // 进程被杀/流关闭等，静默退出
                }
            }.apply {
                name = "file-logger"
                isDaemon = true
                start()
            }
            Log.i(TAG, "文件日志已启动: ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "文件日志启动失败: ${e.message}")
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun rotateIfTooBig(file: File): File =
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val rotated = File(file.parent, "tvhelper-${today()}-${System.currentTimeMillis()}.log")
            file.renameTo(rotated)
            File(file.parent, "tvhelper-${today()}.log")
        } else file

    private fun rotate(file: File, dir: File) {
        val rotated = File(dir, "tvhelper-${today()}-${System.currentTimeMillis()}.log")
        file.renameTo(rotated)
        currentFile = File(dir, "tvhelper-${today()}.log")
    }

    private fun cleanupOld(dir: File) {
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }
}
