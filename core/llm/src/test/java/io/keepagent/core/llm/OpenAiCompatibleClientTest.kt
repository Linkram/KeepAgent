package io.keepagent.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiCompatibleClientTest {
    private val json = Json

    @Test
    fun `reads OpenRouter context length`() {
        val model = parse("""{"id":"vendor/model","name":"Model","context_length":131072}""")

        assertEquals("vendor/model", model?.id)
        assertEquals("Model", model?.name)
        assertEquals(131_072, model?.contextWindow)
    }

    @Test
    fun `reads nested local-server context length`() {
        val model = parse("""{"id":"local","config":{"max_model_len":"65536"}}""")

        assertEquals(65_536, model?.contextWindow)
    }

    @Test
    fun `reads alternate context window name`() {
        val model = parse("""{"id":"local","metadata":{"context_window_size":262144}}""")

        assertEquals(262_144, model?.contextWindow)
    }

    @Test
    fun `leaves context unknown when endpoint omits metadata`() {
        val model = parse("""{"id":"plain-model"}""")

        assertNull(model?.contextWindow)
    }

    @Test
    fun `LM Studio loaded context overrides model file maximum`() {
        val root = json.parseToJsonElement(
            """{
                "models":[{
                    "key":"qwen",
                    "max_context_length":262144,
                    "loaded_instances":[{"id":"qwen","config":{"context_length":294912}}]
                }]
            }""",
        ).jsonObject

        assertEquals(294_912, OpenAiCompatibleClient.lmStudioContextById(root)["qwen"])
    }

    private fun parse(raw: String) = OpenAiCompatibleClient.modelFromJson(
        json.parseToJsonElement(raw).jsonObject,
    )
}
