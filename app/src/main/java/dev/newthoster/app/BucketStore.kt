package dev.newthoster.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

data class Bucket(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val bytesServed: Long,
    val createdAt: Long,
    val port: Int
)

class BucketStore(private val context: Context) {
    companion object {
        const val MIN_BUCKET_PORT = 8800
        const val MAX_BUCKET_PORT = 9799
    }
    private val prefs = context.getSharedPreferences("buckets", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "buckets").apply { mkdirs() }
    private val lock = Any()
    private val pendingTraffic = mutableMapOf<String, Long>()
    private var lastTrafficFlushMs = System.currentTimeMillis()

    fun list(): List<Bucket> = synchronized(lock) {
        readStored().map { it.copy(bytesServed = it.bytesServed + (pendingTraffic[it.id] ?: 0L)) }
            .sortedBy { it.createdAt }
    }

    fun create(name: String): Bucket = synchronized(lock) {
        flushTrafficLocked()
        val safeName = name.trim().ifEmpty { "Bucket" }.take(80)
        val stored = readStored()
        val port = allocatePort(stored.mapTo(mutableSetOf()) { it.port })
        val bucket = Bucket(UUID.randomUUID().toString(), safeName, true, 0, System.currentTimeMillis(), port)
        directory(bucket.id).mkdirs()
        File(directory(bucket.id), "index.html").writeText(
            "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escapeHtml(safeName)}</title></head><body><h1>${escapeHtml(safeName)}</h1></body></html>"
        )
        saveStored(stored + bucket)
        bucket
    }

    fun toggle(id: String, enabled: Boolean) = synchronized(lock) {
        flushTrafficLocked()
        saveStored(readStored().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun addTraffic(id: String, bytes: Long) = synchronized(lock) {
        if (bytes <= 0) return@synchronized
        pendingTraffic[id] = (pendingTraffic[id] ?: 0L) + bytes
        val now = System.currentTimeMillis()
        if (now - lastTrafficFlushMs >= 5_000L || (pendingTraffic[id] ?: 0L) >= 1L * 1024 * 1024) {
            flushTrafficLocked()
        }
    }

    fun flushTraffic() = synchronized(lock) { flushTrafficLocked() }

    fun delete(id: String) = synchronized(lock) {
        flushTrafficLocked()
        directory(id).deleteRecursively()
        pendingTraffic.remove(id)
        saveStored(readStored().filterNot { it.id == id })
    }

    fun directory(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid bucket id" }
        return File(root, id)
    }

    fun files(id: String): List<File> {
        val dir = directory(id)
        return dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.toList()
    }

    fun import(id: String, uri: Uri, displayName: String) {
        val clean = sanitizeFileName(displayName)
        require(clean.isNotBlank())
        val out = resolveSafe(id, clean)
        val temp = File(out.parentFile, ".${out.name}.import")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read selected file" }
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        total += n
                        require(total <= 100L * 1024 * 1024) { "Asset exceeds 100 MiB limit" }
                        output.write(buffer, 0, n)
                    }
                    buffer.fill(0)
                }
            }
            if (!temp.renameTo(out)) {
                temp.copyTo(out, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun deleteFile(id: String, relativePath: String) {
        val file = resolveSafe(id, relativePath)
        require(file.isFile) { "File not found" }
        require(file.delete()) { "Could not delete file" }
        cleanupEmptyParents(id, file.parentFile)
    }

    fun renameFile(id: String, relativePath: String, newName: String): String {
        val source = resolveSafe(id, relativePath)
        require(source.isFile) { "File not found" }
        val clean = sanitizeFileName(newName)
        require(clean.isNotBlank()) { "Invalid file name" }
        require(clean != "." && clean != "..") { "Invalid file name" }

        val base = directory(id).canonicalFile
        val parent = source.parentFile?.canonicalFile ?: error("Missing parent directory")
        require(parent == base || parent.path.startsWith(base.path + File.separator)) { "Path traversal blocked" }

        val target = File(parent, clean).canonicalFile
        require(target.parentFile?.canonicalFile == parent) { "Path traversal blocked" }
        require(target.path.startsWith(base.path + File.separator)) { "Path traversal blocked" }
        require(target != source) { "File name unchanged" }
        require(!target.exists()) { "A file with this name already exists" }
        require(source.renameTo(target)) { "Could not rename file" }
        return target.relativeTo(base).path
    }

    fun readText(id: String, relativePath: String, maxBytes: Int = 2_000_000): String {
        val file = resolveSafe(id, relativePath)
        require(file.isFile) { "File not found" }
        require(file.length() <= maxBytes) { "File too large for inline editor" }
        return file.readText()
    }

    fun writeText(id: String, relativePath: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "File too large" }
        bytes.fill(0)
        val file = resolveSafe(id, relativePath)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun resolveSafe(id: String, rawRelativePath: String): File {
        val base = directory(id).canonicalFile
        val normalized = rawRelativePath.removePrefix("/").replace('\\', '/')
        require(normalized.isNotBlank()) { "Empty path" }
        require(!normalized.contains("\u0000"))
        val target = File(base, normalized).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Path traversal blocked" }
        return target
    }

    private fun readStored(): List<Bucket> {
        val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
        val raw = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Bucket(
                        o.getString("id"),
                        o.getString("name"),
                        o.optBoolean("enabled", true),
                        o.optLong("bytesServed", 0),
                        o.optLong("createdAt", 0),
                        o.optInt("port", 0)
                    )
                )
            }
        }

        val used = mutableSetOf<Int>()
        var changed = false
        val migrated = raw.map { bucket ->
            if (bucket.port in MIN_BUCKET_PORT..MAX_BUCKET_PORT && bucket.port !in used) {
                used += bucket.port
                bucket
            } else {
                val replacement = allocatePort(used)
                used += replacement
                changed = true
                bucket.copy(port = replacement)
            }
        }
        if (changed) saveStored(migrated)
        return migrated
    }

    private fun allocatePort(used: Set<Int>): Int =
        (MIN_BUCKET_PORT..MAX_BUCKET_PORT).firstOrNull { it !in used && isPortAvailable(it) }
            ?: error("No free local bucket port available")

    private fun isPortAvailable(port: Int): Boolean =
        runCatching {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { }
            true
        }.getOrDefault(false)

    private fun flushTrafficLocked() {
        if (pendingTraffic.isEmpty()) {
            lastTrafficFlushMs = System.currentTimeMillis()
            return
        }
        val pending = pendingTraffic.toMap()
        saveStored(readStored().map { it.copy(bytesServed = it.bytesServed + (pending[it.id] ?: 0L)) })
        pendingTraffic.clear()
        lastTrafficFlushMs = System.currentTimeMillis()
    }

    private fun saveStored(items: List<Bucket>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("enabled", it.enabled)
                put("bytesServed", it.bytesServed)
                put("createdAt", it.createdAt)
                put("port", it.port)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun cleanupEmptyParents(id: String, start: File?) {
        val base = directory(id).canonicalFile
        var current = start?.canonicalFile
        while (current != null && current != base && current.path.startsWith(base.path + File.separator)) {
            val children = current.listFiles()
            if (children != null && children.isEmpty()) {
                if (!current.delete()) break
                current = current.parentFile?.canonicalFile
            } else break
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(120)

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
