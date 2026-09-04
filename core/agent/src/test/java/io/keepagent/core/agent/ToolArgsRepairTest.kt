package io.keepagent.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [ToolArgsRepair] — the small-model forgiveness layer
 * (F-026). The repaired output must always be strict JSON (parseable by
 * any parser, including the quickjs-ng sandbox's `JSON.parse`) or the
 * original string, unchanged, with the flag false.
 */
class ToolArgsRepairTest {

    private val strict = Json { ignoreUnknownKeys = true }

    private fun parses(obj: Map<String, String>) {
        val (s, repaired) = ToolArgsRepair.repair(obj["input"]!!)
        if (obj["expect"] == "unrepaired") {
            assertEquals(obj["input"], s, "expected input returned unchanged")
            assertFalse(repaired, "flag must be false when nothing was repaired")
            return
        }
        assertTrue(repaired, "flag must be true when repair happened")
        val parsed = strict.parseToJsonElement(s).jsonObject // throws if not strict JSON
        obj["check"]?.let { key ->
            assertEquals(obj["checkValue"], parsed[key]?.jsonPrimitive?.content, "repaired $key mismatch")
        }
    }

    @Test
    fun strictJsonPassesThroughUnchanged() {
        parses(
            mapOf(
                "input" to """{"path": "a.txt"}""",
                "expect" to "unrepaired",
            )
        )
    }

    @Test
    fun strictJsonWithWhitespaceStillPassesThrough() {
        parses(
            mapOf(
                "input" to """  {"a": 1}
""",
                "expect" to "unrepaired",
            )
        )
    }

    @Test
    fun trailingCommaIsRepaired() {
        parses(
            mapOf(
                "input" to """{"path": "a.txt",}""",
                "check" to "path",
                "checkValue" to "a.txt",
            )
        )
    }

    @Test
    fun markdownFenceIsStripped() {
        parses(
            mapOf(
                "input" to "```json\n" + """{"path": "a.txt"}""" + "\n```",
                "check" to "path",
                "checkValue" to "a.txt",
            )
        )
    }

    @Test
    fun proseAroundJsonIsExtracted() {
        parses(
            mapOf(
                "input" to """Sure! Here is the tool call: {"path": "a.txt"} — let me know if it works.""",
                "check" to "path",
                "checkValue" to "a.txt",
            )
        )
    }

    @Test
    fun unquotedKeysAreAccepted() {
        parses(
            mapOf(
                "input" to """{path: "a.txt", content: "hello"}""",
                "check" to "path",
                "checkValue" to "a.txt",
            )
        )
    }

    @Test
    fun singleQuotedStringsAreConverted() {
        parses(
            mapOf(
                "input" to """{'path': 'a.txt'}""",
                "check" to "path",
                "checkValue" to "a.txt",
            )
        )
    }

    @Test
    fun singleQuotesWithInnerDoubleQuotes() {
        parses(
            mapOf(
                "input" to """{'text': 'say "hi"'}""",
                "check" to "text",
                "checkValue" to "say \"hi\"",
            )
        )
    }

    @Test
    fun rawNewlineInsideStringIsEscaped() {
        parses(
            mapOf(
                "input" to "{\"path\": \"index.html\", \"content\": \"line one\nline two\"}",
                "check" to "content",
                "checkValue" to "line one\nline two",
            )
        )
    }

    @Test
    fun rawTabInsideStringIsEscaped() {
        parses(
            mapOf(
                "input" to "{\"code\": \"if (x) {\n\treturn 1\n}\"}",
                "check" to "code",
                "checkValue" to "if (x) {\n\treturn 1\n}",
            )
        )
    }

    @Test
    fun bracesInsideStringsDoNotBreakExtraction() {
        parses(
            mapOf(
                "input" to """prefix {"a": "if (y) { return }", "b": "}"} suffix""",
                "check" to "b",
                "checkValue" to "}",
            )
        )
    }

    @Test
    fun trailingCommaPlusFencePlusProse() {
        parses(
            mapOf(
                "input" to "Result:\n```json\n" + """{"name": "World",}""" + "\n```\nDone.",
                "check" to "name",
                "checkValue" to "World",
            )
        )
    }

    @Test
    fun truncatedJsonIsNotGuessedAt() {
        parses(
            mapOf(
                "input" to """{"path": "a.txt""",
                "expect" to "unrepaired",
            )
        )
    }

    @Test
    fun notJsonAtAll() {
        parses(
            mapOf(
                "input" to "I will now read the file.",
                "expect" to "unrepaired",
            )
        )
    }

    @Test
    fun blankString() {
        val (s, repaired) = ToolArgsRepair.repair("")
        assertEquals("", s)
        assertFalse(repaired)
    }

    @Test
    fun apostropheInsideDoubleQuotedStringIsLegit() {
        parses(
            mapOf(
                "input" to """{"note": "it's fine, right?"}""",
                "expect" to "unrepaired",
            )
        )
    }

    @Test
    fun nestedStrictJsonPassesThrough() {
        parses(
            mapOf(
                "input" to """{"a": [1, 2.5e3, -4], "b": {"c": null, "d": [true, "x"]}}""",
                "expect" to "unrepaired",
            )
        )
    }

    @Test
    fun arrayTrailingCommaIsRepaired() {
        parses(
            mapOf(
                "input" to """{"list": [1, 2,], "path": "a.txt"}""",
                "check" to "path",
                "checkValue" to "a.txt",
            )
        )
    }
}
