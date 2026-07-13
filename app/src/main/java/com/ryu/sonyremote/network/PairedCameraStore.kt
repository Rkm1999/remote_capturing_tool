package com.ryu.sonyremote.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class PairedCamera(
    val ssid: String,
    val encryptedPassword: String = "",
    val autoConnect: Boolean = false,
    val id: String = ssid,
    val displayName: String = ssid,
) {
    val canRequestWifi: Boolean get() = encryptedPassword.isNotBlank()
}

class PairedCameraStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_cameras", 0)

    fun load(): List<PairedCamera> = runCatching {
        val array = JSONArray(preferences.getString(KEY_CAMERAS, "[]") ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val ssid = item.optString("ssid")
            PairedCamera(
                ssid = ssid,
                encryptedPassword = item.optString("encryptedPassword"),
                autoConnect = item.optBoolean("autoConnect"),
                id = item.optString("id", ssid),
                displayName = item.optString("displayName", ssid),
            )
        }
    }.getOrDefault(emptyList())

    fun password(camera: PairedCamera): String = decrypt(camera.encryptedPassword)

    fun rememberDiscovered(id: String, displayName: String, ssid: String = ""): List<PairedCamera> {
        val normalized = id.trim()
        val existing = load()
        if (normalized.isBlank() || existing.any { it.id == normalized || (ssid.isNotBlank() && it.ssid == ssid) }) {
            return existing
        }
        return persist(existing + PairedCamera(
            ssid = ssid,
            encryptedPassword = "",
            autoConnect = true,
            id = normalized,
            displayName = displayName,
        ))
    }

    fun save(ssid: String, password: String, autoConnect: Boolean): List<PairedCamera> {
        val camera = PairedCamera(ssid.trim(), encrypt(password), autoConnect)
        return persist(load().filterNot { it.id == camera.id } + camera)
    }

    fun setAutoConnect(id: String, enabled: Boolean): List<PairedCamera> =
        persist(load().map { if (it.id == id) it.copy(autoConnect = enabled) else it })

    fun remove(id: String): List<PairedCamera> = persist(load().filterNot { it.id == id })

    private fun persist(cameras: List<PairedCamera>): List<PairedCamera> {
        val array = JSONArray()
        cameras.forEach { camera ->
            array.put(JSONObject().apply {
                put("ssid", camera.ssid)
                put("encryptedPassword", camera.encryptedPassword)
                put("autoConnect", camera.autoConnect)
                put("id", camera.id)
                put("displayName", camera.displayName)
            })
        }
        preferences.edit().putString(KEY_CAMERAS, array.toString()).apply()
        return cameras
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_CAMERAS = "cameras"
        const val KEY_ALIAS = "remote_capture_camera_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
