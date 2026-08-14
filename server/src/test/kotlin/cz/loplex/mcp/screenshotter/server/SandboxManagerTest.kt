package cz.loplex.mcp.screenshotter.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `bwrapCommand with no mounts wraps the command in the expected fixed bwrap arguments`() {
        val manager = SandboxManager()
        val args = manager.bwrapCommand("echo hi", emptyList())

        assertEquals("bwrap", args[0])
        assertEquals(listOf("--", "sh", "-c", "echo hi"), args.takeLast(4))
        // The real HOME is shadowed by a tmpfs, and both the real filesystem root and /proc stay
        // available read-only/as-is so the launched app can actually run - see bwrapCommand()'s
        // doc comment.
        assertTrue(args.containsSequence(listOf("--ro-bind", "/", "/")))
        assertTrue(args.containsSequence(listOf("--dev", "/dev")))
        assertTrue(args.containsSequence(listOf("--proc", "/proc")))
        assertTrue(args.containsSequence(listOf("--bind", "/tmp", "/tmp")))
    }

    @Test
    fun `bwrapCommand appends mounts as ro-bind or bind flags in order, before the sh -c trailer`() {
        val manager = SandboxManager()
        val mounts = listOf(
            Mount(hostPath = "/data/readonly", sandboxPath = "/mnt/ro", readOnly = true),
            Mount(hostPath = "/data/writable", sandboxPath = "/mnt/rw", readOnly = false)
        )

        val noMounts = manager.bwrapCommand("run-me", emptyList())
        val withMounts = manager.bwrapCommand("run-me", mounts)

        // Same fixed prefix and trailer either way; only the mount arguments are inserted between
        // them. Deriving the prefix from noMounts (rather than hardcoding its length) keeps this
        // test from breaking every time an unrelated fixed --bind gets added or removed.
        val trailer = listOf("--", "sh", "-c", "run-me")
        assertEquals(trailer, noMounts.takeLast(4))
        assertEquals(trailer, withMounts.takeLast(4))

        val fixedPrefix = noMounts.dropLast(4)
        assertEquals(fixedPrefix, withMounts.subList(0, fixedPrefix.size))

        val mountArgs = withMounts.subList(fixedPrefix.size, withMounts.size - 4)
        assertEquals(
            listOf(
                "--ro-bind", "/data/readonly", "/mnt/ro",
                "--bind", "/data/writable", "/mnt/rw"
            ),
            mountArgs
        )
    }

    @Test
    fun `displayResolutionFromEnv defaults to 1024x768 when unset`() {
        val manager = SandboxManager()
        assertEquals("1024x768", manager.displayResolutionFromEnv(emptyMap()))
    }

    @Test
    fun `displayResolutionFromEnv passes through a valid override`() {
        val manager = SandboxManager()
        assertEquals(
            "1280x800",
            manager.displayResolutionFromEnv(mapOf("SCREENSHOTTER_DISPLAY_RESOLUTION" to "1280x800"))
        )
    }

    @Test
    fun `displayResolutionFromEnv rejects a malformed override`() {
        val manager = SandboxManager()
        val ex = assertThrows<IllegalArgumentException> {
            manager.displayResolutionFromEnv(mapOf("SCREENSHOTTER_DISPLAY_RESOLUTION" to "not-a-resolution"))
        }
        assertTrue(ex.message?.contains("WIDTHxHEIGHT") == true)
    }

    @Test
    fun `displayCommand builds Xephyr and Xvfb argument lists using the given resolution`() {
        val manager = SandboxManager()
        assertEquals(
            listOf("Xephyr", "-screen", "1280x800", "-displayfd", "1"),
            manager.displayCommand(DisplayBackend.XEPHYR, "1280x800")
        )
        assertEquals(
            listOf("Xvfb", "-screen", "0", "1280x800x24", "-displayfd", "1"),
            manager.displayCommand(DisplayBackend.XVFB, "1280x800")
        )
    }

    @Test
    fun `readProcessGroupId returns null for a pid that does not exist`() {
        val manager = SandboxManager()
        // Comfortably above any real PID on a normal Linux box (pid_max defaults far below this).
        assertNull(manager.readProcessGroupId(999_999_999L))
    }

    @Test
    fun `readProcessGroupId resolves a group for the current, still-running JVM process`() {
        val manager = SandboxManager()
        val pgid = manager.readProcessGroupId(ProcessHandle.current().pid())
        assertNotNull(pgid)
    }

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

    /** True if this list contains `sub` as a contiguous run, anywhere. */
    private fun <T> List<T>.containsSequence(sub: List<T>): Boolean {
        if (sub.isEmpty()) return true
        return (0..size - sub.size).any { i -> sub.indices.all { j -> this[i + j] == sub[j] } }
    }
}
