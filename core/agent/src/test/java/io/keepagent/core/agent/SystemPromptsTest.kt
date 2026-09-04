package io.keepagent.core.agent

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemPromptsTest {

    @Test
    fun includesWorkspaceAndToolList() {
        val p = SystemPrompts.keepAgent("noodleboats")
        assertTrue(p.contains("Workspace: \"noodleboats\""))
        assertTrue(p.contains("Tools: read, write, edit, glob, grep"))
    }

    @Test
    fun noWorkspaceOmitsWorkspaceLine() {
        val p = SystemPrompts.keepAgent(null)
        assertTrue(!p.contains("Workspace:"))
        assertTrue(p.contains("Tools: read, write, edit, glob, grep"))
    }

    @Test
    fun staysCompactForSmallContexts() {
        // Small local models are the first-class target: the prompt must not
        // eat a meaningful share of a 4-8K window.
        val p = SystemPrompts.keepAgent("some-workspace")
        assertTrue(p.length < 1200, "prompt is ${p.length} chars")
    }

    @Test
    fun hasNumberedRules() {
        val p = SystemPrompts.keepAgent("ws")
        for (n in 1..5) assertTrue(p.contains("$n."), "missing rule $n")
    }
}
