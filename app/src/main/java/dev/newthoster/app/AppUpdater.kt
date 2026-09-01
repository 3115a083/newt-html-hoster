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

data class AppRelease(val tag: String, val apkUrl: String, val sha256Url: String)

object AppUpdater {
    private const val API = "https://api.github.com/repos/3115a083/newt-html-hoster/releases/latest"
    private const val RELEASE_PREFIX = "https://github.com/3115a083/newt-html-hoster/releases/download/"

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val root = JSONObject(readText(API))
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return@withContext null
        val tag = root.getString("tag_name")
        if (tag == BuildConfig.VERSION_NAME || tag == "v" + BuildConfig.VERSION_NAME) return@withContext null

        val assets = root.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.getString("name")) {
                "newt-html-hoster-release.apk" -> apk = a.getString("browser_download_url")
                "newt-html-hoster-release.apk.sha256" -> sha = a.getString("browser_download_url")
            }
        }
        val apkUrl = apk ?: return@withContext null
        val shaUrl = sha ?: return@withContext null
        require(apkUrl.startsWith(RELEASE_PREFIX)) { "Untrusted APK source" }
        require(shaUrl.startsWith(RELEASE_PREFIX)) { "Untrusted checksum source" }
        AppRelease(tag, apkUrl, shaUrl)
    }

    suspend fun downloadAndVerify(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        require(release.apkUrl.startsWith(RELEASE_PREFIX)) { "Untrusted APK source" }
        require(release.sha256Url.startsWith(RELEASE_PREFIX)) { "Untrusted checksum source" }

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(updateDir, "newt-html-hoster-update.apk")
        if (out.exists()) out.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NewtHtmlHoster/" + BuildConfig.VERSION_NAME)
            }
            try {
                require(connection.responseCode in 200..299) { "HTTP " + connection.responseCode }
                connection.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        try {
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                total += n
                                require(total <= 200L * 1024 * 1024) { "APK exceeds size limit" }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = readText(release.sha256Url).trim().substringBefore(' ').lowercase()
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid release checksum" }
            require(actual == expected) { "APK checksum mismatch" }
            require(sameSigningCertificate(context, out)) { "APK signing certificate mismatch" }
            require(candidateVersionCode(context, out) > installedVersionCode(context)) {
                "Update is not newer than installed app"
            }
            out
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
    }

    fun install(context: Context, apk: File) {
        require(apk.isFile) { "Update APK missing" }
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
    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun candidateVersionCode(context: Context, archive: File): Long {
        val info = context.packageManager.getPackageArchiveInfo(archive.absolutePath, 0)
            ?: error("Invalid APK")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
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
            instanceFollowRedirects = true
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
