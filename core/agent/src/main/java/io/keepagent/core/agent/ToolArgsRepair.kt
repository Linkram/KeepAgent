package io.keepagent.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Repairs tool-call arguments as emitted by models — written primarily for
 * small local models (F-026), which regularly emit:
 *
 * - markdown code fences (```json … ```) around the arguments,
 * - prose before/after the JSON object,
 * - trailing commas, unquoted keys, unquoted string values, single quotes
 *   (all accepted by kotlinx' lenient parser),
 * - raw newlines/tabs inside string values (invalid JSON; always escapable).
 *
 * The result of [repair] is either the *canonical* JSON re-serialization of
 * the parsed object (guaranteed strict-JSON, safe for any downstream parser
 * including the quickjs-ng sandbox's `JSON.parse`) or the original string
 * unchanged when nothing parseable was found.
 *
 * Pure JVM, no Android dependencies — unit-testable without a device.
 */
object ToolArgsRepair {

    /** Accepts the non-strict constructs above; still validates structure. */
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Strict output: double quotes, no trailing commas, control chars escaped. */
    private val canonical = Json { encodeDefaults = false }

    /**
     * @return the canonical JSON string for the parsed arguments, or the
     *   original input unchanged; plus a flag that is true when the model's
     *   raw output was **not** spec-strict JSON as-is (i.e. semantic repair
     *   happened). Spec-strict input — any whitespace — passes through
     *   untouched with the flag false.
     *
     * The gate is spec-strict (not "kotlinx can parse it") because the
     * repaired string must also survive the quickjs-ng sandbox's
     * `JSON.parse`, which follows the spec exactly: kotlinx tolerates
     * trailing commas and raw control chars in strings, the spec does not.
     */
    fun repair(argsJson: String): Pair<String, Boolean> {
        if (argsJson.isBlank()) return argsJson to false
        if (isSpecStrict(argsJson)) return argsJson to false
        val cleaned = extractJsonObject(argsJson)
        val candidate = stripTrailingCommas(escapeRawControlCharsInStrings(cleaned))
        // Single quotes first: kotlinx' lenient mode treats them as unquoted
        // *content* (it happily parses {'a': 'b'} as the key "'a'"), so the
        // quote-conversion pass must run before the lenient fallback can
        // shadow it with a wrong-but-parseable result.
        val parsed = if (candidate.contains('\'')) {
            parseSingleQuoted(candidate) ?: lenientParse(candidate)
        } else {
            lenientParse(candidate)
        }
        if (parsed == null) return argsJson to false
        val normalized = canonical.encodeToString(JsonElement.serializer(), parsed)
        return normalized to true
    }

    private fun lenientParse(s: String): JsonObject? =
        runCatching { lenient.parseToJsonElement(s).jsonObject }.getOrNull()

    /**
     * Single-quoted strings/keys: one string-aware pass that converts
     * `'`-bounded strings to `"`-bounded ones (escaping any inner double
     * quotes), then re-parse. Apostrophes inside double-quoted strings pass
     * through untouched, so legitimate text like "it's" is safe.
     */
    private fun parseSingleQuoted(s: String): JsonObject? {
        if (!s.contains('\'')) return null
        val out = StringBuilder(s.length)
        var inDouble = false
        var inSingle = false
        var escaped = false
        for (c in s) {
            when {
                escaped -> { out.append(c); escaped = false }
                inDouble -> {
                    out.append(c)
                    if (c == '\\') escaped = true
                    else if (c == '"') inDouble = false
                }
                inSingle -> when {
                    c == '\\' -> { out.append(c); escaped = true }
                    c == '\'' -> { out.append('"'); inSingle = false }
                    c == '"' -> out.append("\\\"") // escape inner double quotes
                    else -> out.append(c)
                }
                c == '"' -> { out.append(c); inDouble = true }
                c == '\'' -> { out.append('"'); inSingle = true }
                else -> out.append(c)
            }
        }
        return runCatching { lenient.parseToJsonElement(out.toString()).jsonObject }.getOrNull()
    }

    /**
     * Strips markdown fences and surrounding prose: returns the first
     * balanced `{…}` span (string-aware), or the fence-stripped input when
     * no balanced object is present.
     */
    private fun extractJsonObject(raw: String): String {
        val unfenced = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = unfenced.indexOf('{')
        if (start < 0) return unfenced
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until unfenced.length) {
            val c = unfenced[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            if (depth == 0) return unfenced.substring(start, i + 1)
        }
        // Unbalanced (truncated mid-argument): return what we have; the
        // parser decides. Better a clear tool error than a silent guess.
        return unfenced.substring(start)
    }

    /**
     * Raw control characters inside string literals are invalid JSON and —
     * crucially for `write`/`edit` payloads — the most common small-model
     * failure. Escaping every raw `\n`, `\r`, `\t` found *inside a string*
     * is always a correct transformation.
     */
    private fun escapeRawControlCharsInStrings(s: String): String {
        val out = StringBuilder(s.length)
        var inString = false
        var escaped = false
        for (c in s) {
            when {
                !inString -> {
                    if (c == '"') inString = true
                    out.append(c)
                }
                escaped -> {
                    out.append(c)
                    escaped = false
                }
                c == '\\' -> {
                    out.append(c)
                    escaped = true
                }
                c == '"' -> {
                    inString = false
                    out.append(c)
                }
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * Drops commas that directly precede a closing `}`/`]` (string-aware).
     * Trailing commas are a signature small-model defect; kotlinx' lenient
     * mode — contrary to its name — does not accept them, so they get their
     * own pass.
     */
    private fun stripTrailingCommas(s: String): String {
        val out = StringBuilder(s.length)
        var inString = false
        var escaped = false
        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> { inString = true; out.append(c) }
                ',' -> {
                    var j = i + 1
                    while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n' || s[j] == '\r')) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                        // trailing comma — drop it
                    } else {
                        out.append(c)
                    }
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * A small recursive-descent validator for spec-strict JSON (RFC 8259):
     * double-quoted strings only, no trailing commas, no unquoted keys, no
     * raw control chars in strings, one complete value with only
     * whitespace around it. This answers "would `JSON.parse` accept this?"
     * exactly, which the kotlinx parser — stricter or lazier in places —
     * cannot.
     */
    private fun isSpecStrict(s: String): Boolean = SpecChecker(s).check()

    /** Stateful recursive-descent checker; members may reference each other freely. */
    private class SpecChecker(private val s: String) {
        private var i = 0

        fun check(): Boolean {
            val ok = value()
            skipWs()
            return ok && i == s.length
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(): Boolean {
            skipWs()
            if (i >= s.length) return false
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { if (s.startsWith("true", i)) { i += 4; true } else false }
                'f' -> { if (s.startsWith("false", i)) { i += 5; true } else false }
                'n' -> { if (s.startsWith("null", i)) { i += 4; true } else false }
                else -> num()
            }
        }

        private fun obj(): Boolean {
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return true }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') return false
                if (!str()) return false
                skipWs()
                if (i >= s.length || s[i] != ':') return false
                i++
                if (!value()) return false
                skipWs()
                if (i >= s.length) return false
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun arr(): Boolean {
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return true }
            while (true) {
                if (!value()) return false
                skipWs()
                if (i >= s.length) return false
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun str(): Boolean {
            i++ // "
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> {
                        i++
                        if (i >= s.length) return false
                        val e = s[i]
                        if (e != '"' && e != '\\' && e != '/' &&
                            e != 'b' && e != 'f' && e != 'n' && e != 'r' && e != 't'
                        ) return false
                        if (e == 'u') {
                            if (i + 4 >= s.length) return false
                            for (k in 1..4) {
                                if (!isHex(s[i + k])) return false
                            }
                            i += 4
                        }
                        i++
                    }
                    c == '"' -> { i++; return true }
                    c < ' ' -> return false // raw control char — not spec-legal
                    else -> i++
                }
            }
            return false
        }

        private fun num(): Boolean {
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) return false
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isDigit()) return false
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) return false
                while (i < s.length && s[i].isDigit()) i++
            }
            return true
        }

        private fun isHex(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
