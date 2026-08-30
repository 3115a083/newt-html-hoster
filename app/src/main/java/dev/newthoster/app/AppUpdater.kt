package dev.newthoster.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(val tag: String, val apkUrl: String, val sha256Url: String?)

object AppUpdater {
    private const val API = "https://api.github.com/repos/3115a083/newt-html-hoster/releases/latest"

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val json = readText(API)
        val root = JSONObject(json)
        if (root.optBoolean("draft", false)) return@withContext null
        val tag = root.getString("tag_name")
        if (tag == BuildConfig.VERSION_NAME || tag == "v" + BuildConfig.VERSION_NAME) return@withContext null
        val assets = root.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val url = a.getString("browser_download_url")
            if (name == "newt-html-hoster-release.apk") apk = url
            if (name == "newt-html-hoster-release.apk.sha256") sha = url
        }
        apk?.let { AppRelease(tag, it, sha) }
    }

    suspend fun downloadAndVerify(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "newt-html-hoster-update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        connection.inputStream.use { input ->
            out.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        connection.disconnect()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        release.sha256Url?.let { shaUrl ->
            val expected = readText(shaUrl).trim().substringBefore(' ').lowercase()
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid release checksum" }
            require(hash == expected) { "APK checksum mismatch" }
        }

        require(sameSigningCertificate(context, out)) { "APK signing certificate mismatch" }
        out
    }

    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Suppress("DEPRECATION")
    private fun sameSigningCertificate(context: Context, archive: File): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val current = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners ?: return false
            val candidate = pm.getPackageArchiveInfo(archive.absolutePath, flags)?.signingInfo?.apkContentsSigners ?: return false
            fingerprints(current.map { it.toByteArray() }) == fingerprints(candidate.map { it.toByteArray() })
        } else {
            val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: return false
            val candidate = pm.getPackageArchiveInfo(archive.absolutePath, PackageManager.GET_SIGNATURES)?.signatures ?: return false
            fingerprints(current.map { it.toByteArray() }) == fingerprints(candidate.map { it.toByteArray() })
        }
    }

    private fun fingerprints(certs: List<ByteArray>): Set<String> =
        certs.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        }.toSet()

    private fun readText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NewtHtmlHoster/" + BuildConfig.VERSION_NAME)
        }
        return try {
            require(c.responseCode in 200..299) { "HTTP " + c.responseCode }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
