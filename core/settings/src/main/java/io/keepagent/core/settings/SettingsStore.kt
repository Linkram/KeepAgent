package io.keepagent.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Typed, namespaced settings (spec §9). Backed by SharedPreferences holding
 * one JSON document per namespace — every module gets a stable settings
 * surface from M0. Room migration lands in M2.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("keepagent_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun get(namespace: String): JsonObject {
        val raw = prefs.getString(key(namespace), null) ?: return JsonObject(emptyMap())
        val stored = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return JsonObject(emptyMap())
        }

        var migrated = false
        val clear = JsonObject(stored.mapValues { (field, value) ->
            if (!isSensitive(namespace, field)) return@mapValues value
            val encoded = value.jsonPrimitive.contentOrNull ?: return@mapValues value
            if (encoded.isBlank()) return@mapValues value
            if (encoded.startsWith(ENCRYPTED_PREFIX)) {
                try {
                    kotlinx.serialization.json.JsonPrimitive(decrypt(encoded))
                } catch (_: Exception) {
                    // A restored backup cannot restore its non-exportable Keystore key.
                    // Clear the unusable credential instead of crashing or exposing it.
                    migrated = true
                    kotlinx.serialization.json.JsonPrimitive("")
                }
            } else {
                // Upgrade existing installs in place the first time the value is read.
                migrated = true
                value
            }
        })
        if (migrated) {
            write(namespace, clear)
        }
        return clear
    }

    @Synchronized
    fun put(namespace: String, patch: JsonObject) {
        val merged = JsonObject(get(namespace) + patch)
        write(namespace, merged)
    }

    fun setString(namespace: String, field: String, value: String) =
        put(namespace, buildJsonObject { put(field, value) })

    fun getString(namespace: String, field: String, default: String? = null): String? =
        get(namespace)[field]?.jsonPrimitive?.contentOrNull ?: default

    private fun key(namespace: String) = "ns.$namespace"

    /** Encrypt secrets field-by-field while preserving the public settings schema. */
    private fun write(namespace: String, clear: JsonObject) {
        val protected = JsonObject(clear.mapValues { (field, value) ->
            if (!isSensitive(namespace, field)) return@mapValues value
            val text = value.jsonPrimitive.contentOrNull ?: return@mapValues value
            if (text.isBlank()) value else kotlinx.serialization.json.JsonPrimitive(encrypt(text))
        })
        val encoded = json.encodeToString(JsonObject.serializer(), protected)
        val editor = prefs.edit().putString(key(namespace), encoded)
        // Secrets must be durable before their caller continues. Public preferences
        // keep the ordinary asynchronous path.
        if (clear.keys.any { isSensitive(namespace, it) }) {
            check(editor.commit()) { "Unable to persist encrypted settings" }
        } else {
            editor.apply()
        }
    }

    private fun isSensitive(namespace: String, field: String): Boolean =
        (namespace == NS_MODEL && field == "apiKey") ||
            (namespace == NS_CONNECTIONS && field == "list")

    private fun encrypt(clear: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(clear.toByteArray(Charsets.UTF_8))
        return ENCRYPTED_PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.removePrefix(ENCRYPTED_PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "Malformed encrypted setting" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val NS_GENERAL = "general"
        const val NS_SESSIONS = "sessions"
        const val NS_ADDONS = "addons"

        /**
         * The model profile (F-001): `baseUrl`, `apiKey`, `model`.
         * Read by the provider add-on on every use — no restart needed.
         */
        const val NS_MODEL = "model"

        /**
         * API connections (spec §14 Q4): `list` (JSON array of connections)
         * and `activeId`. The active connection is mirrored into [NS_MODEL].
         */
        const val NS_CONNECTIONS = "connections"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "keepagent.settings.v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENCRYPTED_PREFIX = "kae1:"
    }
}

/**
 * The binary file-access toggle (ADR-0002). [WORKSPACE] is the default and
 * confines the agent to the active workspace directory; [FULL] is a user
 * confirmation and is shown in the header. The enforcement lives in `core-fs`
 * (not built yet) — the typed surface exists from M0.
 */
enum class FileAccess {
    WORKSPACE,
    FULL;

    companion object {
        fun from(name: String?): FileAccess =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WORKSPACE
    }
}
