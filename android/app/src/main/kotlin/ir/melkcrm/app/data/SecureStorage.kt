package ir.melkcrm.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ir.melkcrm.client.remote.StoredTokens
import ir.melkcrm.client.remote.TokenStorage
import ir.melkcrm.client.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that never leaves the Android Keystore. Values are stored as
 * base64(iv || ciphertext) in a private preferences file that is excluded from backups
 * (res/xml/data_extraction_rules.xml), so a copied file is useless on another device.
 */
class KeystoreCipher(private val alias: String = "melkcrm.local.v1") {
    private val lock = Any()

    private fun key(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Null when the value cannot be decrypted (for example after the key was reset); callers treat it as absent. */
    fun decrypt(text: String): String? = try {
        val all = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LENGTH))
        String(c.doFinal(all, IV_LENGTH, all.size - IV_LENGTH), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}

/** Encrypted key/value storage for secrets: session tokens and the map provider key. */
class SecurePrefs(context: Context, private val cipher: KeystoreCipher = KeystoreCipher()) : SecretStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    override suspend fun get(name: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(name, null)?.let { cipher.decrypt(it) }
    }

    override suspend fun put(name: String, value: String?) = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        if (value == null) editor.remove(name) else editor.putString(name, cipher.encrypt(value))
        editor.commit()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) { prefs.edit().clear().commit(); Unit }
}

/** Access and refresh tokens, encrypted. The password itself is never stored. */
class KeystoreTokenStorage(private val prefs: SecurePrefs) : TokenStorage {
    override suspend fun load(): StoredTokens? {
        val text = prefs.get(KEY) ?: return null
        return try {
            val o = JSONObject(text)
            StoredTokens(o.getString("a"), o.getString("r"), o.getLong("e"))
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(tokens: StoredTokens?) {
        prefs.put(KEY, tokens?.let { JSONObject().put("a", it.accessToken).put("r", it.refreshToken).put("e", it.accessExpiresAt).toString() })
    }

    private companion object {
        const val KEY = "session.tokens"
    }
}
