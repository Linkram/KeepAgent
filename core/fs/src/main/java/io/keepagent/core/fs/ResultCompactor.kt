package io.keepagent.core.fs

/**
 * Compacts oversized tool results for small-model contexts (WS-1.3, F-026).
 *
 * A full dump of a 40K-char source file eats most of a small model's context
 * and usually isn't needed. Instead of a hard truncation that blinds the
 * model to the file's shape, keep a head and a tail (cut at line boundaries)
 * and replace the middle with a pointer the model can ACT ON: the exact
 * `read(path, offset=N)` call that pages into the omitted region.
 */
object ResultCompactor {

    /** Read results at or under this many chars pass through untouched. */
    const val SOFT_CAP_CHARS = 12_000
    const val HEAD_CHARS = 9_000
    const val TAIL_CHARS = 3_000

    /**
     * Compacts [text] when it exceeds [SOFT_CAP_CHARS]. [lineOffset] is the
     * 1-based file line that [text] starts at, so the pointer stays correct
     * when paging an already-sliced read.
     */
    fun compactRead(text: String, path: String, lineOffset: Int = 1): String {
        if (text.length <= SOFT_CAP_CHARS) return text
        var headEnd = lineCut(text, HEAD_CHARS, fromStart = true)
        var tailStart = lineCut(text, TAIL_CHARS, fromStart = false)
        // Degenerate file (one giant line): fall back to raw char cuts.
        if (headEnd <= 0) headEnd = minOf(HEAD_CHARS, text.length)
        if (tailStart >= text.length) tailStart = maxOf(0, text.length - TAIL_CHARS)
        if (tailStart <= headEnd) return text
        val head = text.substring(0, headEnd)
        val tail = text.substring(tailStart)
        val omitted = tailStart - headEnd
        // Line offsets are only meaningful when the content has lines. The
        // snapped head ends ON a newline, so its newline count is its line
        // count (no +1 for a trailing partial line).
        val pointer = if (text.contains('\n')) {
            val headLines = if (head.endsWith("\n")) {
                head.count { it == '\n' }
            } else {
                head.count { it == '\n' } + 1
            }
            "… [${omitted} chars omitted — continue: read(path=\"$path\", offset=${lineOffset + headLines})] …"
        } else {
            "… [${omitted} chars omitted] …"
        }
        return head + "\n" + pointer + "\n" + tail
    }

    /** Index of a cut at or before [chars] chars in, snapped to a line end. */
    private fun lineCut(text: String, chars: Int, fromStart: Boolean): Int {
        val limit = minOf(chars, text.length)
        if (fromStart) {
            var i = limit
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }
        var i = text.length - limit
        while (i < text.length && text[i] != '\n') i++
        return i
    }
}
