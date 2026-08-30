package dev.newthoster.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Bucket(val id: String, val name: String, val enabled: Boolean, val bytesServed: Long, val createdAt: Long)

class BucketStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("buckets", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "buckets").apply { mkdirs() }
    private val lock = Any()

    fun list(): List<Bucket> = synchronized(lock) {
        val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Bucket(o.getString("id"), o.getString("name"), o.optBoolean("enabled", true), o.optLong("bytesServed", 0), o.optLong("createdAt", 0)))
            }
        }.sortedBy { it.createdAt }
    }

    fun create(name: String): Bucket = synchronized(lock) {
        val safeName = name.trim().ifEmpty { "Bucket" }.take(80)
        val bucket = Bucket(UUID.randomUUID().toString(), safeName, true, 0, System.currentTimeMillis())
        directory(bucket.id).mkdirs()
        File(directory(bucket.id), "index.html").writeText(
            "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escapeHtml(safeName)}</title></head><body><h1>${escapeHtml(safeName)}</h1></body></html>"
        )
        save(list() + bucket)
        bucket
    }

    fun toggle(id: String, enabled: Boolean) = synchronized(lock) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun addTraffic(id: String, bytes: Long) = synchronized(lock) {
        if (bytes > 0) save(list().map { if (it.id == id) it.copy(bytesServed = it.bytesServed + bytes) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        directory(id).deleteRecursively()
        save(list().filterNot { it.id == id })
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
        val out = File(directory(id), clean)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read selected file" }
            out.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
        }
    }

    fun readText(id: String, relativePath: String, maxBytes: Int = 2_000_000): String {
        val file = resolveSafe(id, relativePath)
        require(file.length() <= maxBytes) { "File too large for inline editor" }
        return file.readText()
    }

    fun writeText(id: String, relativePath: String, content: String) {
        require(content.toByteArray().size <= 2_000_000) { "File too large" }
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
        require(!normalized.contains("\u0000"))
        val target = File(base, normalized).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) { "Path traversal blocked" }
        return target
    }

    private fun save(items: List<Bucket>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("enabled", it.enabled)
                put("bytesServed", it.bytesServed); put("createdAt", it.createdAt)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun sanitizeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(120)

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
