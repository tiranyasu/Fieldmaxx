package com.fieldmaxx.app.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Keeps the session token between app starts. [save] with null signs out. */
interface TokenVault {
    fun save(token: String?)
    fun load(): String?
}

/** For tests: AndroidKeyStore doesn't exist under Robolectric. */
class InMemoryTokenVault(@Volatile private var token: String? = null) : TokenVault {
    override fun save(token: String?) {
        this.token = token
    }

    override fun load(): String? = token
}

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(name = "session")
private val TOKEN = stringPreferencesKey("token")

/**
 * The token encrypted with AES-256-GCM under a key that never leaves AndroidKeyStore. DataStore holds
 * Base64(IV ‖ ciphertext+tag), with a fresh random 12-byte IV per save. The token is kept in memory once read, so
 * requests don't go to the keystore each time.
 */
class AndroidKeystoreTokenVault(context: Context) : TokenVault {
    private val store = context.applicationContext.sessionStore
    private var loaded = false
    private var token: String? = null

    @Synchronized
    override fun save(token: String?) {
        this.token = token
        loaded = true
        try {
            runBlocking { store.edit { if (token == null) it.remove(TOKEN) else it[TOKEN] = encrypt(token) } }
        } catch (e: Exception) {
            // Keystore or disk failure: this session still works; the next app start asks to sign in again.
            Log.w(LOG_TAG, "Could not store the session", e)
        }
    }

    @Synchronized
    override fun load(): String? {
        if (!loaded) {
            token = try {
                runBlocking { store.data.first()[TOKEN] }?.let(::decrypt)
            } catch (e: Exception) {
                // Unreadable (key gone after a reinstall or restore, keystore error, damaged file): sign in again.
                Log.w(LOG_TAG, "Could not read the stored session", e)
                null
            }
            loaded = true
        }
        return token
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // AndroidKeyStore picks the random IV
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV size ${iv.size}" }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(sealed: String): String {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val LOG_TAG = "TokenVault"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fieldmaxx-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
