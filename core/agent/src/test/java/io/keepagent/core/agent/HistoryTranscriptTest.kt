package io.keepagent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTranscriptTest {

    private fun line(name: String, summary: String, status: ToolLine.Status) =
        ToolLine(id = "t-$name", name = name, summary = summary, status = status)

    @Test
    fun emptyYieldsNull() {
        assertNull(HistoryTranscript.render(emptyList()))
    }

    @Test
    fun onlyRunningLinesYieldNull() {
        assertNull(HistoryTranscript.render(listOf(line("read", "read(a)", ToolLine.Status.RUNNING))))
    }

    @Test
    fun singleOkLine() {
        val out = HistoryTranscript.render(listOf(line("write", "write(path=a.html)", ToolLine.Status.OK)))
        assertEquals("(tools) write(path=a.html) → ok", out)
    }

    @Test
    fun mixedStatuses() {
        val out = HistoryTranscript.render(
            listOf(
                line("read", "read(a)", ToolLine.Status.OK),
                line("edit", "edit(path=a)", ToolLine.Status.ERROR),
                line("write", "write(path=b)", ToolLine.Status.DENIED),
            ),
        )
        assertEquals("(tools) read(a) → ok; edit(path=a) → ERROR; write(path=b) → denied", out)
    }

    @Test
    fun longTurnsCollapseToMore() {
        val lines = (1..20).map { line("write", "write(path=f$it)", ToolLine.Status.OK) }
        val out = HistoryTranscript.render(lines)!!
        assertTrue(out.endsWith("; +8 more"), "unexpected tail: $out")
        assertTrue(out.contains("write(path=f12) → ok"))
        assertTrue(!out.contains("write(path=f13)"))
    }

    @Test
    fun runningLinesAreIgnoredButCountedExcluded() {
        val out = HistoryTranscript.render(
            listOf(
                line("read", "read(a)", ToolLine.Status.OK),
                line("grep", "grep(x)", ToolLine.Status.RUNNING),
            ),
        )
        assertEquals("(tools) read(a) → ok", out)
    }
}
