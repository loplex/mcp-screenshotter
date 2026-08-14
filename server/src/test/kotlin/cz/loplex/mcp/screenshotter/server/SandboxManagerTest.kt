package cz.loplex.mcp.screenshotter.server

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SandboxManager]'s pure, stateless logic - the pieces that don't require a full
 * `start()` (X11/D-Bus/worker running). Each test constructs its own [SandboxManager] purely for
 * access to these methods; the constructor only creates (empty, unused) temp files, same as it
 * does in production before `start()` is ever called - see the class's own doc comments on
 * `sandboxHome`/`pgidRegistryFile`.
 */
class SandboxManagerTest {

    @Test
    fun `safeDeleteRecursively deletes a directory that looks like one of our own sandbox homes`() {
        val manager = SandboxManager()
        val dir = Files.createTempDirectory("mcp-screenshotter-home-").toFile()
        File(dir, "nested").mkdirs()
        File(dir, "nested/file.txt").writeText("content")

        manager.safeDeleteRecursively(dir)

        assertFalse(dir.exists())
    }

    @Test
    fun `safeDeleteRecursively refuses to delete a directory that is not one of our own`() {
        val manager = SandboxManager()
        val dir = Files.createTempDirectory("some-unrelated-prefix-").toFile()
        try {
            manager.safeDeleteRecursively(dir)
            assertTrue(dir.exists(), "should not have deleted a directory outside its own naming convention")
        } finally {
            dir.deleteRecursively()
        }
    }
}
