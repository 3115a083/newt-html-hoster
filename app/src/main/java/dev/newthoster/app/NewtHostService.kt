package dev.newthoster.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RuntimeState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Stopped",
    val newtVersion: String = "unknown",
    val linkMbps: Int = 0,
    val remainingMinutes: Long? = null
)

object RuntimeBus {
    val state = MutableStateFlow(RuntimeState())
}

class NewtHostService : Service() {
    companion object {
        const val ACTION_START = "dev.newthoster.START"
        const val ACTION_STOP = "dev.newthoster.STOP"
        const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL = "newt_runtime"
        private const val NOTIFICATION_ID = 9115
    }

    private var process: Process? = null
    private val servers = mutableMapOf<String, SecureStaticServer>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var stopTask: ScheduledFuture<*>? = null
    private var healthTask: ScheduledFuture<*>? = null
    private var deadlineMillis: Long = 0L
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRuntime()
            ACTION_START -> {
                val requestedMinutes = intent.getLongExtra(EXTRA_MINUTES, 0L)
                val minutes = if (requestedMinutes <= 0L) null else requestedMinutes.coerceIn(1L, 10080L)
                startForeground(NOTIFICATION_ID, notification("Starting Newt…"))
                if (process == null && !RuntimeBus.state.value.running) startRuntime(minutes)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRuntime(minutes: Long?) {
        worker.execute {
            stopping.set(false)
            RuntimeDebugBus.clear()
            RuntimeDebugBus.add("Starting Newt runtime")
            deadlineMillis = minutes?.let { System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(it) } ?: 0L
            RuntimeBus.state.value = RuntimeState(running = true, status = "Starting", remainingMinutes = minutes)
            val app = application as HosterApp
            val config = runCatching { app.vault.load() }.getOrElse {
                RuntimeDebugBus.add("Credential vault error: " + it.javaClass.simpleName)
                fail("Credential vault error")
                return@execute
            }
            if (config == null) {
                RuntimeDebugBus.add("No Newt credentials configured")
                fail("Missing Newt credentials")
                return@execute
            }

            try {
                RuntimeDebugBus.add("Verifying Pangolin TLS certificate and stored SPKI pin")
                val currentPin = TlsPin.fetchSpkiSha256(config.endpoint)
                if (currentPin != config.certPinSha256) {
                    RuntimeDebugBus.add("TLS pin mismatch. Connection aborted.")
                    fail("TLS pin mismatch")
                    return@execute
                }
                if (stopping.get()) {
                    config.wipe()
                    stopSelfRuntime()
                    return@execute
                }

                RuntimeDebugBus.add("TLS pin verified")
                val runtimeDir = File(filesDir, "newt-runtime").apply { mkdirs() }
                val healthFile = File(runtimeDir, "healthy").apply { delete() }
                syncBucketServers(app.buckets)
                acquireWakeLock()

                val binary = File(applicationInfo.nativeLibraryDir, "libnewt.so")
                require(binary.isFile) { "Newt runtime missing" }
                RuntimeDebugBus.add("Newt executable prepared")

                val pb = ProcessBuilder(binary.absolutePath)
                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["PANGOLIN_ENDPOINT"] = config.endpoint
                env["NEWT_ID"] = config.newtId
                env["NEWT_SECRET"] = config.secret.concatToString()
                env["LOG_LEVEL"] = "DEBUG"
                env["USE_NATIVE_INTERFACE"] = "false"
                env["HOME"] = runtimeDir.absolutePath
                env["HEALTH_FILE"] = healthFile.absolutePath
                env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                val dns = currentDnsServer()
                if (dns != null) {
                    env["DNS"] = dns
                    RuntimeDebugBus.add("Using Android DNS server: " + dns)
                } else {
                    env.remove("DNS")
                    RuntimeDebugBus.add("No usable Android DNS server; using Newt default DNS")
                }

                if (stopping.get()) {
                    config.wipe()
                    stopSelfRuntime()
                    return@execute
                }

                process = try {
                    pb.start()
                } finally {
                    config.wipe()
                    env.remove("NEWT_SECRET")
                    env.remove("NEWT_ID")
                    env.remove("PANGOLIN_ENDPOINT")
                }

                RuntimeDebugBus.add("Newt process started")
                val version = runCatching {
                    ProcessBuilder(binary.absolutePath, "--version")
                        .redirectErrorStream(true)
                        .start()
                        .inputStream.bufferedReader()
                        .use { it.readLine() ?: "unknown" }
                }.getOrDefault("unknown")
                RuntimeDebugBus.add("Embedded " + version)
                RuntimeBus.state.value = RuntimeBus.state.value.copy(newtVersion = version, status = "Connecting")
                updateNotification()

                stopTask = minutes?.let { duration ->
                    scheduler.schedule({ if (!stopping.get()) stopRuntime() }, duration, TimeUnit.MINUTES)
                }
                healthTask = scheduler.scheduleAtFixedRate({
                    syncBucketServers(app.buckets)
                    val procAlive = process?.isAlive == true
                    val connected = procAlive && healthFile.isFile
                    val remaining = if (deadlineMillis > 0L) {
                        ((deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
                    } else null
                    val previous = RuntimeBus.state.value
                    if (connected && !previous.connected) RuntimeDebugBus.add("Tunnel health check reports connected")
                    RuntimeBus.state.value = previous.copy(
                        running = procAlive,
                        connected = connected,
                        status = when {
                            !procAlive -> "Stopped"
                            connected -> "Connected"
                            else -> "Connecting"
                        },
                        linkMbps = linkSpeed(),
                        remainingMinutes = remaining
                    )
                    updateNotification()
                }, 0, 2, TimeUnit.SECONDS)

                process!!.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) RuntimeDebugBus.add(line)
                    }
                }

                val exit = process?.waitFor() ?: -1
                RuntimeDebugBus.add("Newt process exited with code " + exit)
                if (!stopping.get() && exit != 0) fail("Newt stopped unexpectedly") else stopSelfRuntime()
            } catch (t: Throwable) {
                config.wipe()
                if (stopping.get()) {
                    stopSelfRuntime()
                } else {
                    RuntimeDebugBus.add("Runtime error: " + t.javaClass.simpleName + ": " + (t.message ?: ""))
                    fail(t.message?.take(100) ?: t.javaClass.simpleName)
                }
            }
        }
    }

    @Synchronized
    private fun syncBucketServers(store: BucketStore) {
        val buckets = store.list()
        val ids = buckets.mapTo(mutableSetOf()) { it.id }

        val removed = servers.keys.filter { it !in ids }
        removed.forEach { id ->
            runCatching { servers.remove(id)?.stop() }
            RuntimeDebugBus.add("Stopped bucket server: " + id)
        }

        buckets.forEach { bucket ->
            if (servers.containsKey(bucket.id)) return@forEach
            val server = SecureStaticServer(store, bucket.id, bucket.port)
            runCatching { server.start() }
                .onSuccess {
                    servers[bucket.id] = server
                    val reachable = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", bucket.port), 1_000)
                        }
                        true
                    }.getOrDefault(false)
                    RuntimeDebugBus.add(
                        "Bucket " + bucket.name + " listening on 127.0.0.1:" + bucket.port +
                            if (reachable) " (self-check OK)" else " (self-check FAILED)"
                    )
                }
                .onFailure {
                    RuntimeDebugBus.add("Bucket server failed on port " + bucket.port + ": " + (it.message ?: it.javaClass.simpleName))
                }
        }
    }

    @Synchronized
    private fun stopBucketServers() {
        servers.values.forEach { runCatching { it.stop() } }
        servers.clear()
    }

    private fun currentDnsServer(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.dnsServers
            ?.firstOrNull { address ->
                !address.isLoopbackAddress &&
                    !address.isAnyLocalAddress &&
                    !address.isMulticastAddress
            }
            ?.hostAddress
    }

    private fun linkSpeed(): Int {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 0
        return (caps.linkDownstreamBandwidthKbps / 1000).coerceAtLeast(0)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NewtHtmlHoster::Tunnel").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun fail(message: String) {
        RuntimeDebugBus.add("Connection failed: " + message)
        RuntimeBus.state.value = RuntimeState(running = false, connected = false, status = message)
        updateNotification()
        stopSelfRuntime(resetState = false)
    }

    private fun stopRuntime() {
        if (!stopping.compareAndSet(false, true)) return
        RuntimeDebugBus.add("Stop requested")
        stopTask?.cancel(true)
        healthTask?.cancel(true)
        runCatching { process?.destroy() }
        scheduler.execute { stopSelfRuntime(resetState = true) }
    }

    @Synchronized
    private fun stopSelfRuntime(resetState: Boolean = true) {
        stopping.set(true)
        stopTask?.cancel(true)
        healthTask?.cancel(true)
        stopTask = null
        healthTask = null
        runCatching { process?.destroy() }
        runCatching {
            if (process?.waitFor(2, TimeUnit.SECONDS) == false) process?.destroyForcibly()
        }
        process = null
        stopBucketServers()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        File(filesDir, "newt-runtime/healthy").delete()
        if (resetState) RuntimeBus.state.value = RuntimeState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NewtHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Newt HTML Hoster")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification() {
        val state = RuntimeBus.state.value
        val remaining = state.remainingMinutes?.let { " · ${it} min" }.orEmpty()
        val text = if (state.connected) "Newt connected · ${state.linkMbps} Mbps$remaining" else state.status + remaining
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Newt runtime", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        stopTask?.cancel(true)
        healthTask?.cancel(true)
        runCatching { process?.destroyForcibly() }
        stopBucketServers()
        if (wakeLock?.isHeld == true) runCatching { wakeLock?.release() }
        worker.shutdownNow()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
