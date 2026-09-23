package io.keepagent.app.runner

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.keepagent.core.settings.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit companion connection. Credentials are encrypted with a non-exportable Android key. */
class RunnerConnection(private val settings: SettingsStore) {
    var endpoint: String
        get() = settings.getString("runner", "endpoint").orEmpty()
        private set(value) = settings.setString("runner", "endpoint", value)

    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("keepagent-runner", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("keepagent-runner", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(url: String, token: String) {
        val normalized = url.trim().trimEnd('/')
        val uri = java.net.URI(normalized)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "Enter the companion origin, e.g. http://127.0.0.1:8765" }
        require(token.length >= 24 || (token.isEmpty() && normalized == endpoint && configured())) { "Enter a token with at least 24 characters" }
        if (token.isNotEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            settings.setString("runner", "token", Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
        }
        endpoint = normalized
    }

    fun configured() = endpoint.isNotBlank() && !settings.getString("runner", "token").isNullOrBlank()

    fun disconnect() {
        endpoint = ""
        settings.setString("runner", "token", "")
    }

    fun request(path: String, body: String? = null): String {
        check(configured()) { "Pair an execution target in Tools → Runner first." }
        val encrypted = Base64.decode(settings.getString("runner", "token"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        }
        val token = String(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)))
        val request = Request.Builder().url(endpoint + path).header("Authorization", "Bearer $token")
        if (body != null) request.post(body.toRequestBody("application/json".toMediaType()))
        return client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Companion HTTP ${response.code}: ${text.take(500)}" }
            text
        }
    }
}
