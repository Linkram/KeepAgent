package io.keepagent.app.test

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectChecksTest {
    @Test fun testHistorySerializationRoundTrips() {
        val runs = listOf(ProjectChecks.Run(42, "/workspace", ".", "Python tests", false, true, "2 passed"))
        val encoded = kotlinx.serialization.json.Json.encodeToString(runs)
        assertEquals(runs, kotlinx.serialization.json.Json.decodeFromString<List<ProjectChecks.Run>>(encoded))
    }

    @Test fun discoversMixedProjectsWithoutExecutingThem() {
        val root = Files.createTempDirectory("checks").toFile()
        try {
            root.resolve("test_example.py").writeText("raise RuntimeError('must not execute')")
            root.resolve("index.html").writeText("hello")
            root.resolve("package.json").writeText("{}")
            assertEquals(setOf("pytest", "web", "unavailable"), ProjectChecks.discover(root).map { it.kind }.toSet())
        } finally { root.deleteRecursively() }
    }
    @Test fun ignoresDependencyAndBuildFixtures() {
        val root = Files.createTempDirectory("checks").toFile()
        try {
            root.resolve("node_modules/example").mkdirs()
            root.resolve("node_modules/example/test_fixture.py").writeText("")
            root.resolve("build").mkdirs()
            root.resolve("build/index.html").writeText("")
            assertTrue(ProjectChecks.discover(root).isEmpty())
        } finally { root.deleteRecursively() }
    }
}
