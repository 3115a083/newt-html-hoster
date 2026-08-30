package dev.newthoster.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_config", Context.MODE_PRIVATE)
    private val alias = "newt_hoster_vault_v1"

    data class ConnectionConfig(
        val endpoint: String,
        val newtId: String,
        val secret: CharArray,
        val certPinSha256: String?
    )

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    fun hasSecret(): Boolean = prefs.contains("secret_ct")

    fun save(endpoint: String, newtId: String, secret: CharArray, certPinSha256: String?) {
        require(endpoint.startsWith("https://")) { "Pangolin endpoint must use HTTPS" }
        require(newtId.isNotBlank()) { "Newt ID required" }
        require(secret.isNotEmpty()) { "Newt secret required" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val clear = secret.concatToString().toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(clear)
        clear.fill(0)
        prefs.edit()
            .putString("endpoint", endpoint.trimEnd('/'))
            .putString("newt_id", newtId.trim())
            .putString("secret_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("secret_ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("cert_pin", certPinSha256)
            .apply()
        ciphertext.fill(0)
    }

    fun load(): ConnectionConfig? {
        val endpoint = prefs.getString("endpoint", null) ?: return null
        val id = prefs.getString("newt_id", null) ?: return null
        val iv = prefs.getString("secret_iv", null) ?: return null
        val ct = prefs.getString("secret_ct", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val clear = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))
        val chars = clear.toString(Charsets.UTF_8).toCharArray()
        clear.fill(0)
        return ConnectionConfig(endpoint, id, chars, prefs.getString("cert_pin", null))
    }

    fun clear() {
        prefs.edit().clear().apply()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
