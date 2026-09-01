package dev.newthoster.app

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class TrafficEvent(
    val timestamp: String,
    val method: String,
    val path: String,
    val socketIp: String,
    val reportedIp: String?,
    val userAgent: String?
) {
    fun asLine(): String = buildString {
        append(timestamp).append("  ").append(method).append(" ").append(path)
        append("\nSocket: ").append(socketIp)
        append("\nReported IP: ").append(reportedIp ?: "not provided")
        append("\nUser-Agent: ").append(userAgent ?: "not provided")
    }
}

object TrafficInspectorBus {
    private const val MAX_EVENTS_PER_BUCKET = 100
    private val lock = Any()
    private val enabled = mutableSetOf<String>()
    private val events = mutableMapOf<String, ArrayDeque<TrafficEvent>>()
    val revision = MutableStateFlow(0L)

    fun isEnabled(bucketId: String): Boolean = synchronized(lock) { bucketId in enabled }

    fun setEnabled(bucketId: String, value: Boolean) = synchronized(lock) {
        if (value) enabled += bucketId
        else {
            enabled -= bucketId
            events.remove(bucketId)
        }
        revision.value += 1
    }

    fun clear(bucketId: String) = synchronized(lock) {
        events.remove(bucketId)
        revision.value += 1
    }

    fun list(bucketId: String): List<TrafficEvent> = synchronized(lock) {
        events[bucketId]?.toList().orEmpty()
    }

    fun record(
        bucketId: String,
        method: String,
        path: String,
        socketIp: String,
        reportedIp: String?,
        userAgent: String?
    ) = synchronized(lock) {
        if (bucketId !in enabled) return@synchronized
        val ring = events.getOrPut(bucketId) { ArrayDeque() }
        if (ring.size >= MAX_EVENTS_PER_BUCKET) ring.removeFirst()
        ring.addLast(
            TrafficEvent(
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                method = method.take(12),
                path = path.take(512),
                socketIp = socketIp.take(64),
                reportedIp = reportedIp?.take(256),
                userAgent = userAgent?.take(512)
            )
        )
        revision.value += 1
    }
}
