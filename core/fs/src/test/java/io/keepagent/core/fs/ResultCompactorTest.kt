package io.keepagent.core.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultCompactorTest {

    @Test
    fun smallTextPassesThrough() {
        val text = "line1\nline2\nline3"
        assertEquals(text, ResultCompactor.compactRead(text, "a.txt"))
    }

    @Test
    fun exactlyAtCapPassesThrough() {
        val text = "a".repeat(ResultCompactor.SOFT_CAP_CHARS)
        assertEquals(text, ResultCompactor.compactRead(text, "a.txt"))
    }

    @Test
    fun largeTextIsHeadTailWithPointer() {
        val lines = (1..1000).map { "line $it: padding padding padding" }
        val text = lines.joinToString("\n")
        val out = ResultCompactor.compactRead(text, "big.txt")
        assertTrue(out.length < text.length)
        assertTrue(out.startsWith("line 1:"))
        assertTrue(out.contains("line 1000:"))
        assertTrue(out.contains("chars omitted"))
        assertTrue(out.contains("read(path=\"big.txt\", offset="))
    }

    @Test
    fun pointerOffsetCountsHeadLines() {
        // 300 short lines (~5.4K chars) is under the cap — build a file
        // where the head cut lands on a known line.
        val lines = (1..700).map { "x${it} padding padding padding padding" }
        val text = lines.joinToString("\n")
        val out = ResultCompactor.compactRead(text, "f.txt")
        val m = Regex("offset=(\\d+)").find(out)!!
        val offset = m.groupValues[1].toInt()
        assertTrue(offset > 1)
        // headPart is the rendered head plus the separator newline, so its
        // newline count is exactly 1 + (head's own line count) = the offset
        // the pointer must carry (lineOffset=1).
        val headPart = out.substring(0, out.indexOf("… ["))
        assertEquals(offset, headPart.count { it == '\n' },
            "offset should be 1 + number of head lines (lineOffset=1)")
    }

    @Test
    fun pagingAwarePointer() {
        val text = "l\n".repeat(20_000)
        val out = ResultCompactor.compactRead(text, "p.txt", lineOffset = 500)
        val m = Regex("offset=(\\d+)").find(out)!!
        assertTrue(m.groupValues[1].toInt() > 500)
    }

    @Test
    fun singleGiantLineFallsBackToCharCutsWithoutLinePointer() {
        val text = "y".repeat(30_000)
        val out = ResultCompactor.compactRead(text, "g.txt")
        assertTrue(out.length < text.length)
        assertTrue(out.startsWith("y"))
        assertTrue(out.endsWith("y"))
        assertTrue(out.contains("chars omitted"))
        assertFalse(out.contains("offset="), "no lines in the file, so no line-offset pointer")
    }
}
