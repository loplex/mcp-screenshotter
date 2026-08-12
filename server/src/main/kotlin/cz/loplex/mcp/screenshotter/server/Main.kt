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

class SandboxManager {
    private var xephyrProc: Process? = null
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

    fun start() {
        System.err.println("Starting sandbox environment...")

        // Start the watchdog first, before anything it might need to clean up even exists -
        // see startWatchdog() for how it detects our death and why.
        startWatchdog()

        // 1. Start Xephyr
        val displayNum = findFreeDisplay()
        val display = ":$displayNum"
        xephyrProc = startTracked(listOf("Xephyr", "-screen", "1024x768", display))
        Thread.sleep(1000) // Wait for X11 to initialize

        System.err.println("Xephyr started on $display")

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
        val workerJar = Paths.get("worker/target/screenshotter-worker-0.1.0-SNAPSHOT-jar-with-dependencies.jar").toAbsolutePath().toString()
        workerProc = startTracked(listOf("java", "-Djava.awt.headless=false", "-jar", workerJar, "0")) { pb ->
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
        val script = """
            cat >/dev/null
            while IFS= read -r pgid; do
                [ -n "${'$'}pgid" ] && kill -TERM -"${'$'}pgid" 2>/dev/null
            done < "${pgidRegistryFile.absolutePath}"
            rm -rf "${pgidRegistryFile.absolutePath}" "${sandboxHome.absolutePath}"
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

    fun launchApp(command: String): Int {
        // Same tracked-group treatment as the sandbox's own services: the launched command (and
        // any children it spawns) gets its own process group so stop()/the watchdog can tear the
        // whole tree down, instead of leaking it the way plain ProcessBuilder.destroy() would
        // (see FUTURE_WORK #8/#9).
        val proc = startTracked(listOf("sh", "-c", command)) { pb ->
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            pb.environment().clear()
            sandboxEnv?.let { pb.environment().putAll(it) }
        }
        return proc.pid().toInt()
    }

    /** Sends `signal` to every process in `pgid` at once (negative PID = process group, see kill(2)). */
    private fun killProcessGroup(pgid: Int, signal: String = "TERM") {
        if (pgid <= 0) return
        try {
            ProcessBuilder("kill", "-$signal", "-$pgid").start().waitFor()
        } catch (ignored: Exception) {}
    }

    private fun findFreeDisplay(): Int {
        for (i in 1..99) {
            if (!File("/tmp/.X11-unix/X$i").exists()) {
                return i
            }
        }
        return 99
    }

    fun stop() {
        if (pgidRegistryFile.exists()) {
            pgidRegistryFile.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { killProcessGroup(it) }
        }
        workerProc?.destroy()
        atSpiProc?.destroy()
        xephyrProc?.destroy()
        dbusProc?.destroy() // belt-and-suspenders in case the group kill above missed it
        watchdogProc?.destroy() // we're exiting cleanly ourselves, no need for its crash safety net
        pgidRegistryFile.delete()
        sandboxHome.deleteRecursively()
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

private fun handleMethod(method: String, params: Map<String, Any>?): Any? {
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
                            "threshold" to mapOf("type" to "number")
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
                    description = "Resizes the active window.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "required" to listOf("width", "height"),
                        "properties" to mapOf(
                            "width" to mapOf("type" to "integer"),
                            "height" to mapOf("type" to "integer")
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
                            "height" to mapOf("type" to "integer")
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
                    description = "Launches an application inside the isolated GUI environment.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "command" to mapOf("type" to "string", "description" to "The shell command to execute, e.g., 'java -cp e2e SampleApp'.")
                        ),
                        "required" to listOf("command")
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
                        "height" to ((arguments?.get("height") as? Number)?.toInt() ?: 768)
                    ))
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Window resized.")))
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
                        "height" to ((arguments?.get("height") as? Number)?.toInt() ?: 100)
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
                        val pid = sandbox.launchApp(command)
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
