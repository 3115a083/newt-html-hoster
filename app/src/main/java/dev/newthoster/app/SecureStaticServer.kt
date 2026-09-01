package dev.newthoster.app

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class SecureStaticServer(
    private val store: BucketStore,
    private val bucketId: String,
    private val port: Int
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val slots = Semaphore(24)
    private val rateLock = Any()
    private var rateWindowNanos = System.nanoTime()
    private var requestsInWindow = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    if (!allowRequest()) {
                        socket.use { writeStatus(it, 429, "Too Many Requests") }
                        continue
                    }
                    if (!slots.tryAcquire()) {
                        socket.use { writeStatus(it, 429, "Too Many Requests") }
                        continue
                    }
                    pool.execute {
                        try { handle(socket) } finally { slots.release() }
                    }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }, "newt-hoster-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII), 4096)
            val requestLine = reader.readLine() ?: return
            if (requestLine.length > 4096) return writeStatus(s, 414, "URI Too Long")
            val parts = requestLine.split(' ')
            if (parts.size != 3) return writeStatus(s, 400, "Bad Request")
            val method = parts[0]
            if (method != "GET" && method != "HEAD") return writeStatus(s, 405, "Method Not Allowed")

            var headerBytes = requestLine.length
            while (true) {
                val line = reader.readLine() ?: return
                headerBytes += line.length + 2
                if (headerBytes > 16_384) return writeStatus(s, 431, "Request Header Fields Too Large")
                if (line.isEmpty()) break
            }

            val pathOnly = parts[1].substringBefore('?')
            val decoded = runCatching { URLDecoder.decode(pathOnly, "UTF-8") }.getOrElse {
                return writeStatus(s, 400, "Bad Request")
            }
            val bucket = store.list().firstOrNull { it.id == bucketId } ?: return writeStatus(s, 404, "Not Found")
            if (!bucket.enabled) return writeStatus(s, 404, "Not Found")

            var rel = decoded.trimStart('/')
            if (rel.isBlank()) rel = "index.html"
            var file = runCatching { store.resolveSafe(bucketId, rel) }.getOrElse {
                return writeStatus(s, 403, "Forbidden")
            }
            if (file.isDirectory) {
                file = runCatching { store.resolveSafe(bucketId, rel.trimEnd('/') + "/index.html") }.getOrElse {
                    return writeStatus(s, 403, "Forbidden")
                }
            }
            if (!file.isFile) return writeStatus(s, 404, "Not Found")
            if (file.length() > 100L * 1024 * 1024) return writeStatus(s, 413, "Payload Too Large")

            val mime = mimeFor(file)
            val out = s.getOutputStream()
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("Content-Length: ").append(file.length()).append("\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-store\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                append("X-Frame-Options: DENY\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
                if (mime.startsWith("text/html")) {
                    append("Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n")
                }
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            out.write(headers)
            var sent = headers.size.toLong()
            if (method == "GET") {
                BufferedInputStream(file.inputStream(), 64 * 1024).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        sent += n
                    }
                }
            }
            out.flush()
            store.addTraffic(bucketId, sent)
        }
    }

    private fun allowRequest(): Boolean = synchronized(rateLock) {
        val now = System.nanoTime()
        if (now - rateWindowNanos >= 1_000_000_000L) {
            rateWindowNanos = now
            requestsInWindow = 0
        }
        if (requestsInWindow >= 120) return@synchronized false
        requestsInWindow++
        true
    }

    private fun writeStatus(socket: Socket, code: Int, message: String) {
        val body = "$code $message\n".toByteArray(StandardCharsets.UTF_8)
        val response = "HTTP/1.1 $code $message\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        runCatching {
            socket.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "txt", "md" -> "text/plain; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
