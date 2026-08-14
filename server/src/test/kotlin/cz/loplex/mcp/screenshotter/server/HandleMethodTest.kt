package cz.loplex.mcp.screenshotter.server

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests for `tools/call` argument validation in [handleMethod] that don't require a running
 * sandbox - `sandbox.launchApp()` is only ever reached once `mounts` has already validated, so
 * these never touch the (never-started) global `sandbox` instance.
 */
class HandleMethodTest {

    @Test
    fun `launch_app with a missing mounts host_path returns a readable error instead of a bare cast failure`() {
        val params = mapOf(
            "name" to "launch_app",
            "arguments" to mapOf(
                "command" to "true",
                "mounts" to listOf(mapOf("sandbox_path" to "/mnt/x"))
            )
        )

        val result = handleMethod("tools/call", params) as ToolResult

        assertTrue(result.isError)
        val text = result.content.first()["text"] as String
        assertTrue(text.contains("host_path is required"), "unexpected error message: $text")
    }

    @Test
    fun `launch_app with a non-string mounts host_path returns a readable error`() {
        val params = mapOf(
            "name" to "launch_app",
            "arguments" to mapOf(
                "command" to "true",
                "mounts" to listOf(mapOf("host_path" to 123))
            )
        )

        val result = handleMethod("tools/call", params) as ToolResult

        assertTrue(result.isError)
        val text = result.content.first()["text"] as String
        assertTrue(text.contains("host_path is required"), "unexpected error message: $text")
    }
}
