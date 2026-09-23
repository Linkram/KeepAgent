package io.keepagent.app.handoff

import io.keepagent.app.chat.ChatSession
import io.keepagent.app.git.GitService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Versioned, harness-neutral continuation state embedded in exported projects. */
object HandoffExporter {
    fun create(
        workspaceName: String,
        root: File,
        appVersion: String,
        git: GitService.HandoffState?,
        chat: ChatSession?,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val recent = chat?.turns.orEmpty().takeLast(3)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("exportedAt", exportedAt)
            put("project", buildJsonObject { put("name", workspaceName) })
            put("source", buildJsonObject {
                put("harness", "KeepAgent")
                put("version", appVersion)
            })
            put("git", gitObject(git))
            put("goal", recent.lastOrNull()?.userText?.take(8_000) ?: "")
            put("decisions", chat?.memorySummary?.take(12_000) ?: "")
            put("completedWork", recent.lastOrNull()?.agentText?.take(8_000) ?: "")
            put("pendingWork", JsonArray(emptyList()))
            put("tests", JsonArray(emptyList()))
            put("instructionFiles", buildJsonArray {
                listOf("AGENTS.md", "CLAUDE.md", "PLAN.md", "HANDOFF.md", "README.md")
                    .filter { File(root, it).isFile }
                    .forEach { add(JsonPrimitive(it)) }
            })
            put("recentContext", buildJsonArray {
                recent.forEach { turn ->
                    add(buildJsonObject {
                        put("user", turn.userText.take(8_000))
                        put("assistant", turn.agentText.take(8_000))
                        turn.modelId?.let { put("model", it) }
                    })
                }
            })
        }.toString()
    }

    private fun gitObject(state: GitService.HandoffState?): JsonObject = buildJsonObject {
        if (state == null) {
            put("repository", false)
            return@buildJsonObject
        }
        put("repository", true)
        put("branch", state.branch?.let(::JsonPrimitive) ?: JsonNull)
        put("commit", state.commit?.let(::JsonPrimitive) ?: JsonNull)
        put("remote", state.remote?.let(::JsonPrimitive) ?: JsonNull)
        put("dirtyFiles", buildJsonArray {
            state.dirtyFiles.forEach { dirty ->
                add(buildJsonObject {
                    put("path", dirty.path)
                    put("sha256", dirty.sha256?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
    }
}
