package io.keepagent.core.agent

import io.keepagent.addonsapi.ToolPermission
import io.keepagent.addonsapi.llm.*
import io.keepagent.core.events.EventBus
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DelegatingExecutorTest {
    @Test fun specialistGetsOnlyReadToolsAndNoParentTranscript() = runBlocking {
        val requests = mutableListOf<LlmRequest>()
        val provider = object : LlmProvider {
            override val id = "fake"
            override suspend fun listModels() = emptyList<LlmModel>()
            override suspend fun streamChat(request: LlmRequest, onEvent: suspend (LlmEvent) -> Unit): LlmResult {
                requests += request
                return LlmResult("Found src/main.kt", "", emptyList(), LlmUsage(), "stop")
            }
        }
        val bus = EventBus()
        val gate = ApprovalGate(bus, { ApprovalMode.NEVER_ASK })
        val tools = listOf(ToolSpec("read", "read", ToolPermission.READ, "{}"),
            ToolSpec("write", "write", ToolPermission.WRITE, "{}"), DelegatingExecutor.SPEC)
        val executor = DelegatingExecutor(provider, "fake", tools, ToolExecutor { _, _ -> ToolOutcome(true, "read") }, gate, bus, 8192, "Workspace test")
        repeat(3) { assertTrue(executor.execute("delegate", """{"task":"Find the entry point"}""").ok) }
        assertFalse(executor.execute("delegate", """{"task":"Again"}""").ok)
        assertEquals(3, requests.size)
        assertEquals(listOf("read"), requests.first().tools.map { it.name })
        assertEquals(2, requests.first().messages.size)
        assertEquals("Find the entry point", requests.first().messages.last().content)
    }
}
