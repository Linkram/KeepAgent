package io.keepagent.app.handoff

import io.keepagent.app.chat.ChatSession
import io.keepagent.app.chat.StoredTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffExporterTest {
    @Test fun exportsVersionedBoundedContinuationWithoutClaimingTests() {
        val root = Files.createTempDirectory("handoff-").toFile()
        File(root, "AGENTS.md").writeText("guidance")
        val chat = ChatSession(
            id = "c1", title = "work", createdAt = 1, updatedAt = 2,
            turns = listOf(StoredTurn(userText = "fix it", agentText = "changed it")),
            workspace = "demo", memorySummary = "Use the new parser.",
        )
        val text = HandoffExporter.create("demo", root, "test", null, chat, exportedAt = 3)
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, obj["schemaVersion"]?.jsonPrimitive?.content?.toInt())
        assertEquals("fix it", obj["goal"]?.jsonPrimitive?.content)
        assertTrue(text.contains("AGENTS.md"))
        assertTrue(text.contains("\"tests\":[]"))
        assertFalse(text.contains("guidance"))
        root.deleteRecursively()
    }
}
