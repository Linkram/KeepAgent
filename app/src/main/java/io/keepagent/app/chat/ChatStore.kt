package io.keepagent.app.chat

import io.keepagent.addonsapi.llm.ImagePart
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One tool invocation line as stored for display (args summary + result snippet). */
data class StoredTool(
    val name: String,
    val summary: String,
    val status: String,
    val detail: String? = null,
    /** UI extras (diff old/new content, file path). */
    val extra: Map<String, String> = emptyMap(),
)

/** A workspace file or folder attached to a user message (path + preview). */
data class StoredFile(
    val path: String,
    val isFolder: Boolean,
    val preview: String,
)

/** One exchange: a user message and the agent turn that answered it. */
data class StoredTurn(
    val userText: String,
    val images: List<ImagePart> = emptyList(),
    val files: List<StoredFile> = emptyList(),
    val agentText: String = "",
    val thinking: String = "",
    val tools: List<StoredTool> = emptyList(),
    val error: String? = null,
    /** Model id used for this turn. */
    val modelId: String? = null,
    /** The exact prompt (user text + inlined attachments) sent to the model. */
    val sentPrompt: String? = null,
    val usagePrompt: Int = 0,
    val usageCompletion: Int = 0,
    val elapsedMs: Long = 0,
    /** True when the user stopped the turn before it finished. */
    val interrupted: Boolean = false,
)

/** A persisted chat session: one JSON file under files/keepagent/chats/. */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: List<StoredTurn> = emptyList(),
)

/**
 * Chat history persistence (M1.3). Sessions are plain JSON files, written
 * atomically (tmp + rename), listed most-recently-updated first.
 */
class ChatStore(rootDir: File) {

    private val dir = rootDir.apply { mkdirs() }

    fun list(): List<ChatSession> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { parse(f) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun load(id: String): ChatSession? =
        runCatching { parse(File(dir, "$id.json")) }.getOrNull()

    fun latest(): ChatSession? = list().firstOrNull()

    fun save(session: ChatSession) {
        val target = File(dir, "${session.id}.json")
        val tmp = File(dir, "${session.id}.tmp")
        try {
            tmp.writeText(serialize(session))
            if (!tmp.renameTo(target)) target.writeText(serialize(session))
        } catch (_: Exception) {
            // A failed save loses the in-memory turn only; chat keeps working.
        }
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
    }

    private fun parse(f: File): ChatSession {
        val o = JSONObject(f.readText())
        // Android's org.json JSONArray is not Iterable — walk by index.
        val turns = mutableListOf<StoredTurn>()
        val turnsArr = o.optJSONArray("turns") ?: JSONArray()
        for (ti in 0 until turnsArr.length()) {
            val to = turnsArr.getJSONObject(ti)
            val images = mutableListOf<ImagePart>()
            val imgArr = to.optJSONArray("images")
            if (imgArr != null) {
                for (i in 0 until imgArr.length()) {
                    val jo = imgArr.getJSONObject(i)
                    images.add(ImagePart(jo.optString("mime"), jo.optString("data")))
                }
            }
            val files = mutableListOf<StoredFile>()
            val fileArr = to.optJSONArray("files")
            if (fileArr != null) {
                for (i in 0 until fileArr.length()) {
                    val jo = fileArr.getJSONObject(i)
                    files.add(StoredFile(jo.optString("path"), jo.optBoolean("folder"), jo.optString("preview")))
                }
            }
            val tools = mutableListOf<StoredTool>()
            val toolArr = to.optJSONArray("tools")
            if (toolArr != null) {
                for (i in 0 until toolArr.length()) {
                    val jo = toolArr.getJSONObject(i)
                    val detail = jo.optString("detail")
                    val extra = mutableMapOf<String, String>()
                    val ex = jo.optJSONObject("extra")
                    if (ex != null) {
                        val keys = ex.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = ex.optString(k)
                            if (v.isNotEmpty() || ex.isNull(k).not()) extra[k] = v
                        }
                    }
                    tools.add(
                        StoredTool(
                            jo.optString("name"),
                            jo.optString("summary"),
                            jo.optString("status"),
                            detail.takeIf { d -> d.isNotEmpty() },
                            extra,
                        ),
                    )
                }
            }
            turns.add(
                StoredTurn(
                    userText = to.optString("user"),
                    images = images,
                    files = files,
                    agentText = to.optString("agent"),
                    thinking = to.optString("thinking"),
                    error = if (to.isNull("error")) null else to.optString("error"),
                    tools = tools,
                    modelId = to.optString("model").takeIf { m -> m.isNotEmpty() },
                    sentPrompt = to.optString("sent").takeIf { s -> s.isNotEmpty() },
                    usagePrompt = to.optInt("usagePrompt"),
                    usageCompletion = to.optInt("usageCompletion"),
                    elapsedMs = to.optLong("elapsedMs"),
                    interrupted = to.optBoolean("interrupted"),
                ),
            )
        }
        return ChatSession(
            id = o.getString("id"),
            title = o.optString("title", "Untitled chat"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            turns = turns,
        )
    }

    private fun serialize(s: ChatSession): String {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("title", s.title)
        o.put("createdAt", s.createdAt)
        o.put("updatedAt", s.updatedAt)
        val turns = JSONArray()
        s.turns.forEach { t ->
            val to = JSONObject()
            to.put("user", t.userText)
            val images = JSONArray()
            t.images.forEach { i ->
                images.put(JSONObject().put("mime", i.mimeType).put("data", i.dataBase64))
            }
            to.put("images", images)
            val files = JSONArray()
            t.files.forEach { f ->
                files.put(JSONObject().put("path", f.path).put("folder", f.isFolder).put("preview", f.preview))
            }
            to.put("files", files)
            to.put("agent", t.agentText)
            to.put("thinking", t.thinking)
            to.put("error", t.error ?: JSONObject.NULL)
            if (t.modelId != null) to.put("model", t.modelId)
            if (t.sentPrompt != null) to.put("sent", t.sentPrompt)
            if (t.usagePrompt > 0) to.put("usagePrompt", t.usagePrompt)
            if (t.usageCompletion > 0) to.put("usageCompletion", t.usageCompletion)
            if (t.elapsedMs > 0) to.put("elapsedMs", t.elapsedMs)
            if (t.interrupted) to.put("interrupted", true)
            val tools = JSONArray()
            t.tools.forEach { tl ->
                val jo = JSONObject()
                jo.put("name", tl.name)
                jo.put("summary", tl.summary)
                jo.put("status", tl.status)
                if (tl.detail != null) jo.put("detail", tl.detail)
                if (tl.extra.isNotEmpty()) {
                    val ex = JSONObject()
                    tl.extra.forEach { (k, v) -> ex.put(k, v) }
                    jo.put("extra", ex)
                }
                tools.put(jo)
            }
            to.put("tools", tools)
            turns.put(to)
        }
        o.put("turns", turns)
        return o.toString()
    }

    companion object {
        fun newId(): String =
            "${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toLong()}"
    }
}
