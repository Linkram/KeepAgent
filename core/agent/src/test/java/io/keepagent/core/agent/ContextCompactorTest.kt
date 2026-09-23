package io.keepagent.core.agent

import io.keepagent.addonsapi.llm.ChatMessage
import io.keepagent.addonsapi.llm.LlmEvent
import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.addonsapi.llm.LlmModel
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.addonsapi.llm.LlmRequest
import io.keepagent.addonsapi.llm.LlmResult
import io.keepagent.addonsapi.llm.LlmUsage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextCompactorTest {
    private val provider = object : LlmProvider {
        override val id = "fake"
        override suspend fun listModels(): List<LlmModel> = emptyList()
        override suspend fun streamChat(
            request: LlmRequest,
            onEvent: suspend (LlmEvent) -> Unit,
        ) = LlmResult("durable summary", "", emptyList(), LlmUsage(), "stop")
    }

    @Test
    fun `does not compact below eighty percent`() = runBlocking {
        val messages = listOf(ChatMessage.user("small"), ChatMessage.assistant("answer"), ChatMessage.user("next"))
        assertNull(ContextCompactor(provider, "m", 10_000).compactIfNeeded(messages))
    }

    @Test
    fun `summarizes old messages and retains recent tail at eighty percent`() = runBlocking {
        val old = "x".repeat(28_000)
        val messages = listOf(
            ChatMessage.system("system"),
            ChatMessage.user(old),
            ChatMessage.assistant(old),
            ChatMessage.user("latest requirement"),
        )
        val result = ContextCompactor(provider, "m", 16_000).compactIfNeeded(messages)
        assertNotNull(result)
        assertTrue(result.summarizedMessages >= 1)
        assertEquals("latest requirement", result.messages.last().content)
        assertTrue(result.messages.any { it.content.contains("durable summary") })
    }

    @Test
    fun `image attachments reserve context without counting base64 text`() {
        val short = ChatMessage.user("look", listOf(ImagePart("image/png", "a")))
        val huge = ChatMessage.user("look", listOf(ImagePart("image/png", "a".repeat(1_000_000))))
        assertEquals(ContextCompactor.estimateTokens(short), ContextCompactor.estimateTokens(huge))
        assertTrue(ContextCompactor.estimateTokens(short) >= ContextCompactor.IMAGE_TOKEN_RESERVE)
    }
}
