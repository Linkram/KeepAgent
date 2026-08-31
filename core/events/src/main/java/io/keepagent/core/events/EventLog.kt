package io.keepagent.core.events

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Append-only JSONL persistence of the event stream — one JSON object per
 * line (spec §9). Backs the Console tab's replay (F-008); compaction is M2.
 */
class EventLog(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    fun append(event: AgentEvent) {
        val obj = buildJsonObject {
            put("seq", event.seq)
            put("ts", event.timestamp)
            put("kind", event.kind.name)
            put("source", event.source)
            put("summary", event.summary)
            if (event.detail.isNotEmpty()) {
                put("detail", JsonObject(event.detail.mapValues { (_, v) -> JsonPrimitive(v) }))
            }
        }
        val line = json.encodeToString(JsonObject.serializer(), obj)
        synchronized(lock) {
            file.appendText(line + "\n")
        }
    }

    /** Reads up to [limit] most recent events, oldest first. */
    fun tail(limit: Int = 500): List<AgentEvent> {
        if (!file.exists()) return emptyList()
        return synchronized(lock) {
            file.readLines()
                .filter { it.isNotBlank() }
                .takeLast(limit)
                .mapNotNull { line ->
                    runCatching {
                        val o = json.parseToJsonElement(line).jsonObject
                        AgentEvent(
                            seq = o["seq"]?.jsonPrimitive?.long ?: 0L,
                            timestamp = o["ts"]?.jsonPrimitive?.long ?: 0L,
                            kind = runCatching {
                                EventKind.valueOf(o["kind"]?.jsonPrimitive?.content ?: "SYSTEM")
                            }.getOrDefault(EventKind.SYSTEM),
                            source = o["source"]?.jsonPrimitive?.content ?: "?",
                            summary = o["summary"]?.jsonPrimitive?.content ?: "",
                            detail = o["detail"]?.jsonObject
                                ?.mapValues { it.value.jsonPrimitive.content }
                                ?: emptyMap(),
                        )
                    }.getOrNull()
                }
        }
    }
}
