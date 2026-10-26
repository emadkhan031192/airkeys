package com.airkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Plain settings (not secrets). */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("airkeys_settings", Context.MODE_PRIVATE)
    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1.0f)
        set(v) { prefs.edit().putFloat("sensitivity", v.coerceIn(0.25f, 3f)).apply() }
    var scrollSpeed: Float
        get() = prefs.getFloat("scroll_speed", 1.0f)
        set(v) { prefs.edit().putFloat("scroll_speed", v.coerceIn(0.25f, 3f)).apply() }
    var naturalScroll: Boolean
        get() = prefs.getBoolean("natural_scroll", false)
        set(v) { prefs.edit().putBoolean("natural_scroll", v).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) { prefs.edit().putBoolean("haptics", v).apply() }
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(v) { prefs.edit().putBoolean("keep_screen_on", v).apply() }
    var showClickButtons: Boolean
        get() = prefs.getBoolean("click_buttons", true)
        set(v) { prefs.edit().putBoolean("click_buttons", v).apply() }
    var touchpadLock: Boolean
        get() = prefs.getBoolean("touchpad_lock", false)
        set(v) { prefs.edit().putBoolean("touchpad_lock", v).apply() }
    var lastDeviceAddress: String?
        get() = prefs.getString("last_device", null)
        set(v) { prefs.edit().putString("last_device", v).apply() }
    var lastDeviceName: String?
        get() = prefs.getString("last_device_name", null)
        set(v) { prefs.edit().putString("last_device_name", v).apply() }
}

/**
 * Pairing key storage: the 32-byte key is encrypted with an AES key held in
 * the Android Keystore, so it is never stored in plaintext. Keyed by PC address.
 */
class PairingKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("airkeys_pairing", Context.MODE_PRIVATE)
    private val alias = "airkeys_pairing_key"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(alias, null)?.let { return it as SecretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }

    fun save(address: String, key: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(key)
        val combined = cipher.iv + ct
        prefs.edit().putString("key_$address", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun load(address: String): ByteArray? {
        val raw = prefs.getString("key_$address", null) ?: return null
        val combined = Base64.decode(raw, Base64.NO_WRAP)
        if (combined.size < 12 + 16) return null
        val iv = combined.copyOfRange(0, 12); val ct = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return try { cipher.doFinal(ct) } catch (e: Exception) { null }
    }

    /** Fallback for when a hotspot/USB IP changed: return any stored LAN key.
     *  Only correct when one LAN PC is paired; with several, the server
     *  rejects a wrong key and the phone shows "Pairing failed" instead
     *  of silently dropping input, so the user re-enters the code. */
    fun loadFirstLan(): ByteArray? {
        val key = prefs.all.keys.firstOrNull { it.startsWith("key_lan:") } ?: return null
        return load(key.removePrefix("key_"))
    }

    fun clear(address: String) { prefs.edit().remove("key_$address").apply() }
}
