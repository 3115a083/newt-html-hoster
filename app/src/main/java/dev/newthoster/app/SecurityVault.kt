package dev.newthoster.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_config", Context.MODE_PRIVATE)
    private val alias = "newt_hoster_vault_v2"

    data class ConnectionConfig(
        val endpoint: String,
        val newtId: String,
        val secret: CharArray,
        val certPinSha256: String
    ) {
        fun wipe() = secret.fill('\u0000')
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    fun hasSecret(): Boolean = prefs.contains("config_ct")

    fun save(endpoint: String, newtId: String, secret: CharArray, certPinSha256: String) {
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        require(cleanEndpoint.startsWith("https://")) { "Pangolin endpoint must use HTTPS" }
        require(newtId.isNotBlank()) { "Newt ID required" }
        require(secret.isNotEmpty()) { "Newt secret required" }
        require(certPinSha256.startsWith("sha256/")) { "TLS pin required" }

        val clear = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(cleanEndpoint)
                out.writeUTF(newtId.trim())
                out.writeUTF(certPinSha256)
                val secretBytes = secret.concatToString().toByteArray(Charsets.UTF_8)
                try {
                    out.writeInt(secretBytes.size)
                    out.write(secretBytes)
                } finally {
                    secretBytes.fill(0)
                }
            }
            bytes.toByteArray()
        }

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(clear)
            try {
                prefs.edit()
                    .clear()
                    .putString("config_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putString("config_ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .apply()
            } finally {
                ciphertext.fill(0)
            }
        } finally {
            clear.fill(0)
            secret.fill('\u0000')
        }
    }

    fun load(): ConnectionConfig? {
        val ivB64 = prefs.getString("config_iv", null) ?: return null
        val ctB64 = prefs.getString("config_ct", null) ?: return null
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ctB64, Base64.NO_WRAP)
        val clear = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }

        try {
            DataInputStream(ByteArrayInputStream(clear)).use { input ->
                val endpoint = input.readUTF()
                val id = input.readUTF()
                val pin = input.readUTF()
                val n = input.readInt()
                require(n in 1..16_384) { "Invalid credential blob" }
                val secretBytes = ByteArray(n)
                input.readFully(secretBytes)
                return try {
                    ConnectionConfig(endpoint, id, secretBytes.toString(Charsets.UTF_8).toCharArray(), pin)
                } finally {
                    secretBytes.fill(0)
                }
            }
        } finally {
            clear.fill(0)
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        if (ks.containsAlias("newt_hoster_vault_v1")) ks.deleteEntry("newt_hoster_vault_v1")
    }
}
