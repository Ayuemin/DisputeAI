package com.ayuemin.disputeai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("disputeai_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(id: String, value: String) {
        if (id.isBlank() || value.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(PREFIX + id, packed).apply()
    }

    fun get(id: String): String {
        val packed = prefs.getString(PREFIX + id, "").orEmpty()
        if (packed.isBlank()) return ""
        return try {
            val parts = packed.split('.', limit = 2)
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove(PREFIX + id).apply()
            ""
        }
    }

    fun has(id: String): Boolean = get(id).isNotBlank()

    fun remove(id: String) {
        prefs.edit().remove(PREFIX + id).apply()
    }

    fun clearAll() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach(editor::remove)
        editor.apply()
        try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (_: Exception) { }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "DisputeAI.ApiKeys.v2"
        private const val PREFIX = "secret."
    }
}
