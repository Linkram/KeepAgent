package io.keepagent.core.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ProjectImporterTest {
    private fun archive(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun inRoot(test: (java.io.File) -> Unit) {
        val temp = Files.createTempDirectory("import-test-").toFile()
        try { test(java.io.File(temp, "workspaces")) } finally { temp.deleteRecursively() }
    }

    @Test fun importsWrappedRepositoryAndGitMetadata() = inRoot { root ->
        val result = ProjectImporter(root).importZip(archive(
            "project/AGENTS.md" to "Run tests", "project/.git/HEAD" to "ref: refs/heads/main",
            "project/src/main.kt" to "hello",
        ), "My project")
        assertEquals("My-project", result.name)
        assertEquals(3, result.files)
        assertEquals("Run tests", java.io.File(root, "My-project/AGENTS.md").readText())
        assertTrue(java.io.File(root, "My-project/.git/HEAD").isFile)
    }

    @Test fun rejectsTraversalAndAbsolutePathsWithoutPublishing() = inRoot { root ->
        for (path in listOf("../escape", "/escape", "C:/escape", "dir/../../escape", "..\\escape")) {
            assertFailsWith<IllegalArgumentException> {
                ProjectImporter(root).importZip(archive("ok.txt" to "ok", path to "bad"), "project")
            }
            assertFalse(java.io.File(root, "project").exists())
        }
        assertTrue(java.io.File(root.parentFile, ".project-imports").listFiles().orEmpty().isEmpty())
    }

    @Test fun enforcesExpandedSizeAndEntryBudgets() = inRoot { root ->
        assertFailsWith<IllegalArgumentException> {
            ProjectImporter(root, maxBytes = 3).importZip(archive("large.txt" to "1234"), "large")
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectImporter(root, maxEntries = 1).importZip(archive("a" to "a", "b" to "b"), "many")
        }
        assertFalse(java.io.File(root, "large").exists())
        assertFalse(java.io.File(root, "many").exists())
    }

    @Test fun neverOverwritesExistingProjectAndRejectsEmptyInput() = inRoot { root ->
        ProjectImporter(root).importZip(archive("keep.txt" to "original"), "existing")
        assertFailsWith<IllegalArgumentException> {
            ProjectImporter(root).importZip(archive("keep.txt" to "replacement"), "existing")
        }
        assertEquals("original", java.io.File(root, "existing/keep.txt").readText())
        assertFailsWith<IllegalArgumentException> {
            ProjectImporter(root).importZip(ByteArrayInputStream("not a zip".toByteArray()), "empty")
        }
    }
}
