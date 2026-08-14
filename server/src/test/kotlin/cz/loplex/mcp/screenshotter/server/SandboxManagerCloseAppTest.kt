package cz.loplex.mcp.screenshotter.server

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dedicated coverage for closeApp()'s PID-recycling guard (see its doc comment in Main.kt) -
 * split out from [SandboxManagerTest] because it needs real child processes, not just pure
 * argument-building logic, to exercise the pgid-leader check and the actual kill(2) happy path.
 *
 * Deferred earlier (see NOTES/AI/2026-08-14-test-coverage-todo.md #4) while `killProcessGroup()`
 * was still an actively investigated/changed area (the procps `kill(1)` bug, see
 * NOTES/AI/2026-08-14-kill-procps-investigation.md). Now that it's settled on a single atomic
 * `kill(2)` via JNA, this only exercises it from the outside (closeApp()'s public contract), not
 * its internals - independent of how that investigation itself concludes.
 */
class SandboxManagerCloseAppTest {

    @Test
    fun `closeApp refuses a pid whose process is not its own group leader, and evicts it anyway`() {
        val manager = SandboxManager()
        // Plain ProcessBuilder (no setsid) inherits this JVM's own process group, so its pgid
        // never equals its own pid - the same shape a real PID recycled onto an unrelated process
        // would have. Simulates a stale launchedApps entry surviving past its app's exit.
        val proc = ProcessBuilder("sleep", "5").start()
        val pid = proc.pid().toInt()
        try {
            manager.launchedApps[pid] = "sleep 5"

            val result = manager.closeApp(pid)

            assertFalse(result, "closeApp should refuse a pid that is not its own process group leader")
            assertFalse(
                manager.launchedApps.containsKey(pid),
                "closeApp should evict the stale entry even when it refuses to kill"
            )
            assertTrue(proc.isAlive, "closeApp must not have touched a process it refused to recognize as its own")
        } finally {
            proc.destroyForcibly()
            proc.waitFor()
        }
    }

    @Test
    fun `closeApp kills a pid that is its own process group leader`() {
        val manager = SandboxManager()
        // setsid execs directly into the given command in a new session, so the resulting process
        // is its own group leader for as long as it's alive - the same invariant startTracked()
        // relies on for every app launch_app itself starts (see closeApp()'s doc comment). This is
        // also the first direct unit test on killProcessGroup()'s happy path, not just its guard.
        val proc = ProcessBuilder("setsid", "sleep", "30").start()
        val pid = proc.pid().toInt()
        try {
            manager.launchedApps[pid] = "sleep 30"
            assertEquals(
                pid.toLong(), manager.readProcessGroupId(pid.toLong()),
                "sanity check: setsid should make pid its own group leader"
            )

            val result = manager.closeApp(pid)

            assertTrue(result, "closeApp should succeed for a pid that is its own process group leader")
            assertFalse(manager.launchedApps.containsKey(pid))
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
            assertTrue(exited, "closeApp's kill(2) should have actually terminated the process")
        } finally {
            proc.destroyForcibly()
            proc.waitFor()
        }
    }
}
