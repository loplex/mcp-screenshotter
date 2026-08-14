package cz.loplex.mcp.screenshotter.server

import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Scanner

data class JsonRpcRequest(val jsonrpc: String, val id: Any?, val method: String, val params: Map<String, Any>?)
data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: Any?, val result: Any? = null, val error: Any? = null)
data class ToolInfo(val name: String, val description: String, val inputSchema: Map<String, Any>)
data class InitResult(val protocolVersion: String, val capabilities: Map<String, Any>, val serverInfo: Map<String, Any>)
data class ToolResult(val content: List<Map<String, Any>>, val isError: Boolean = false)

/** A single directory the client explicitly wants visible inside the sandbox; everything else under HOME stays hidden. */
data class Mount(val hostPath: String, val sandboxPath: String = hostPath, val readOnly: Boolean = true)

/**
 * Which nested X server backs the sandbox display.
 *  - [XEPHYR] renders into a window on a *host* X server, so it needs one already running (e.g.
 *    a real desktop session) to attach to - but that window is handy for a human to peek at while
 *    debugging a test locally.
 *  - [XVFB] is fully virtual and needs no host display at all, which is what makes it work on a
 *    headless CI box or a bare SSH session where Xephyr has nothing to attach to.
 */
enum class DisplayBackend(val processName: String) {
    XEPHYR("Xephyr"),
    XVFB("Xvfb");

    companion object {
        /** Selected via SCREENSHOTTER_DISPLAY_BACKEND (case-insensitive); defaults to Xephyr, matching prior behavior. */
        fun fromEnv(env: Map<String, String> = System.getenv()): DisplayBackend {
            val raw = env["SCREENSHOTTER_DISPLAY_BACKEND"]?.trim()
            if (raw.isNullOrEmpty()) return XEPHYR
            return entries.find { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown SCREENSHOTTER_DISPLAY_BACKEND '$raw'; expected one of ${entries.joinToString(", ") { it.name }}."
                )
        }
    }
}

class SandboxManager {
    private var displayProc: Process? = null
    private var vncProc: Process? = null
    private var dbusProc: Process? = null
    private var atSpiProc: Process? = null
    private var workerProc: Process? = null
    private var watchdogProc: Process? = null

    // Both created eagerly (not lazily inside start()) so their paths are known before
    // startWatchdog() builds its cleanup script, and both are reaped by that script too - not
    // just by stop() - so a crash doesn't leave them behind either.
    private val sandboxHome: File = Files.createTempDirectory("mcp-screenshotter-home-").toFile()

    // Every tracked process is started via `setsid` (see startTracked()), so its pgid equals its
    // own pid; both this process (in stop()) and the crash-only watchdog (see startWatchdog())
    // reap groups by reading this same file, so there's exactly one place that knows about them.
    private val pgidRegistryFile: File = Files.createTempFile("mcp-screenshotter-pgids-", ".txt").toFile()

    private var workerPort: Int = -1
    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    private var sandboxEnv: Map<String, String>? = null

    // Maps a launch_app-returned PID (which, thanks to startTracked()'s `setsid`, is also that
    // app's process *group* ID) to the command it was launched with. Lets list_windows() report
    // which launched app a given window belongs to, and lets closeApp() refuse to touch a PID it
    // didn't itself hand out - see FUTURE_WORK #8.
    private val launchedApps = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun start() {
        System.err.println("Starting sandbox environment...")

        // Start the watchdog first, before anything it might need to clean up even exists -
        // see startWatchdog() for how it detects our death and why.
        startWatchdog()

        // 1. Start the display server (Xephyr or Xvfb, per SCREENSHOTTER_DISPLAY_BACKEND).
        // Display number picked by the server itself via `-displayfd 1`: it finds a free
        // display, binds to it, and writes the number to fd 1 (stdout, which we keep as a
        // dedicated pipe for exactly this) only once it's actually ready to accept connections.
        // That's both race-free (no separate "scan /tmp/.X11-unix, then hope nothing else grabs
        // the same number before we get there" step - the exact TOCTOU a `findFreeDisplay()`-style
        // scan is prone to) and self-synchronizing (no fixed `Thread.sleep()` guess for "is it up
        // yet" - the write itself *is* the readiness signal).
        val backend = DisplayBackend.fromEnv()
        val resolution = displayResolutionFromEnv()
        displayProc = startTracked(displayCommand(backend, resolution)) { pb ->
            pb.redirectError(ProcessBuilder.Redirect.INHERIT) // keep stdout free for -displayfd; let stderr flow through as usual
        }
        val displayOut = BufferedReader(InputStreamReader(displayProc!!.inputStream, "UTF-8"))
        val displayNumLine = displayOut.readLine()?.trim()
            ?: throw RuntimeException("Failed to read display number from ${backend.processName} via -displayfd.")
        val displayNum = displayNumLine.toIntOrNull()
            ?: throw RuntimeException("${backend.processName} -displayfd wrote a non-numeric value: '$displayNumLine'")
        val display = ":$displayNum"

        // -displayfd's contract is a single line; nothing else is expected on this pipe, but keep
        // draining it anyway (same reasoning as the dbus-daemon pipe below) so an unexpected
        // extra write can never fill the pipe buffer and block the display server.
        Thread {
            try { while (displayOut.readLine() != null) { /* discard */ } } catch (ignored: Exception) {}
        }.start()

        System.err.println("${backend.processName} started on $display")

        // Optional: mirror the sandbox display over VNC, e.g. so a human can watch/interact with
        // an otherwise-headless Xvfb display. Whether the sandbox ends up visible to anyone at
        // all is entirely up to whoever runs the server - Xephyr already opens a plain window if
        // a host display is present, and this is the equivalent knob for the case where it isn't.
        // Off by default: it opens a network-facing (albeit loopback-only) port, so it should be
        // an explicit opt-in, not silently on.
        vncPortFromEnv()?.let { port ->
            vncProc = startTracked(listOf(
                "x11vnc", "-display", display, "-rfbport", port.toString(),
                "-localhost", "-forever", "-shared", "-noxdamage", "-nopw"
            ))
            System.err.println(
                "VNC mirror listening on 127.0.0.1:$port ($display) - loopback-only; " +
                    "use an SSH tunnel or your own port-forward for remote viewing."
            )
        }

        // 2. Start D-Bus
        // Deliberately run in the foreground (--nofork): this keeps a live Process handle
        // instead of letting the daemon fork-and-detach into a PID we never captured (the leak
        // described in FUTURE_WORK #9). startTracked()'s `setsid` group lets stop() (or the
        // watchdog) reap it - and any D-Bus-activated helper it spawns - as a single unit.
        dbusProc = startTracked(listOf("dbus-daemon", "--session", "--print-address=1", "--nofork")) { pb ->
            pb.redirectErrorStream(true)
        }

        val dbusOut = BufferedReader(InputStreamReader(dbusProc!!.inputStream, "UTF-8"))
        val dbusAddress = dbusOut.readLine()?.trim()
            ?: throw RuntimeException("Failed to read D-Bus address from dbus-daemon.")

        // Keep draining the rest of its output so the pipe never fills up and blocks the daemon
        Thread {
            try {
                while (dbusOut.readLine() != null) { /* discard */ }
            } catch (ignored: Exception) {}
        }.start()

        System.err.println("D-Bus started at $dbusAddress")

        // A dedicated, throwaway HOME for the sandbox: reusing the real user's HOME would leak
        // their actual GTK theme/font/icon overrides (~/.config/gtk-3.0/settings.ini) and dconf
        // settings into launched apps, so the same test could render differently depending on
        // whose machine (and whose desktop config) it runs on. Pin a fixed theme/font on top of
        // that isolation so rendering is deterministic across machines, not just clean.
        writeGtkSettings(sandboxHome)

        // Base minimal environment for the sandbox
        sandboxEnv = mapOf(
            "DISPLAY" to display,
            "DBUS_SESSION_BUS_ADDRESS" to dbusAddress,
            "HOME" to sandboxHome.absolutePath,
            "XDG_CONFIG_HOME" to File(sandboxHome, ".config").absolutePath,
            "GTK_THEME" to "Adwaita",
            "GSETTINGS_BACKEND" to "memory", // ignore any ambient dconf database entirely
            "LC_ALL" to "C.UTF-8",
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin")
        )

        // 3. Start AT-SPI2
        atSpiProc = startTracked(listOf("/usr/libexec/at-spi-bus-launcher", "--launch-immediately")) { pb ->
            pb.environment().clear()
            pb.environment().putAll(sandboxEnv!!)
        }
        Thread.sleep(1000)

        // 4. Start Worker
        // Capped heap (default 512m, override via SCREENSHOTTER_WORKER_MAX_HEAP) so a runaway
        // allocation on the JVM heap fails fast with an OutOfMemoryError instead of pressuring the
        // host's memory - and by extension, whatever else happens to share its cgroup - into a
        // kernel/systemd-level OOM response.
        //
        // -Xmx alone doesn't cover this: it bounds the JVM *heap*, not native allocations made by
        // libraries the worker loads via JNA/JNI (X11, AT-SPI, OpenCV) - which is exactly what bit
        // us here (OpenCV's native thread pool attempting a ~896GB stack allocation). `ulimit -v`
        // bounds the whole process's virtual address space at the kernel level, so *any* single
        // allocation above the cap - JVM heap or native - fails immediately with ENOMEM, no matter
        // what caused it. Default 8GB (override via SCREENSHOTTER_WORKER_MAX_VIRTUAL_MEM_MB) leaves
        // comfortable headroom for a real JVM + loaded native libs while still rejecting anything
        // resembling that ~896GB attempt long before it can add real memory pressure.
        val workerMaxHeap = System.getenv("SCREENSHOTTER_WORKER_MAX_HEAP")?.trim().takeUnless { it.isNullOrEmpty() } ?: "512m"
        val workerMaxVirtualMemKb = (System.getenv("SCREENSHOTTER_WORKER_MAX_VIRTUAL_MEM_MB")?.trim()?.toLongOrNull() ?: 8192L) * 1024
        val workerJar = resolveWorkerJar()
        val workerCommand = "ulimit -v $workerMaxVirtualMemKb && exec java -Xmx$workerMaxHeap -Djava.awt.headless=false -jar '$workerJar' 0"
        workerProc = startTracked(listOf("bash", "-c", workerCommand)) { pb ->
            // Use strictly isolated environment
            pb.environment().clear()
            pb.environment().putAll(sandboxEnv!!)
            // We will read worker's stderr to find out what port it started on
            pb.redirectErrorStream(true)
        }

        val workerOut = BufferedReader(InputStreamReader(workerProc!!.inputStream, "UTF-8"))
        var portFound = false
        // Read lines until we find the port
        while (true) {
            val line = workerOut.readLine() ?: break
            System.err.println("[Worker] $line")
            if (line.contains("Worker started on HTTP port ")) {
                workerPort = line.substringAfter("Worker started on HTTP port ").substringBefore(" ").toInt()
                portFound = true
                break
            }
        }

        if (!portFound) {
            throw RuntimeException("Failed to read HTTP port from worker process.")
        }

        // Start a thread to keep dumping worker logs to stderr
        Thread {
            try {
                while (true) {
                    val line = workerOut.readLine() ?: break
                    System.err.println("[Worker] $line")
                }
            } catch (ignored: Exception) {}
        }.start()

        // Ensure cleanup on graceful shutdown (Ctrl-C, normal exit, ...)
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /**
     * Selected via SCREENSHOTTER_DISPLAY_RESOLUTION (e.g. "1280x800"); defaults to "1024x768",
     * matching the resolution this used to have hardcoded in [displayCommand] (see FUTURE_WORK
     * #10 - this was the last of the three parameters listed there still fixed).
     */
    internal fun displayResolutionFromEnv(env: Map<String, String> = System.getenv()): String {
        val raw = env["SCREENSHOTTER_DISPLAY_RESOLUTION"]?.trim()
        if (raw.isNullOrEmpty()) return "1024x768"
        if (!raw.matches(Regex("""\d+x\d+"""))) {
            throw IllegalArgumentException(
                "SCREENSHOTTER_DISPLAY_RESOLUTION must be of the form WIDTHxHEIGHT (e.g. '1024x768'), got '$raw'."
            )
        }
        return raw
    }

    /** Selected via SCREENSHOTTER_VNC_PORT; null (the default) means no VNC mirror is started at all. */
    private fun vncPortFromEnv(env: Map<String, String> = System.getenv()): Int? {
        val raw = env["SCREENSHOTTER_VNC_PORT"]?.trim()
        if (raw.isNullOrEmpty()) return null
        return raw.toIntOrNull()
            ?: throw IllegalArgumentException("SCREENSHOTTER_VNC_PORT must be a port number, got '$raw'.")
    }

    /**
     * Locates the worker jar to launch:
     *  1. `SCREENSHOTTER_WORKER_JAR` env var, if set - an explicit override for deployments where
     *     the module layout below doesn't apply.
     *  2. Otherwise, `worker/target/screenshotter-worker.jar`, resolved next to wherever *this
     *     server's own* jar/class files actually live on disk - not the process's current working
     *     directory, which an MCP host (e.g. Claude Desktop) is free to launch us from anywhere.
     *     The filename itself is fixed (no version, no assembly-plugin "-jar-with-dependencies"
     *     classifier) via `finalName`/`appendAssemblyId` on the assembly plugin in the parent
     *     `pom.xml`, so this never has to know this project's version or Maven's naming
     *     convention - and can't silently desync from it the way a hardcoded version string once
     *     did (this used to say "0.1.0-SNAPSHOT" long after the project moved on to
     *     "0.2.0-SNAPSHOT").
     */
    private fun resolveWorkerJar(): String {
        System.getenv("SCREENSHOTTER_WORKER_JAR")?.trim()?.takeUnless { it.isEmpty() }?.let { return it }

        // codeSource.location is either this server's own jar file (a packaged run) or its
        // target/classes directory (run straight from Maven/an IDE) - either way it's a direct
        // child of server/target/, so three parents up lands on the project root regardless.
        val serverLocation = Paths.get(
            SandboxManager::class.java.protectionDomain.codeSource.location.toURI()
        )
        val projectRoot = serverLocation.parent.parent.parent
        val workerJar = projectRoot.resolve("worker").resolve("target").resolve("screenshotter-worker.jar")

        if (!Files.isRegularFile(workerJar)) {
            throw RuntimeException(
                "Worker jar not found at $workerJar. Run 'mvn clean package' first, or set " +
                    "SCREENSHOTTER_WORKER_JAR to an explicit path."
            )
        }
        return workerJar.toAbsolutePath().toString()
    }

    /**
     * Builds the launch command for `backend`; both take the same `resolution` (see
     * [displayResolutionFromEnv], e.g. "1024x768") and `-displayfd 1` (report the display number
     * they picked on stdout instead of taking one on the command line - see the comment in
     * start()), just in their own flag syntax.
     */
    internal fun displayCommand(backend: DisplayBackend, resolution: String): List<String> = when (backend) {
        DisplayBackend.XEPHYR -> listOf("Xephyr", "-screen", resolution, "-displayfd", "1")
        // Xvfb additionally wants a color depth suffixed onto the resolution (24-bit here, same
        // as before this became configurable).
        DisplayBackend.XVFB -> listOf("Xvfb", "-screen", "0", "${resolution}x24", "-displayfd", "1")
    }

    /** A fixed, built-in GTK theme/font/icon set, so widget layout and text metrics are the same on every machine. */
    private fun writeGtkSettings(home: File) {
        val gtkConfigDir = File(home, ".config/gtk-3.0")
        gtkConfigDir.mkdirs()
        File(gtkConfigDir, "settings.ini").writeText(
            """
            [Settings]
            gtk-theme-name=Adwaita
            gtk-icon-theme-name=Adwaita
            gtk-cursor-theme-name=Adwaita
            gtk-font-name=DejaVu Sans 10
            """.trimIndent() + "\n"
        )
    }

    /**
     * Starts `command` inside its own `setsid` session - so it (and anything it spawns) becomes
     * one process group whose pgid equals its own pid - and records that pgid in
     * `pgidRegistryFile`, so both stop() and the crash-only watchdog can reap it uniformly.
     */
    private fun startTracked(command: List<String>, configure: (ProcessBuilder) -> Unit = {}): Process {
        val pb = ProcessBuilder(listOf("setsid") + command)
        configure(pb)
        val proc = pb.start()
        pgidRegistryFile.appendText("${proc.pid()}\n")
        return proc
    }

    /**
     * Spawns a tiny shell watchdog whose only job is to notice that *we* have died - for any
     * reason, including `kill -9`, an OOM-kill, or a native crash in JNA/X11 code that never
     * reaches stop()'s shutdown hook - and reap every tracked process group even then.
     *
     * The trick: the watchdog blocks reading its own stdin, which is a pipe whose write end we
     * (the JVM) hold open for our entire lifetime and never write to or close ourselves. The
     * moment we die, for any reason, the kernel closes every file descriptor we held - including
     * that pipe's write end - as part of normal process teardown, no cooperation from us
     * required. The watchdog's read() then returns EOF, and it cleans up on its own.
     */
    private fun startWatchdog() {
        // Same safety check as safeDeleteRecursively(): only remove sandboxHome if it's still
        // unmistakably one of our own (a direct child of the temp dir named
        // mcp-screenshotter-home-*), not blindly `rm -rf` whatever is at that path by the time we
        // crash. See safeDeleteRecursively()'s doc comment for why this checks identity rather
        // than counting entries.
        val tmpDir = System.getProperty("java.io.tmpdir")
        val script = """
            cat >/dev/null
            while IFS= read -r pgid; do
                [ -n "${'$'}pgid" ] && kill -TERM -"${'$'}pgid" 2>/dev/null
            done < "${pgidRegistryFile.absolutePath}"
            rm -f "${pgidRegistryFile.absolutePath}"
            sandbox_home="${sandboxHome.absolutePath}"
            case "${'$'}sandbox_home" in
                "$tmpDir"/mcp-screenshotter-home-*)
                    rm -rf "${'$'}sandbox_home"
                    ;;
                *)
                    echo "Refusing to delete ${'$'}sandbox_home: doesn't look like one of our own sandbox home directories." >&2
                    ;;
            esac
        """.trimIndent()
        watchdogProc = ProcessBuilder("sh", "-c", script).start()
    }

    fun sendCommand(command: Map<String, Any?>): Map<String, Any> {
        val reqJson = mapper.writeValueAsString(command)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$workerPort/execute"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(reqJson))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        @Suppress("UNCHECKED_CAST")
        val resMap = mapper.readValue(response.body(), Map::class.java) as Map<String, Any>
        if (resMap["status"] == "error") {
            throw RuntimeException((resMap["error"] as? String) ?: "Unknown worker error")
        }
        return resMap
    }

    fun launchApp(command: String, mounts: List<Mount> = emptyList()): Int {
        // Same tracked-group treatment as the sandbox's own services: the launched command (and
        // any children it spawns) gets its own process group so stop()/the watchdog can tear the
        // whole tree down, instead of leaking it the way plain ProcessBuilder.destroy() would
        // (see FUTURE_WORK #8/#9).
        val proc = startTracked(bwrapCommand(command, mounts)) { pb ->
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            pb.environment().clear()
            sandboxEnv?.let { pb.environment().putAll(it) }
        }
        val pid = proc.pid().toInt()
        launchedApps[pid] = command
        // Evict as soon as the app exits on its own, instead of only ever on an explicit
        // close_app - see closeApp()'s doc comment for why a stale entry here is a real hazard,
        // not just a memory leak: the OS can and does recycle PIDs.
        proc.onExit().thenRun { launchedApps.remove(pid) }
        return pid
    }

    /**
     * Lists every viewable top-level window in the sandbox display, annotated - when resolvable -
     * with the [launchApp]-returned PID of the app it belongs to, so a caller can tell which
     * window came from which `launch_app` call instead of guessing from geometry/title alone.
     *
     * The worker reports each window's *actual* OS PID (from `_NET_WM_PID` - e.g. the JVM or
     * Python process the app command ultimately exec'd into), which generally differs from the
     * PID [launchApp] returned (the `setsid`+`bwrap` wrapper's PID). Both live in the same PID
     * namespace, though (`launch_app` only isolates the mount namespace - see `bwrapCommand()`),
     * and every descendant of that wrapper inherits its process *group* ID unless it explicitly
     * changes it (nothing here does), so resolving the window's PID to its process group via
     * `/proc/<pid>/stat` reliably maps it back to the PID `launch_app` handed out.
     */
    fun listWindows(): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val windows = sendCommand(mapOf("action" to "listWindows"))["windows"] as? List<Map<String, Any?>> ?: emptyList()
        return windows.map { w ->
            val pid = (w["pid"] as? Number)?.toLong()
            val launchedPid = pid?.let { readProcessGroupId(it) }
            w + mapOf(
                "launchedPid" to launchedPid,
                "command" to launchedPid?.let { launchedApps[it.toInt()] }
            )
        }
    }

    /** Reads the process group ID (field 5 of `/proc/<pid>/stat`) of a still-running process, or null. */
    internal fun readProcessGroupId(pid: Long): Long? {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            // `comm` (2nd field) is wrapped in parens and may itself contain spaces/parens, so
            // split after the *last* ')' rather than just splitting on whitespace throughout.
            val fieldsAfterComm = stat.substringAfterLast(')').trim().split(" ")
            fieldsAfterComm[2].toLong() // state(0) ppid(1) pgrp(2)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Terminates a specific app previously started via [launchApp], identified by the PID it
     * returned (which is also that app's process group, see [launchApp]'s doc comment) - without
     * touching the sandbox's own services (display server, D-Bus, worker) or any other launched
     * app. Refuses (returns false) for any PID this manager didn't itself hand out, so this can't
     * be turned into an arbitrary-process-kill primitive by a caller guessing PIDs.
     *
     * [launchApp] evicts `pid` from [launchedApps] the moment the process exits on its own, which
     * closes most of the window for the OS recycling `pid` onto an unrelated process before a
     * later close_app call reuses the same number - but eviction races with a client's close_app
     * call arriving at almost the same instant, so as a second, independent check, also require
     * that `pid` is still its own process group leader (`setsid`, in [startTracked], guarantees
     * every app we launch has `pgid == pid` for as long as it's alive; an unrelated process that
     * inherited the recycled number would only coincidentally satisfy that too).
     */
    fun closeApp(pid: Int, force: Boolean = false): Boolean {
        if (!launchedApps.containsKey(pid)) return false
        if (readProcessGroupId(pid.toLong()) != pid.toLong()) {
            launchedApps.remove(pid)
            return false
        }
        killProcessGroup(pid, if (force) "KILL" else "TERM")
        launchedApps.remove(pid)
        return true
    }

    /**
     * Wraps `command` in a `bwrap` (bubblewrap) mount namespace so the launched app can't see the
     * *real* HOME directory at all - not even by bypassing the HOME env var and going straight to
     * getpwuid()'s reported home dir - only whatever `mounts` the caller explicitly asked for.
     * The rest of the filesystem (libs, binaries, /tmp for the X11/D-Bus sockets) stays available
     * read-only so the app can actually run; only the real HOME is shadowed, and only our own
     * synthetic sandboxHome (holding the pinned GTK settings) is writable on top of that.
     */
    internal fun bwrapCommand(command: String, mounts: List<Mount>): List<String> {
        val realHome = System.getenv("HOME") ?: "/tmp"
        // The server's own working directory (this repo, typically) is very often *under* the
        // real HOME (e.g. ~/projects/foo) and launch_app commands routinely reference it via
        // relative paths (see e2e/test_gui.py's own `python3 e2e/sample_app.py`), so blanket-
        // hiding HOME would take that out from under them too. Explicitly re-expose just this one
        // directory - not the rest of HOME - on top of the tmpfs shadow below.
        val serverCwd = File(System.getProperty("user.dir")).absolutePath
        val args = mutableListOf(
            "bwrap",
            "--ro-bind", "/", "/",
            "--dev", "/dev",
            "--proc", "/proc",
            "--bind", "/tmp", "/tmp", // the display server's and dbus-daemon's unix sockets live under here
            "--tmpfs", realHome, // hide the real user's HOME entirely, regardless of $HOME env var
            "--bind", sandboxHome.absolutePath, sandboxHome.absolutePath, // our own HOME stays writable
            "--ro-bind", serverCwd, serverCwd
        )
        for (mount in mounts) {
            args += if (mount.readOnly) "--ro-bind" else "--bind"
            args += mount.hostPath
            args += mount.sandboxPath
        }
        args += listOf("--", "sh", "-c", command)
        return args
    }

    /**
     * Sends `signal` ("TERM" or "KILL") to `pgid` and every one of its descendants, one PID at a
     * time via [ProcessHandle] - not by shelling out to `kill -$signal -$pgid` (negative PID =
     * process group, see kill(2)), which is what this used to do.
     *
     * That approach was replaced after tracking down a real close_app bug: on this box, `kill
     * -TERM -$pgid` run via `ProcessBuilder` was observed under `strace` to sometimes deliver the
     * signal to the wrong target entirely - `kill(-4, SIGTERM)` instead of the intended pgid, e.g.
     * `kill(-4075250, SIGTERM)` - a string-argument-parsing quirk in this system's `procps` build
     * that doesn't reproduce with bash's builtin `kill`. `close_app` would report success (the
     * external `kill` process exits 0 either way) while the sandboxed app kept running completely
     * untouched. [ProcessHandle.destroy]/[ProcessHandle.destroyForcibly] call `kill(2)` directly
     * on each PID from inside the JVM, with no external process and no string arguments to
     * mis-parse in between.
     *
     * When `SCREENSHOTTER_DRY_RUN_KILL` is set (any non-empty value), no signal is actually sent -
     * this just logs what it would have done. A safety valve for exercising close_app()/stop()'s
     * targeting logic (which pgid, when) without any risk of an actual kill reaching further than
     * intended while that's still being investigated.
     */
    private fun killProcessGroup(pgid: Int, signal: String = "TERM") {
        if (pgid <= 0) return
        if (!System.getenv("SCREENSHOTTER_DRY_RUN_KILL").isNullOrEmpty()) {
            System.err.println("[DRY RUN] would send $signal to pid $pgid and its descendants")
            return
        }
        val leader = ProcessHandle.of(pgid.toLong()).orElse(null)
        if (leader == null) {
            System.err.println("[closeApp] pid $pgid is already gone, nothing to signal")
            return
        }
        val targets = leader.descendants().toList() + leader
        for (h in targets) {
            val sent = if (signal == "KILL") h.destroyForcibly() else h.destroy()
            System.err.println("[closeApp] sent $signal to pid ${h.pid()} (part of group $pgid): $sent")
        }
    }

    fun stop() {
        if (pgidRegistryFile.exists()) {
            pgidRegistryFile.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { killProcessGroup(it) }
        }
        workerProc?.destroy()
        atSpiProc?.destroy()
        vncProc?.destroy()
        displayProc?.destroy()
        dbusProc?.destroy() // belt-and-suspenders in case the group kill above missed it
        watchdogProc?.destroy() // we're exiting cleanly ourselves, no need for its crash safety net
        pgidRegistryFile.delete()
        safeDeleteRecursively(sandboxHome)
    }

    /**
     * Recursively deletes `dir`, but refuses - logging an error and deleting nothing - unless it's
     * unmistakably one of our own sandbox home directories: a direct child of the system temp
     * directory whose name starts with the same prefix [sandboxHome] itself is created with.
     *
     * This used to gate on a file *count* (refusing above 100 entries) instead, on the theory that
     * `sandboxHome` should only ever hold a handful of files. That was wrong in practice: it's a
     * writable HOME for *every* `launch_app`'d process, and an ordinary GTK/Qt app routinely
     * dumps icon-cache/fontconfig-cache/similar well past 100 files into it within seconds - a
     * high count there is normal, not a sign that this call is pointed at the wrong path. Checking
     * *identity* (is this really one of ours?) instead of *size* catches the actual failure mode
     * this guards against - a future bug reassigning `dir` to something broader, like the real
     * HOME - without ever refusing a legitimate cleanup.
     */
    internal fun safeDeleteRecursively(dir: File) {
        if (!dir.exists()) return
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val looksLikeOurs = dir.parentFile == tmpDir && dir.name.startsWith("mcp-screenshotter-home-")
        if (!looksLikeOurs) {
            System.err.println(
                "Refusing to delete $dir: doesn't look like one of our own sandbox home directories " +
                    "(expected a child of $tmpDir named mcp-screenshotter-home-*). Leaving it in place - " +
                    "please inspect and remove it manually."
            )
            return
        }
        dir.deleteRecursively()
    }
}

// Global sandbox instance
val sandbox = SandboxManager()

fun main() {
    sandbox.start()
    
    val mapper = jacksonObjectMapper()
    val scanner = Scanner(System.`in`)
    System.err.println("MCP Server started (Headless Orchestrator mode).")

    while (scanner.hasNextLine()) {
        val line = scanner.nextLine()
        if (line.isBlank()) continue

        try {
            val request = mapper.readValue(line, JsonRpcRequest::class.java)
            val responseResult = handleMethod(request.method, request.params)
            
            if (request.id != null) {
                val response = JsonRpcResponse(id = request.id, result = responseResult)
                println(mapper.writeValueAsString(response))
            }
        } catch (e: Throwable) {
            System.err.println("Error processing message: ${e.message}")
            e.printStackTrace(System.err)
        }
    }
    sandbox.stop()
}

internal fun handleMethod(method: String, params: Map<String, Any>?): Any? {
    val mapper = jacksonObjectMapper()
    return when (method) {
        "initialize" -> {
            InitResult(
                protocolVersion = "2024-11-05",
                capabilities = mapOf("tools" to emptyMap<String, Any>()),
                serverInfo = mapOf("name" to "screenshotter-mcp-server", "version" to "0.2.0")
            )
        }
        "initialized" -> emptyMap<String, Any>()
        "tools/list" -> {
            mapOf("tools" to listOf(
                ToolInfo(
                    name = "get_screenshot",
                    description = "Takes a screenshot of the current environment.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "x" to mapOf("type" to "integer"),
                            "y" to mapOf("type" to "integer"),
                            "width" to mapOf("type" to "integer"),
                            "height" to mapOf("type" to "integer"),
                            "include_deltas" to mapOf("type" to "boolean"),
                            "threshold" to mapOf("type" to "number"),
                            "max_width" to mapOf(
                                "type" to "integer",
                                "description" to "Downscale the returned image to at most this many pixels wide " +
                                    "(aspect ratio preserved) to cut vision-token cost when full detail isn't " +
                                    "needed. Omit for full resolution."
                            )
                        )
                    )
                ),
                ToolInfo(
                    name = "mouse_action",
                    description = "Performs a mouse action at the given coordinates. Supported actions: " +
                        "'move', 'click', 'press' and 'release' (pair these two around intermediate 'move' " +
                        "calls to perform a drag, e.g. to resize a split pane), and 'scroll' (uses 'amount' " +
                        "as the number of wheel notches; positive scrolls down, negative scrolls up).",
                    inputSchema = mapOf(
                        "type" to "object",
                        "required" to listOf("action", "x", "y"),
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "x" to mapOf("type" to "integer"),
                            "y" to mapOf("type" to "integer"),
                            "amount" to mapOf("type" to "integer", "description" to "Wheel notches for the 'scroll' action.")
                        )
                    )
                ),
                ToolInfo(
                    name = "resize_window",
                    description = "Resizes a window in the sandbox display. With no 'window_id', resizes every " +
                        "top-level window (fine with a single app open, ambiguous otherwise) - pass the " +
                        "'window_id' from 'list_windows' to target just one window.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "required" to listOf("width", "height"),
                        "properties" to mapOf(
                            "width" to mapOf("type" to "integer"),
                            "height" to mapOf("type" to "integer"),
                            "window_id" to mapOf(
                                "type" to "integer",
                                "description" to "X11 window ID (from 'list_windows') to resize. Omit to resize every top-level window."
                            )
                        )
                    )
                ),
                ToolInfo(
                    name = "get_ui_tree",
                    description = "Gets the AT-SPI accessibility tree.",
                    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                ),
                ToolInfo(
                    name = "get_clipboard",
                    description = "Reads text from the clipboard.",
                    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                ),
                ToolInfo(
                    name = "set_clipboard",
                    description = "Writes text to the clipboard.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf("text" to mapOf("type" to "string")),
                        "required" to listOf("text")
                    )
                ),
                ToolInfo(
                    name = "highlight_area",
                    description = "Takes a screenshot with a red highlight box.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "required" to listOf("x", "y", "width", "height"),
                        "properties" to mapOf(
                            "x" to mapOf("type" to "integer"),
                            "y" to mapOf("type" to "integer"),
                            "width" to mapOf("type" to "integer"),
                            "height" to mapOf("type" to "integer"),
                            "max_width" to mapOf(
                                "type" to "integer",
                                "description" to "Downscale the returned image to at most this many pixels wide " +
                                    "(aspect ratio preserved) to cut vision-token cost when full detail isn't " +
                                    "needed. Omit for full resolution."
                            )
                        )
                    )
                ),
                ToolInfo(
                    name = "detect_ui_elements",
                    description = "Uses OpenCV to visually detect bounds of UI elements.",
                    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                ),
                ToolInfo(
                    name = "launch_app",
                    description = "Launches an application inside the isolated GUI environment. The app runs in its " +
                        "own mount namespace with an empty HOME - it cannot see the real user's files at all, even " +
                        "by bypassing the HOME env var - unless you explicitly expose a directory via 'mounts'.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "command" to mapOf("type" to "string", "description" to "The shell command to execute, e.g., 'java -cp e2e SampleApp'."),
                            "mounts" to mapOf(
                                "type" to "array",
                                "description" to "Host directories to make visible inside the sandbox. Everything else under HOME stays hidden.",
                                "items" to mapOf(
                                    "type" to "object",
                                    "required" to listOf("host_path"),
                                    "properties" to mapOf(
                                        "host_path" to mapOf("type" to "string", "description" to "Absolute path on the real filesystem."),
                                        "sandbox_path" to mapOf("type" to "string", "description" to "Path it appears at inside the sandbox. Defaults to host_path."),
                                        "read_only" to mapOf("type" to "boolean", "description" to "Defaults to true.")
                                    )
                                )
                            )
                        ),
                        "required" to listOf("command")
                    )
                ),
                ToolInfo(
                    name = "list_windows",
                    description = "Lists every top-level window currently visible in the sandbox display, with " +
                        "its position/size and, when resolvable, the PID that 'launch_app' returned for the app " +
                        "it belongs to (as 'launched_pid') and the command it was launched with. Use this to " +
                        "figure out which window belongs to which launched app once more than one is open.",
                    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                ),
                ToolInfo(
                    name = "close_app",
                    description = "Terminates an app previously started via 'launch_app', identified by the PID " +
                        "it returned. Only affects that one app - the sandbox and any other launched apps keep " +
                        "running.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "required" to listOf("pid"),
                        "properties" to mapOf(
                            "pid" to mapOf("type" to "integer", "description" to "The PID returned by 'launch_app'."),
                            "force" to mapOf("type" to "boolean", "description" to "Send SIGKILL instead of SIGTERM. Defaults to false.")
                        )
                    )
                )
            ))
        }
        "tools/call" -> {
            val name = params?.get("name") as? String
            @Suppress("UNCHECKED_CAST")
            val arguments = params?.get("arguments") as? Map<String, Any>
            
            try {
                if (name == "get_screenshot") {
                    val req = mutableMapOf<String, Any?>("action" to "takeScreenshot")
                    if (arguments != null) {
                        req.putAll(arguments)
                    }
                    // Map snake_case to camelCase
                    if (req.containsKey("include_deltas")) req["includeDeltas"] = req.remove("include_deltas")
                    if (req.containsKey("max_width")) req["maxWidth"] = req.remove("max_width")
                    
                    val res = sandbox.sendCommand(req)
                    
                    if (res["changed"] == false) {
                        ToolResult(content = listOf(mapOf(
                            "type" to "text",
                            "text" to mapper.writeValueAsString(mapOf(
                                "changed" to false,
                                "changed_areas" to res["changedAreas"]
                            ))
                        )))
                    } else {
                        val content = mutableListOf<Map<String, Any>>(
                            mapOf(
                                "type" to "image",
                                "mimeType" to "image/png",
                                "data" to (res["imageB64"] as String)
                            )
                        )
                        if (req["includeDeltas"] == true) {
                            content.add(mapOf(
                                "type" to "text",
                                "text" to mapper.writeValueAsString(mapOf("changed_areas" to res["changedAreas"]))
                            ))
                        }
                        ToolResult(content = content)
                    }
                } else if (name == "mouse_action") {
                    sandbox.sendCommand(mapOf(
                        "action" to "mouseAction",
                        "mouseAction" to (arguments?.get("action") as? String ?: "move"),
                        "x" to ((arguments?.get("x") as? Number)?.toInt() ?: 0),
                        "y" to ((arguments?.get("y") as? Number)?.toInt() ?: 0),
                        "amount" to ((arguments?.get("amount") as? Number)?.toInt() ?: 0)
                    ))
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Mouse action executed.")))
                } else if (name == "resize_window") {
                    sandbox.sendCommand(mapOf(
                        "action" to "resizeWindow",
                        "width" to ((arguments?.get("width") as? Number)?.toInt() ?: 1024),
                        "height" to ((arguments?.get("height") as? Number)?.toInt() ?: 768),
                        "windowId" to (arguments?.get("window_id") as? Number)?.toLong()
                    ))
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Window resized.")))
                } else if (name == "list_windows") {
                    val windows = sandbox.listWindows().map { w ->
                        mapOf(
                            "window_id" to w["windowId"],
                            "pid" to w["pid"],
                            "launched_pid" to w["launchedPid"],
                            "command" to w["command"],
                            "title" to w["title"],
                            "x" to w["x"],
                            "y" to w["y"],
                            "width" to w["width"],
                            "height" to w["height"]
                        )
                    }
                    ToolResult(content = listOf(mapOf(
                        "type" to "text",
                        "text" to mapper.writeValueAsString(mapOf("windows" to windows))
                    )))
                } else if (name == "close_app") {
                    val pid = (arguments?.get("pid") as? Number)?.toInt()
                    if (pid == null) {
                        ToolResult(content = listOf(mapOf("type" to "text", "text" to "Error: 'pid' is required.")), isError = true)
                    } else {
                        val force = (arguments["force"] as? Boolean) ?: false
                        val closed = sandbox.closeApp(pid, force)
                        if (closed) {
                            ToolResult(content = listOf(mapOf("type" to "text", "text" to "App with PID $pid terminated.")))
                        } else {
                            ToolResult(content = listOf(mapOf(
                                "type" to "text",
                                "text" to "Error: no app launched via 'launch_app' with PID $pid (already closed, or never launched by this server)."
                            )), isError = true)
                        }
                    }
                } else if (name == "get_ui_tree") {
                    val res = sandbox.sendCommand(mapOf("action" to "getUiTree"))
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(res["tree"]))))
                } else if (name == "get_clipboard") {
                    val res = sandbox.sendCommand(mapOf("action" to "getClipboard"))
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to ((res["text"] as? String) ?: ""))))
                } else if (name == "set_clipboard") {
                    sandbox.sendCommand(mapOf("action" to "setClipboard", "text" to ((arguments?.get("text") as? String) ?: "")))
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Clipboard updated.")))
                } else if (name == "highlight_area") {
                    val res = sandbox.sendCommand(mapOf(
                        "action" to "highlightArea",
                        "x" to ((arguments?.get("x") as? Number)?.toInt() ?: 0),
                        "y" to ((arguments?.get("y") as? Number)?.toInt() ?: 0),
                        "width" to ((arguments?.get("width") as? Number)?.toInt() ?: 100),
                        "height" to ((arguments?.get("height") as? Number)?.toInt() ?: 100),
                        "maxWidth" to (arguments?.get("max_width") as? Number)?.toInt()
                    ))
                    ToolResult(content = listOf(mapOf(
                        "type" to "image",
                        "mimeType" to "image/png",
                        "data" to (res["imageB64"] as String)
                    )))
                } else if (name == "detect_ui_elements") {
                    val res = sandbox.sendCommand(mapOf("action" to "detectUiElements"))
                    ToolResult(content = listOf(mapOf(
                        "type" to "text",
                        "text" to mapper.writeValueAsString(mapOf("detected_elements" to res["detectedElements"]))
                    )))
                } else if (name == "launch_app") {
                    val command = (arguments?.get("command") as? String) ?: ""
                    if (command.isBlank()) {
                        ToolResult(content = listOf(mapOf("type" to "text", "text" to "Error: Command cannot be empty.")), isError = true)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val mounts = (arguments?.get("mounts") as? List<Map<String, Any>> ?: emptyList()).map { m ->
                            // Required per the tool's own schema, but never enforced - a missing
                            // (or wrong-typed) host_path used to surface as a bare
                            // ClassCastException via the catch-all below instead of this message.
                            val hostPath = m["host_path"] as? String
                                ?: throw IllegalArgumentException("mounts[].host_path is required and must be a string.")
                            Mount(
                                hostPath = hostPath,
                                sandboxPath = (m["sandbox_path"] as? String) ?: hostPath,
                                readOnly = (m["read_only"] as? Boolean) ?: true
                            )
                        }
                        val pid = sandbox.launchApp(command, mounts)
                        ToolResult(content = listOf(mapOf(
                            "type" to "text",
                            "text" to "Application launched successfully with PID $pid inside the sandbox."
                        )))
                    }
                } else {
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Unknown tool: $name")))
                }
            } catch (e: Throwable) {
                ToolResult(content = listOf(mapOf("type" to "text", "text" to (e.message ?: e.toString()))), isError = true)
            }
        }
        else -> emptyMap<String, Any>()
    }
}
