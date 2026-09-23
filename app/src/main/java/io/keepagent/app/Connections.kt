package io.keepagent.app

import io.keepagent.core.settings.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    /** Model selected in Chat for this provider. */
    val model: String = "",
    /** Per-model settings. Older single-model records migrate in [from]. */
    val models: List<ApiModelConfig> = emptyList(),
) {
    val activeModelConfig: ApiModelConfig?
        get() = models.firstOrNull { it.id == model } ?: models.firstOrNull()

    val effectiveContextLimit: Int
        get() = activeModelConfig?.effectiveContextLimit ?: DEFAULT_CONTEXT_LIMIT

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("models", buildJsonArray { models.forEach { add(it.toJson()) } })
    }

    companion object {
        fun from(obj: JsonObject): ApiConnection? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val activeModel = obj["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val configured = (obj["models"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(ApiModelConfig::from) }
                .orEmpty()
                .ifEmpty {
                    // Seamless migration from the previous one-model schema.
                    if (activeModel.isBlank()) emptyList() else listOf(
                        ApiModelConfig(
                            id = activeModel,
                            providerContextLength = obj["contextWindow"]?.jsonPrimitive?.intOrNull,
                            contextLength = obj["contextOverride"]?.jsonPrimitive?.intOrNull,
                        ),
                    )
                }
            return ApiConnection(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull ?: "",
                model = activeModel.takeIf { id -> configured.any { it.id == id } }
                    ?: configured.firstOrNull()?.id.orEmpty(),
                models = configured,
            )
        }

        const val DEFAULT_CONTEXT_LIMIT = 128_000
    }
}

/** Settings belonging to one model exposed by an API connection. */
@Serializable
data class ApiModelConfig(
    val id: String,
    /** Maximum/loaded context reported by the provider. */
    val providerContextLength: Int? = null,
    /** User-entered context length; null means use [providerContextLength]. */
    val contextLength: Int? = null,
) {
    val effectiveContextLimit: Int
        get() = contextLength ?: providerContextLength ?: ApiConnection.DEFAULT_CONTEXT_LIMIT

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        providerContextLength?.let { put("providerContextLength", it) }
        contextLength?.let { put("contextLength", it) }
    }

    companion object {
        fun from(obj: JsonObject): ApiModelConfig? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return ApiModelConfig(
                id = id,
                providerContextLength = obj["providerContextLength"]?.jsonPrimitive?.intOrNull,
                contextLength = obj["contextLength"]?.jsonPrimitive?.intOrNull,
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

    fun add(
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<ApiModelConfig>,
        activeModel: String = models.firstOrNull()?.id.orEmpty(),
    ): ApiConnection {
        val conn = ApiConnection(
            id = "c-${System.currentTimeMillis()}",
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = activeModel.takeIf { id -> models.any { it.id == id } }
                ?: models.firstOrNull()?.id.orEmpty(),
            models = models,
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

    /** Selects a model and immediately applies context metadata discovered for it. */
    fun setActiveModel(model: String, contextWindow: Int?) {
        val conn = active() ?: run {
            settings.setString(SettingsStore.NS_MODEL, "model", model)
            return
        }
        val id = model.trim()
        val configured = conn.models.toMutableList()
        val index = configured.indexOfFirst { it.id == id }
        if (index >= 0 && contextWindow != null) {
            configured[index] = configured[index].copy(providerContextLength = contextWindow)
        } else if (index < 0) {
            configured += ApiModelConfig(id, providerContextLength = contextWindow)
        }
        update(conn.copy(model = id, models = configured))
    }

    /** Refreshes metadata for the current model without changing the selection. */
    fun updateActiveContext(model: String, contextWindow: Int?) {
        val conn = active() ?: return
        val index = conn.models.indexOfFirst { it.id == model }
        if (index >= 0 && contextWindow != null &&
            conn.models[index].providerContextLength != contextWindow
        ) {
            val configured = conn.models.toMutableList()
            configured[index] = configured[index].copy(providerContextLength = contextWindow)
            update(conn.copy(models = configured))
        }
    }

    /** Updates provider-reported maxima for every configured model in one write. */
    fun updateActiveModelMetadata(discovered: List<io.keepagent.addonsapi.llm.LlmModel>) {
        val conn = active() ?: return
        val byId = discovered.associateBy { it.id }
        var changed = false
        val configured = conn.models.map { config ->
            val reported = byId[config.id]?.contextWindow
            if (reported != null && reported != config.providerContextLength) {
                changed = true
                config.copy(providerContextLength = reported)
            } else {
                config
            }
        }
        if (changed) update(conn.copy(models = configured))
    }

    private fun mirrorToProfile(conn: ApiConnection) {
        settings.setString(SettingsStore.NS_MODEL, "baseUrl", conn.baseUrl)
        settings.setString(SettingsStore.NS_MODEL, "apiKey", conn.apiKey)
        settings.setString(SettingsStore.NS_MODEL, "model", conn.model)
        settings.setString(SettingsStore.NS_MODEL, "contextLimit", conn.effectiveContextLimit.toString())
    }

    private fun deactivate() {
        settings.setString(SettingsStore.NS_CONNECTIONS, KEY_ACTIVE, "")
        settings.setString(SettingsStore.NS_MODEL, "baseUrl", "")
        settings.setString(SettingsStore.NS_MODEL, "apiKey", "")
        settings.setString(SettingsStore.NS_MODEL, "model", "")
        settings.setString(SettingsStore.NS_MODEL, "contextLimit", "")
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
