package com.tvhelper.app.apkserver

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简局域网 HTTP 文件服务，用于把手机上的 APK 暴露给电视下载。
 *
 * 流程：SAF 选 APK → 本服务托管 `http://<手机IP>:8765/apk/<name>.apk`
 * → UDT `installUrlApp(url)` → 电视端拉取并弹官方确认框安装。
 *
 * 自己实现而不引第三方框架的原因：只需要 GET/HEAD + Range，且避免额外依赖。
 */
class ApkHttpServer(private val port: Int = DEFAULT_PORT) {

    companion object {
        private const val TAG = "ApkHttpServer"
        const val DEFAULT_PORT = 8765
        const val MIME_APK = "application/vnd.android.package-archive"
        private val STATUS_TEXT = mapOf(
            200 to "OK", 206 to "Partial Content", 400 to "Bad Request",
            403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
            416 to "Range Not Satisfiable", 500 to "Internal Server Error",
        )
    }

    data class Entry(val file: File, val mime: String = MIME_APK, val downloadName: String = file.name)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _bytesServed = MutableStateFlow(0L)
    /** 已推送给电视的字节数，用于安装进度展示 */
    val bytesServed: StateFlow<Long> = _bytesServed

    private val _lastRequest = MutableStateFlow<String?>(null)
    val lastRequest: StateFlow<String?> = _lastRequest

    private val totalServed = AtomicLong(0)

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun register(path: String, file: File, mime: String = MIME_APK): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        entries[normalized] = Entry(file, mime, file.name)
        return normalized
    }

    fun unregister(path: String) {
        entries.remove(path)
    }

    fun clear() {
        entries.clear()
        totalServed.set(0)
        _bytesServed.value = 0
    }

    /** 启动并返回实际绑定端口；绑定失败抛异常 */
    fun start(): Int {
        if (isRunning) return serverSocket!!.localPort
        val ss = ServerSocket(port).apply { reuseAddress = true }
        serverSocket = ss
        acceptJob = scope.launch { acceptLoop(ss) }
        Log.i(TAG, "APK HTTP 服务已启动，端口 ${ss.localPort}")
        return ss.localPort
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive && !ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (t: Throwable) {
                break
            }
            scope.launch { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            runCatching {
                val ins = s.getInputStream().buffered(8 * 1024)
                val out = BufferedOutputStream(s.getOutputStream(), 32 * 1024)
                val head = readHead(ins) ?: return@runCatching
                if (head.isEmpty()) return@runCatching
                serve(head, out)
                out.flush()
            }.onFailure { Log.w(TAG, "请求处理失败：${it.message}") }
        }
    }

    /** 读取请求头（以空行结束），返回各行 */
    private fun readHead(ins: InputStream): List<String>? {
        val lines = ArrayList<String>(16)
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = ins.read()
            if (b < 0) return if (lines.isEmpty()) null else lines
            if (prev == '\r'.code && b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                if (line.isEmpty()) {
                    // 空行结束，但第一行可能还没进 lines
                    if (lines.isEmpty()) {
                        sb.setLength(0); prev = -1; continue
                    }
                    return lines
                }
                lines += line
                sb.setLength(0)
            } else {
                sb.append(b.toChar())
            }
            prev = b
        }
    }

    private fun serve(headerLines: List<String>, out: OutputStream) {
        val requestLine = headerLines.firstOrNull().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respondText(out, 400, "bad request")

        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val headers = headerLines.drop(1)
            .mapNotNull { l ->
                val i = l.indexOf(':')
                if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }
            .toMap()

        _lastRequest.value = "$method $path"

        if (method != "GET" && method != "HEAD") return respondText(out, 405, "only GET/HEAD")

        // 目录枚举：/  返回已注册条目，便于调试
        if (path == "/" || path == "/list") {
            val body = buildString {
                append("{\"files\":[")
                entries.keys.forEachIndexed { i, k ->
                    if (i > 0) append(',')
                    append("\"").append(k).append("\"")
                }
                append("]}")
            }
            return respondBytes(
                out, 200, body.toByteArray(), "application/json", method == "HEAD"
            )
        }

        val entry = entries[path]
            ?: entries.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value
            ?: return respondText(out, 404, "not found")

        if (!entry.file.exists() || !entry.file.isFile) return respondText(out, 404, "file missing")

        val length = entry.file.length()
        val range = headers["range"]?.let { parseRange(it, length) }

        if (range != null) {
            val (start, end) = range
            val chunk = end - start + 1
            val head = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: ").append(entry.mime).append("\r\n")
                append("Content-Length: ").append(chunk).append("\r\n")
                append("Content-Range: bytes $start-$end/$length\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Disposition: attachment; filename=\"").append(entry.downloadName).append("\"\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            if (method == "GET") streamFile(entry.file, out, start, chunk)
        } else {
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ").append(entry.mime).append("\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Disposition: attachment; filename=\"").append(entry.downloadName).append("\"\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            if (method == "GET") streamFile(entry.file, out, 0, length)
        }
    }

    private fun streamFile(file: File, out: OutputStream, start: Long, count: Long) {
        var remaining = count
        file.inputStream().use { fis ->
            fis.skip(start)
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val read = fis.read(buf, 0, want)
                if (read < 0) break
                out.write(buf, 0, read)
                remaining -= read
                _bytesServed.value = totalServed.addAndGet(read.toLong())
            }
        }
    }

    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").split(',').first().trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (startStr.isEmpty()) {
            // last-N bytes
            val n = endStr.toLongOrNull() ?: return null
            start = (total - n).coerceAtLeast(0)
            end = total - 1
        } else {
            start = startStr.toLongOrNull() ?: return null
            end = if (endStr.isEmpty()) total - 1 else (endStr.toLongOrNull() ?: return null)
        }
        if (start > end || start >= total) return null
        return start to end.coerceAtMost(total - 1)
    }

    private fun respondText(out: OutputStream, code: Int, msg: String) =
        respondBytes(out, code, msg.toByteArray(), "text/plain; charset=utf-8", false)

    private fun respondBytes(out: OutputStream, code: Int, body: ByteArray, mime: String, headOnly: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(STATUS_TEXT[code] ?: "").append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        if (!headOnly) out.write(body)
    }

    /** 生成 URL 友好且可被电视识别的文件名 */
    fun encodePath(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }
}
