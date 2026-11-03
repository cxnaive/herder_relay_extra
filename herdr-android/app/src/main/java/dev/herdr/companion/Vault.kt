package dev.herdr.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials and editor drafts are encrypted with an installation-specific Android Keystore key. */
class Vault(context: Context) {
    private val preferences = context.getSharedPreferences("private_state", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("herdr-pocket", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("herdr-pocket", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): String? {
        val raw = preferences.getString(name, null) ?: return null
        val data = Base64.decode(raw, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
        }
    }
    @Synchronized fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存本机数据" }
    }
    @Synchronized fun remove(name: String) { check(preferences.edit().remove(name).commit()) }
    @Synchronized fun names(prefix: String): List<String> = preferences.all.keys.filter { it.startsWith(prefix) }.sorted()
}
