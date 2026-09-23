package io.keepagent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunActivityTest {
    @Test
    fun `thinking around a tool remains in execution order`() {
        val run = AgentRun()
        run.appendThinking("first thought")
        run.addToolLine(ToolLine("1", "read", "read(file)", ToolLine.Status.RUNNING))
        run.setToolLine("1") { it.copy(status = ToolLine.Status.OK) }
        run.appendThinking("second thought")
        run.flushStreams()

        val activities = run.activities.value
        assertEquals(3, activities.size)
        assertEquals("first thought", (activities[0] as RunActivity.Thinking).text)
        assertEquals(ToolLine.Status.OK, (activities[1] as RunActivity.Tool).line.status)
        assertEquals("second thought", (activities[2] as RunActivity.Thinking).text)
    }

    @Test
    fun `large streamed response is complete after flush`() {
        val run = AgentRun()
        repeat(20_000) { run.appendText("token ") }
        run.flushStreams()
        assertEquals(120_000, run.text.value.length)
        assertTrue(run.estimatedCompletionTokens() >= 30_000)
    }
}
