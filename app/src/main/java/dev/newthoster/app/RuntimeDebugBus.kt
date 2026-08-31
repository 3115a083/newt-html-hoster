package dev.newthoster.app

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque

object RuntimeDebugBus {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private const val MAX_LINES = 250

    fun clear() = synchronized(lock) {
        ring.clear()
        lines.value = emptyList()
    }

    fun add(raw: String) = synchronized(lock) {
        val clean = redact(raw).take(800)
        if (clean.isBlank()) return@synchronized
        if (ring.size >= MAX_LINES) ring.removeFirst()
        ring.addLast(clean)
        lines.value = ring.toList()
    }

    private fun redact(raw: String): String {
        var value = raw
        val patterns = listOf(
            Regex("(?i)(NEWT_SECRET|secret|token|authorization|bearer|password|session[_ -]?token)(\\s*[=:]\\s*|\\s+)[^\\s,}]+"),
            Regex("(?i)(Received token:)\\s*.+"),
            Regex("(?i)(Authorization:)\\s*.+")
        )
        patterns.forEach { pattern ->
            value = value.replace(pattern) { match ->
                val key = match.groupValues.getOrNull(1)?.ifBlank { "credential" } ?: "credential"
                key + "=[redacted]"
            }
        }
        return value
    }
}
