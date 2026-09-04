package io.keepagent.app.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Parser tests for the dependency-free markdown renderer (pure JVM). */
class MarkdownTest {

    @Test
    fun emptyTextYieldsNoBlocks() {
        assertTrue(Markdown.parse("").isEmpty())
        assertTrue(Markdown.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun headingsKeepLevelAndText() {
        val blocks = Markdown.parse("# Title\n\n### Sub sub")
        assertEquals(2, blocks.size)
        assertIs<MdBlock.Heading>(blocks[0]).let {
            assertEquals(1, it.level)
            assertEquals("Title", it.src)
        }
        assertIs<MdBlock.Heading>(blocks[1]).let {
            assertEquals(3, it.level)
            assertEquals("Sub sub", it.src)
        }
    }

    @Test
    fun fencedCodeKeepsLangAndContent() {
        val blocks = Markdown.parse("```kotlin\nval x = 1\nval y = 2\n```\n")
        val code = assertIs<MdBlock.Code>(blocks.single())
        assertEquals("kotlin", code.lang)
        assertEquals("val x = 1\nval y = 2", code.content)
    }

    @Test
    fun unclosedFenceRunsToTheEnd() {
        val blocks = Markdown.parse("```\na\nb")
        val code = assertIs<MdBlock.Code>(blocks.single())
        assertEquals("", code.lang)
        assertEquals("a\nb", code.content)
    }

    @Test
    fun fenceLanguageWithTrailingSpaceIsTrimmed() {
        val blocks = Markdown.parse("```json   \n{}\n```")
        assertEquals("json", (blocks.single() as MdBlock.Code).lang)
    }

    @Test
    fun horizontalRules() {
        for (hr in listOf("---", "***", "___", "-----")) {
            assertTrue(assertIs<MdBlock.Hr>(Markdown.parse(hr).single()).let { true })
        }
    }

    @Test
    fun blockquoteJoinsLines() {
        val blocks = Markdown.parse("> line one\n> line two")
        val quote = assertIs<MdBlock.Quote>(blocks.single())
        assertEquals("line one\nline two", quote.src)
    }

    @Test
    fun bulletListParsesItems() {
        val blocks = Markdown.parse("- one\n- two\n* three")
        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals(listOf("one", "two", "three"), list.items.map { it.text })
        assertTrue(list.items.none { it.ordered })
        assertTrue(list.items.all { it.depth == 0 })
    }

    @Test
    fun orderedListKeepsNumbers() {
        val blocks = Markdown.parse("1. first\n2. second\n3. third")
        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals(listOf(1, 2, 3), list.items.map { it.number })
        assertTrue(list.items.all { it.ordered })
    }

    @Test
    fun nestedListTracksDepthByIndent() {
        val blocks = Markdown.parse("- top\n  - child\n    - grandchild\n- next")
        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals(4, list.items.size)
        assertEquals(0, list.items[0].depth)
        assertEquals(1, list.items[1].depth)
        assertEquals(2, list.items[2].depth)
        assertEquals(0, list.items[3].depth)
        assertEquals("grandchild", list.items[2].text)
    }

    @Test
    fun pipeTableParsesHeaderAndRows() {
        val md = """
            | name | type |
            |------|------|
            | a    | 1    |
            | b    | 2    |
        """.trimIndent()
        val blocks = Markdown.parse(md)
        val table = assertIs<MdBlock.Table>(blocks.single())
        assertEquals(listOf("name", "type"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a", "1"), table.rows[0])
        assertEquals(listOf("b", "2"), table.rows[1])
    }

    @Test
    fun tableRaggedRowsArePaddedToHeaderWidth() {
        val md = "| a | b |\n|---|---|\n| only-one |\n"
        val table = assertIs<MdBlock.Table>(Markdown.parse(md).single())
        assertEquals(listOf("only-one", ""), table.rows.single())
    }

    @Test
    fun extraCellsBeyondHeaderAreDropped() {
        val md = "| a |\n|---|\n| x | y | z |\n"
        val table = assertIs<MdBlock.Table>(Markdown.parse(md).single())
        assertEquals(listOf("x"), table.rows.single())
    }

    @Test
    fun pipeLineWithoutSeparatorIsNotATable() {
        val blocks = Markdown.parse("| a | b |\nnot a separator\n| c | d |")
        assertTrue(blocks.none { it is MdBlock.Table })
    }

    @Test
    fun mixedDocumentPreservesOrder() {
        val md = "para one\n\n## Head\n\n- item\n\n| h |\n|---|\n| v |\n\ntail"
        val blocks = Markdown.parse(md)
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is MdBlock.Para)
        assertTrue(blocks[1] is MdBlock.Heading)
        assertTrue(blocks[2] is MdBlock.ListBlock)
        assertTrue(blocks[3] is MdBlock.Table)
        assertTrue(blocks[4] is MdBlock.Para)
    }

    @Test
    fun paragraphStopsAtBlockStarts() {
        val blocks = Markdown.parse("line one\nline two\n## Head")
        assertEquals(2, blocks.size)
        assertIs<MdBlock.Para>(blocks[0]).let { assertEquals("line one\nline two", it.src) }
        assertTrue(blocks[1] is MdBlock.Heading)
    }

    @Test
    fun hrLikeListMarkerIsNotAMisparse() {
        // "----" must stay an hr, not a list item.
        assertTrue(assertIs<MdBlock.Hr>(Markdown.parse("----").single()).let { true })
    }
}
