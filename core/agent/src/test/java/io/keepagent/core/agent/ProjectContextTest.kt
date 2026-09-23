package io.keepagent.core.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ProjectContextTest {
    @Test fun boundsInputAndReloadsGuidance() {
        val root = Files.createTempDirectory("context-test-").toFile()
        try {
            File(root, "AGENTS.md").writeText("Run project tests")
            File(root, "CLAUDE.md").writeText("x".repeat(100_000))
            File(root, "PLAN.md").writeText("y".repeat(100_000))
            File(root, "HANDOFF.md").writeText("z".repeat(100_000))
            File(root, "README.md").writeText("w".repeat(100_000))
            File(root, ".env").writeText("SECRET_MUST_NOT_APPEAR")
            val context = ProjectContext.load(root)
            assertTrue(context.length <= ProjectContext.MAX_CHARS)
            assertTrue(context.contains("Run project tests"))
            assertTrue(context.contains("Excerpt truncated"))
            assertFalse(context.contains("SECRET_MUST_NOT_APPEAR"))
            File(root, "AGENTS.md").writeText("Updated guidance")
            assertTrue(ProjectContext.load(root).contains("Updated guidance"))
        } finally { root.deleteRecursively() }
    }

    @Test fun compactsVersionedHandoffWithoutLoadingHashes() {
        val root = Files.createTempDirectory("handoff-context-").toFile()
        try {
            val dir = File(root, ".keepagent").apply { mkdirs() }
            File(dir, "handoff.json").writeText(
                """{"schemaVersion":1,"goal":"finish parser","decisions":"keep API stable","completedWork":"lexer done","git":{"branch":"main","commit":"abc","dirtyFiles":[{"path":"src/parser.kt","sha256":"SECRET_HASH"}]},"tests":[]}""",
            )
            val context = ProjectContext.load(root)
            assertTrue(context.contains("Goal: finish parser"))
            assertTrue(context.contains("Dirty paths: src/parser.kt"))
            assertTrue(context.contains("empty tests list is not evidence"))
            assertFalse(context.contains("SECRET_HASH"))
        } finally { root.deleteRecursively() }
    }
}
