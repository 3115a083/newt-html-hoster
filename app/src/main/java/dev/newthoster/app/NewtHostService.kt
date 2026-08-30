package dev.newthoster.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RuntimeState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Stopped",
    val newtVersion: String = "unknown",
    val linkMbps: Int = 0
)

object RuntimeBus {
    val state = MutableStateFlow(RuntimeState())
}

class NewtHostService : Service() {
    companion object {
        const val ACTION_START = "dev.newthoster.START"
        const val ACTION_STOP = "dev.newthoster.STOP"
        const val EXTRA_MINUTES = "minutes"
        const val PORT = 8793
        private const val CHANNEL = "newt_runtime"
        private const val NOTIFICATION_ID = 9115
    }

    private var process: Process? = null
    private var server: SecureStaticServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRuntime()
            ACTION_START -> {
                val minutes = intent.getLongExtra(EXTRA_MINUTES, 60L).coerceIn(1L, 10080L)
                startForeground(NOTIFICATION_ID, notification("Starting Newt…"))
                if (process == null) startRuntime(minutes)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRuntime(minutes: Long) {
        executor.execute {
            stopping = false
            RuntimeBus.state.value = RuntimeBus.state.value.copy(running = true, status = "Starting")
            val app = application as HosterApp
            val config = app.vault.load()
            if (config == null) {
                fail("Missing Newt credentials")
                return@execute
            }

            try {
                val currentPin = TlsPin.fetchSpkiSha256(config.endpoint)
                if (config.certPinSha256 != null && currentPin != config.certPinSha256) {
                    config.secret.fill('\u0000')
                    fail("TLS pin mismatch")
                    return@execute
                }

                server = SecureStaticServer(app.buckets, PORT).also { it.start() }
                acquireWakeLock()

                val binary = applicationInfo.nativeLibraryDir + "/libnewt.so"
                val pb = ProcessBuilder(binary)
                pb.redirectErrorStream(true)
                pb.environment()["PANGOLIN_ENDPOINT"] = config.endpoint
                pb.environment()["NEWT_ID"] = config.newtId
                pb.environment()["NEWT_SECRET"] = config.secret.concatToString()
                pb.environment()["LOG_LEVEL"] = "INFO"
                pb.environment()["USE_NATIVE_INTERFACE"] = "false"
                config.secret.fill('\u0000')

                process = pb.start()
                val version = runCatching {
                    ProcessBuilder(binary, "--version").redirectErrorStream(true).start().inputStream.bufferedReader().readLine() ?: "unknown"
                }.getOrDefault("unknown")
                RuntimeBus.state.value = RuntimeBus.state.value.copy(newtVersion = version)

                Thread {
                    try {
                        TimeUnit.MINUTES.sleep(minutes)
                        if (!stopping) stopSelfRuntime()
                    } catch (_: InterruptedException) {}
                }.start()

                BufferedReader(InputStreamReader(process!!.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        val lower = line.lowercase()
                        val connected = lower.contains("websocket connected") || lower.contains("tunnel connection to server established")
                        val state = RuntimeBus.state.value
                        RuntimeBus.state.value = state.copy(
                            connected = state.connected || connected,
                            status = if (state.connected || connected) "Connected" else sanitizeStatus(line),
                            linkMbps = linkSpeed()
                        )
                        updateNotification()
                    }
                }
                val exit = process?.waitFor() ?: -1
                if (!stopping && exit != 0) fail("Newt exited with code $exit") else stopSelfRuntime()
            } catch (t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun sanitizeStatus(line: String): String {
        val redacted = line
            .replace(Regex("(?i)(secret|token|authorization)[=: ]+[^ ]+"), "$1=[redacted]")
            .replace(Regex("https://[^ ]+"), "HTTPS endpoint")
        return redacted.take(120)
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
        RuntimeBus.state.value = RuntimeState(status = message)
        updateNotification()
        stopSelfRuntime()
    }

    private fun stopRuntime() {
        stopping = true
        executor.execute { stopSelfRuntime() }
    }

    private fun stopSelfRuntime() {
        stopping = true
        runCatching { process?.destroy() }
        runCatching {
            if (process?.waitFor(2, TimeUnit.SECONDS) == false) process?.destroyForcibly()
        }
        process = null
        server?.stop()
        server = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        RuntimeBus.state.value = RuntimeState()
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
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification() {
        val state = RuntimeBus.state.value
        val text = if (state.connected) "Newt connected · ${state.linkMbps} Mbps link" else state.status
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Newt runtime", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        if (!stopping) stopSelfRuntime()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
