package com.rrgmc.classapptriage.data

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The persisted login session. The password is never stored. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String? = null,
    val login: String = "",
)

/**
 * Stores the [Session] encrypted with an AES-256-GCM key kept in the Android
 * Keystore (non-exportable), with the ciphertext in private SharedPreferences.
 * If the key becomes unusable (e.g. app data restored on another device), the
 * stored session is discarded and the user logs in again.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun save(session: Session) {
        val plain = Json.encodeToString(Session.serializer(), session).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(plain)
        prefs.edit { putString(KEY_SESSION, Base64.encodeToString(blob, Base64.NO_WRAP)) }
        _session.value = session
    }

    fun clear() {
        prefs.edit { remove(KEY_SESSION) }
        _session.value = null
    }

    private fun load(): Session? {
        val encoded = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
            val plain = cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
            Json.decodeFromString(Session.serializer(), plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            prefs.edit { remove(KEY_SESSION) }
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "classapp_session"
        const val KEY_SESSION = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
