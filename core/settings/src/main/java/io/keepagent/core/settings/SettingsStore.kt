package io.keepagent.core.settings

import android.content.Context
import android.content.SharedPreferences
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

    fun get(namespace: String): JsonObject {
        val raw = prefs.getString(key(namespace), null) ?: return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    fun put(namespace: String, patch: JsonObject) {
        val merged = JsonObject(get(namespace) + patch)
        prefs.edit()
            .putString(key(namespace), json.encodeToString(JsonObject.serializer(), merged))
            .apply()
    }

    fun setString(namespace: String, field: String, value: String) =
        put(namespace, buildJsonObject { put(field, value) })

    fun getString(namespace: String, field: String, default: String? = null): String? =
        get(namespace)[field]?.jsonPrimitive?.contentOrNull ?: default

    private fun key(namespace: String) = "ns.$namespace"

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
