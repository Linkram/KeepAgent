package io.keepagent.app.test

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Minimal single-host static file server for the Test tab's WebView.
 *
 * WebView blocks `file://` URLs into the app's internal storage
 * (ERR_ACCESS_DENIED), so the workspace is served over loopback HTTP
 * instead: relative assets, fetch(), and JS modules behave exactly as
 * they would from a real host. Bound to 127.0.0.1 only; path traversal
 * is rejected; one daemon thread per connection.
 */
class LocalFileServer(val root: File) : AutoCloseable {

    private val socket: ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }

    /** The ephemeral loopback port this server listens on. */
    val port: Int get() = socket.localPort

    private @Volatile var running = true
    private val acceptThread = Thread({ acceptLoop() }, "keepagent-file-server").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        running = false
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun acceptLoop() {
        while (running) {
            val conn = try {
                socket.accept()
            } catch (_: Exception) {
                return
            }
            Thread({ handle(conn) }, "keepagent-file-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(conn: Socket) {
        try {
            conn.soTimeout = 5_000
            val input = conn.getInputStream()
            val out = conn.getOutputStream()
            val request = readRequestLine(input) ?: return
            if (!request.startsWith("GET ")) return respondError(out, 405, "method not allowed")
            val target = request.removePrefix("GET ").substringBefore(' ').substringBefore('?')
            val path = try {
                URLDecoder.decode(target, "UTF-8")
            } catch (_: Exception) {
                return respondError(out, 400, "bad request")
            }
            val dirOrFile = resolveSafe(path) ?: return respondError(out, 403, "forbidden")
            val file = if (dirOrFile.isDirectory) File(dirOrFile, "index.html") else dirOrFile
            if (!file.isFile || file.length() > MAX_FILE_BYTES) {
                return respondError(out, 404, "not found: $target")
            }
            val body = file.readBytes()
            out.write(buildResponse(file, body))
            out.flush()
        } catch (_: Exception) {
            // Per-connection errors are silent; the WebView sees a failed
            // resource and the tab surfaces the page's own errors.
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Reads the request line and drains the headers (never needed here). */
    private fun readRequestLine(input: InputStream): String? {
        val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val line = r.readLine() ?: return null
        var header = r.readLine()
        while (header != null && header.isNotEmpty()) header = r.readLine()
        return line
    }

    /** Maps a request path under [root]; null when it escapes or is malformed. */
    private fun resolveSafe(path: String): File? {
        if (path.contains('\u0000')) return null
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        val requested = if (segments.isEmpty()) root else root.resolve(segments.joinToString(File.separator))
        val p = requested.absoluteFile.normalize().path
        val r = root.absoluteFile.normalize().path
        return if (p == r || p.startsWith(r + File.separator)) requested else null
    }

    private fun buildResponse(file: File, body: ByteArray): ByteArray {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: ${contentType(file.name)}\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        return head.encodeToByteArray() + body
    }

    private fun respondError(out: OutputStream, code: Int, message: String) {
        val head = "HTTP/1.1 $code " + message.replaceFirstChar { it.uppercase() } + "\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${message.length}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.encodeToByteArray())
        out.write(message.encodeToByteArray())
        out.flush()
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "txt", "md", "log", "csv" -> "text/plain; charset=utf-8"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    companion object {
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    }
}

/** The Test tab's loopback server — one per active workspace root. */
object TestServer {
    @Volatile
    private var server: LocalFileServer? = null
    @Volatile
    private var key: String? = null

    @Synchronized
    fun ensure(root: File): LocalFileServer {
        val k = root.absoluteFile.normalize().path
        val current = server
        if (current != null && key == k) return current
        current?.close()
        val fresh = LocalFileServer(root.absoluteFile.normalize())
        server = fresh
        key = k
        return fresh
    }
}
