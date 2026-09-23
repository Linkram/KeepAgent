package io.keepagent.app.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

class GitServiceTest {
    @Test fun acceptsPublicHttpUrls() {
        assertEquals(
            "https://github.com/example/project.git",
            GitService.validatePublicUrl(" https://github.com/example/project.git "),
        )
    }

    @Test fun rejectsLocalAndCredentialSchemes() {
        assertFailsWith<IllegalArgumentException> {
            GitService.validatePublicUrl("file:///private/project")
        }
        assertFailsWith<IllegalArgumentException> {
            GitService.validatePublicUrl("https://token@example.com/project.git")
        }
        assertFailsWith<IllegalArgumentException> {
            GitService.validatePublicUrl("git@example.com:project.git")
        }
    }

    @Test fun jgitClonesIntoTheEmptyStagingDirectoryUsedByWorkspaceImports() {
        val temp = Files.createTempDirectory("clone-stage-").toFile()
        try {
            val source = File(temp, "source").apply { mkdirs() }
            Git.init().setDirectory(source).call().use { git ->
                File(source, "README.md").writeText("hello")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("initial")
                    .setAuthor("Test", "test@example.invalid")
                    .setCommitter("Test", "test@example.invalid").call()
            }
            val staging = File(temp, "staging").apply { mkdirs() }
            Git.cloneRepository().setURI(source.toURI().toString()).setDirectory(staging).call().use { }
            assertTrue(File(staging, "README.md").isFile)
            assertTrue(File(staging, ".git").isDirectory)
        } finally {
            temp.deleteRecursively()
        }
    }
}
