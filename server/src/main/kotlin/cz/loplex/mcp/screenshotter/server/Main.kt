package cz.loplex.mcp.screenshotter.server

import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private var dbusPgid: Int = -1
    private var atSpiProc: Process? = null
    private var workerProc: Process? = null
    private val launchedAppPgids = mutableListOf<Int>()

    private var workerPort: Int = -1
    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    private var sandboxEnv: Map<String, String>? = null

    fun start() {
        System.err.println("Starting sandbox environment...")
        
        // 1. Start Xephyr
        val displayNum = findFreeDisplay()
        val display = ":$displayNum"
        xephyrProc = ProcessBuilder("Xephyr", "-screen", "1024x768", display).start()
        Thread.sleep(1000) // Wait for X11 to initialize
        
        System.err.println("Xephyr started on $display")

        // 2. Start D-Bus
        // Deliberately run in the foreground (--nofork) inside its own `setsid` session: this
        // keeps a live Process handle instead of letting the daemon fork-and-detach into a PID
        // we never captured (the leak described in FUTURE_WORK #9), and the `setsid` group lets
        // stop() reap it - and any D-Bus-activated helper it spawns - as a single unit via
        // killProcessGroup(), instead of leaving them orphaned.
        val pbDbus = ProcessBuilder("setsid", "dbus-daemon", "--session", "--print-address=1", "--nofork")
        pbDbus.redirectErrorStream(true)
        dbusProc = pbDbus.start()
        dbusPgid = dbusProc!!.pid().toInt()

        val dbusOut = BufferedReader(InputStreamReader(dbusProc!!.inputStream, "UTF-8"))
        val dbusAddress = dbusOut.readLine()?.trim()
            ?: throw RuntimeException("Failed to read D-Bus address from dbus-daemon.")

        // Keep draining the rest of its output so the pipe never fills up and blocks the daemon
        Thread {
            try {
                while (dbusOut.readLine() != null) { /* discard */ }
            } catch (ignored: Exception) {}
        }.start()

        System.err.println("D-Bus started at $dbusAddress (pgid=$dbusPgid)")

        // Base minimal environment for the sandbox
        sandboxEnv = mutableMapOf(
            "DISPLAY" to display,
            "DBUS_SESSION_BUS_ADDRESS" to dbusAddress,
            "HOME" to (System.getenv("HOME") ?: "/tmp"),
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin")
        )
        System.getenv("USER")?.let { (sandboxEnv as MutableMap)["USER"] = it }

        // 3. Start AT-SPI2
        val pbAtSpi = ProcessBuilder("/usr/libexec/at-spi-bus-launcher", "--launch-immediately")
        pbAtSpi.environment().clear()
        pbAtSpi.environment().putAll(sandboxEnv!!)
        atSpiProc = pbAtSpi.start()
        Thread.sleep(1000)

        // 4. Start Worker
        val workerJar = Paths.get("worker/target/screenshotter-worker-0.1.0-SNAPSHOT-jar-with-dependencies.jar").toAbsolutePath().toString()
        val pbWorker = ProcessBuilder("java", "-Djava.awt.headless=false", "-jar", workerJar, "0")
        
        // Use strictly isolated environment
        pbWorker.environment().clear()
        pbWorker.environment().putAll(sandboxEnv!!)
        
        // We will read worker's stderr to find out what port it started on
        pbWorker.redirectErrorStream(true)
        workerProc = pbWorker.start()
        
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
        
        // Ensure cleanup on shutdown
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
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
        // Same `setsid` treatment as D-Bus: the launched command (and any children it spawns)
        // gets its own process group so stop() can tear the whole tree down, instead of
        // leaking it the way plain ProcessBuilder.destroy() would (see FUTURE_WORK #8/#9).
        val pb = ProcessBuilder("setsid", "sh", "-c", command)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.environment().clear()
        sandboxEnv?.let { pb.environment().putAll(it) }
        val proc = pb.start()
        val pgid = proc.pid().toInt()
        launchedAppPgids.add(pgid)
        return pgid
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
            if (!java.io.File("/tmp/.X11-unix/X$i").exists()) {
                return i
            }
        }
        return 99
    }

    fun stop() {
        launchedAppPgids.forEach { killProcessGroup(it) }
        killProcessGroup(dbusPgid)
        dbusProc?.destroy() // belt-and-suspenders in case the group kill above missed it
        workerProc?.destroy()
        atSpiProc?.destroy()
        xephyrProc?.destroy()
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
