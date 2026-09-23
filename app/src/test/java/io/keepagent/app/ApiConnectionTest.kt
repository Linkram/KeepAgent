package io.keepagent.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiConnectionTest {
    @Test
    fun `migrates old single-model context settings`() {
        val old = Json.parseToJsonElement(
            """{"id":"c1","name":"Local","baseUrl":"http://host/v1","model":"qwen","contextWindow":294912}""",
        ).jsonObject

        val connection = ApiConnection.from(old)!!

        assertEquals("qwen", connection.model)
        assertEquals(1, connection.models.size)
        assertEquals(294_912, connection.models.single().providerContextLength)
        assertNull(connection.models.single().contextLength)
        assertEquals(294_912, connection.effectiveContextLimit)
    }

    @Test
    fun `context setting belongs to selected model`() {
        val connection = ApiConnection(
            id = "c1",
            name = "Provider",
            baseUrl = "http://host/v1",
            model = "large",
            models = listOf(
                ApiModelConfig("small", providerContextLength = 32_768),
                ApiModelConfig("large", providerContextLength = 294_912, contextLength = 200_000),
            ),
        )

        assertEquals(200_000, connection.effectiveContextLimit)
        assertEquals(32_768, connection.copy(model = "small").effectiveContextLimit)
    }

    @Test
    fun `round trips multiple model settings`() {
        val original = ApiConnection(
            id = "c1",
            name = "Provider",
            baseUrl = "http://host/v1",
            model = "b",
            models = listOf(
                ApiModelConfig("a", providerContextLength = 65_536),
                ApiModelConfig("b", providerContextLength = 294_912, contextLength = 288_000),
            ),
        )

        assertEquals(original, ApiConnection.from(original.toJson()))
    }
}
