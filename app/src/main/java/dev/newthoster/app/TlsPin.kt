package dev.newthoster.app

import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import android.util.Base64

object TlsPin {
    fun fetchSpkiSha256(endpoint: String): String {
        val uri = URI(endpoint)
        require(uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank())
        require(uri.userInfo == null)
        require(uri.fragment == null)
        val url = URL("https", uri.host, if (uri.port == -1) 443 else uri.port, "/")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 7_000
            instanceFollowRedirects = false
            requestMethod = "HEAD"
        }
        try {
            conn.connect()
            val cert = conn.serverCertificates.first()
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
        } finally {
            conn.disconnect()
        }
    }
}
