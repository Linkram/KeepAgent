package io.keepagent.core.agent

/**
 * Compact tool-round transcript for chat history (WS-1.4).
 *
 * Without it, a turn that wrote 10 files looks, on the next turn, like just
 * the final text — the model has no idea which files exist or changed, so
 * follow-up work ("now add dark mode") cannot ground itself. With it, the
 * most recent turns carry a one-line transcript
 * (`(tools) write(path=a.html) → ok; edit(path=a.html) → ok; …`) so the
 * model can reference real files — at a small token cost, which matters
 * because small local models are the first-class target.
 */
object HistoryTranscript {

    /** How many of the most recent prior turns get a transcript. */
    const val RECENT_TURNS = 3

    /** Per-turn line cap before collapsing to "+N more". */
    const val MAX_LINES_PER_TURN = 12

    /**
     * One-line tool transcript for a finished turn, or null when the turn
     * had no completed tool calls (history stays text-only then).
     */
    fun render(toolLines: List<ToolLine>, maxLines: Int = MAX_LINES_PER_TURN): String? {
        val done = toolLines.filter { it.status != ToolLine.Status.RUNNING }
        if (done.isEmpty()) return null
        val shown = done.take(maxLines)
        val extra = done.size - shown.size
        val parts = shown.joinToString("; ") { line(it) }
        return "(tools) $parts" + if (extra > 0) "; +$extra more" else ""
    }

    private fun line(t: ToolLine): String =
        t.summary + " → " + when (t.status) {
            ToolLine.Status.OK -> "ok"
            ToolLine.Status.ERROR -> "ERROR"
            ToolLine.Status.DENIED -> "denied"
            ToolLine.Status.RUNNING -> "running"
        }
}
