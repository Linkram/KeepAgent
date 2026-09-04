package io.keepagent.app

import io.keepagent.core.settings.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An API connection (spec §14 Q4 surface, M1.1): a named OpenAI-compatible
 * endpoint — OpenRouter, a local server, or any custom base URL. The active
 * connection's profile is mirrored into [SettingsStore.NS_MODEL], which the
 * provider add-on and the chat model chip read; no restart is needed.
 */
@Serializable
data class ApiConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
    }

    companion object {
        fun from(obj: JsonObject): ApiConnection? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return ApiConnection(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull ?: "",
                model = obj["model"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }
}

/**
 * CRUD + activation for [ApiConnection]s, persisted as a JSON array in the
 * `connections` settings namespace (spec §9).
 */
class ConnectionsStore(private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _activeConnection = MutableStateFlow(active())

    /** Observable source of truth for screens that depend on the active endpoint. */
    val activeConnection = _activeConnection.asStateFlow()

    fun list(): List<ApiConnection> {
        val raw = settings.getString(SettingsStore.NS_CONNECTIONS, KEY_LIST) ?: return emptyList()
        return try {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { obj -> (obj as? JsonObject)?.let(ApiConnection::from) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): ApiConnection? = list().firstOrNull { it.id == id }

    fun activeId(): String? = settings.getString(SettingsStore.NS_CONNECTIONS, KEY_ACTIVE)

    fun active(): ApiConnection? {
        val id = activeId() ?: return null
        return get(id)
    }

    fun add(name: String, baseUrl: String, apiKey: String, model: String): ApiConnection {
        val conn = ApiConnection(
            id = "c-${System.currentTimeMillis()}",
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
        )
        persist(list() + conn)
        return conn
    }

    fun update(conn: ApiConnection) {
        persist(list().map { if (it.id == conn.id) conn else it })
        if (activeId() == conn.id) {
            mirrorToProfile(conn)
            _activeConnection.value = conn
        }
    }

    fun remove(id: String) {
        persist(list().filter { it.id != id })
        if (activeId() == id) deactivate()
    }

    /** Activates [id] (or null to deactivate): mirrors the profile into NS_MODEL. */
    fun setActive(id: String?) {
        val conn = id?.let(::get)
        if (conn == null) {
            deactivate()
            return
        }
        settings.setString(SettingsStore.NS_CONNECTIONS, KEY_ACTIVE, conn.id)
        mirrorToProfile(conn)
        _activeConnection.value = conn
    }

    /**
     * Copies the current NS_MODEL values back into the active record so the
     * gear dialog / model dropdown and this list never drift apart.
     */
    fun syncActiveFromProfile() {
        val conn = active() ?: return
        update(
            conn.copy(
                baseUrl = settings.getString(SettingsStore.NS_MODEL, "baseUrl")?.trim() ?: "",
                apiKey = settings.getString(SettingsStore.NS_MODEL, "apiKey")?.trim() ?: "",
                model = settings.getString(SettingsStore.NS_MODEL, "model")?.trim() ?: "",
            ),
        )
    }

    private fun mirrorToProfile(conn: ApiConnection) {
        settings.setString(SettingsStore.NS_MODEL, "baseUrl", conn.baseUrl)
        settings.setString(SettingsStore.NS_MODEL, "apiKey", conn.apiKey)
        settings.setString(SettingsStore.NS_MODEL, "model", conn.model)
    }

    private fun deactivate() {
        settings.setString(SettingsStore.NS_CONNECTIONS, KEY_ACTIVE, "")
        settings.setString(SettingsStore.NS_MODEL, "baseUrl", "")
        settings.setString(SettingsStore.NS_MODEL, "apiKey", "")
        settings.setString(SettingsStore.NS_MODEL, "model", "")
        _activeConnection.value = null
    }

    private fun persist(conns: List<ApiConnection>) {
        val arr = buildJsonArray { conns.forEach { add(it.toJson()) } }
        settings.setString(SettingsStore.NS_CONNECTIONS, KEY_LIST, arr.toString())
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_ACTIVE = "activeId"
    }
}
