package cz.loplex.mcp.screenshotter

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Scanner

data class JsonRpcRequest(val jsonrpc: String, val id: JsonNode?, val method: String, val params: JsonNode?)
data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: JsonNode?, val result: Any? = null, val error: Any? = null)

data class ToolInfo(val name: String, val description: String, val inputSchema: Map<String, Any>)
data class InitResult(val protocolVersion: String, val capabilities: Map<String, Any>, val serverInfo: Map<String, Any>)
data class ToolResult(val content: List<Map<String, Any>>, val isError: Boolean = false)

fun main() {
    val server = ScreenshotterServer()
    val mapper = jacksonObjectMapper()
    val scanner = Scanner(System.`in`)

    System.err.println("MCP Screenshotter Server started (Standard IO mode).")

    while (scanner.hasNextLine()) {
        val line = scanner.nextLine()
        if (line.isBlank()) continue

        try {
            val request = mapper.readValue(line, JsonRpcRequest::class.java)
            val responseResult = handleMethod(request.method, request.params, server)
            
            if (request.id != null) {
                val response = JsonRpcResponse(id = request.id, result = responseResult)
                println(mapper.writeValueAsString(response))
            }
        } catch (e: Exception) {
            System.err.println("Error processing message: ${e.message}")
        }
    }
}

private fun handleMethod(method: String, params: JsonNode?, server: ScreenshotterServer): Any? {
    return when (method) {
        "initialize" -> {
            InitResult(
                protocolVersion = "2024-11-05",
                capabilities = mapOf("tools" to emptyMap<String, Any>()),
                serverInfo = mapOf("name" to "screenshotter-mcp-server", "version" to "0.1.0")
            )
        }
        "initialized" -> {
            emptyMap<String, Any>()
        }
        "tools/list" -> {
            mapOf("tools" to listOf(
                ToolInfo(
                    name = "get_screenshot",
                    description = "Takes a full screenshot of the current environment.",
                    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                ),
                ToolInfo(
                    name = "mouse_action",
                    description = "Performs a mouse action (click, move) at the given coordinates.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "required" to listOf("action", "x", "y"),
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "x" to mapOf("type" to "integer"),
                            "y" to mapOf("type" to "integer")
                        )
                    )
                ),
                ToolInfo(
                    name = "resize_window",
                    description = "Resizes the active application window to the given width and height.",
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
                    description = "Gets the AT-SPI accessibility tree of the current desktop (buttons, labels, bounds, etc).",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>()
                    )
                ),
                ToolInfo(
                    name = "get_clipboard",
                    description = "Reads text from the system clipboard.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>()
                    )
                ),
                ToolInfo(
                    name = "set_clipboard",
                    description = "Writes text to the system clipboard.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "text" to mapOf("type" to "string")
                        ),
                        "required" to listOf("text")
                    )
                ),
                ToolInfo(
                    name = "highlight_area",
                    description = "Takes a screenshot with a red highlight box drawn over the specified area.",
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
                    description = "Uses OpenCV to visually detect bounds of UI elements (buttons, inputs) in the current screen. Fallback for when get_ui_tree doesn't work.",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>()
                    )
                )
            ))
        }
        "tools/call" -> {
            val name = params?.get("name")?.asText()
            val arguments = params?.get("arguments")
            
            try {
                if (name == "get_screenshot") {
                    val img = server.takeFullScreenshot()
                    val b64 = server.imageToBase64(img)
                    ToolResult(content = listOf(mapOf(
                        "type" to "image",
                        "mimeType" to "image/png",
                        "data" to b64
                    )))
                } else if (name == "mouse_action") {
                    val action = arguments?.get("action")?.asText() ?: "move"
                    val x = arguments?.get("x")?.asInt() ?: 0
                    val y = arguments?.get("y")?.asInt() ?: 0
                    server.mouseAction(action, x, y)
                    ToolResult(content = listOf(mapOf(
                        "type" to "text",
                        "text" to "Mouse $action executed at ($x, $y)."
                    )))
                } else if (name == "resize_window") {
                    val width = arguments?.get("width")?.asInt() ?: 1024
                    val height = arguments?.get("height")?.asInt() ?: 768
                    server.resizeTopLevelWindows(width, height)
                    ToolResult(content = listOf(mapOf(
                        "type" to "text",
                        "text" to "Window resized to ${width}x${height}."
                    )))
                } else if (name == "get_ui_tree") {
                    val reader = AtSpiReader()
                    val tree = reader.getUiTree()
                    ToolResult(content = listOf(mapOf(
                        "type" to "text",
                        "text" to jacksonObjectMapper().writeValueAsString(tree)
                    )))
                } else if (name == "get_clipboard") {
                    val clip = ClipboardManager()
                    val text = clip.getText() ?: ""
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to text)))
                } else if (name == "set_clipboard") {
                    val text = arguments?.get("text")?.asText() ?: ""
                    val clip = ClipboardManager()
                    clip.setText(text)
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Clipboard updated.")))
                } else if (name == "highlight_area") {
                    val x = arguments?.get("x")?.asInt() ?: 0
                    val y = arguments?.get("y")?.asInt() ?: 0
                    val width = arguments?.get("width")?.asInt() ?: 100
                    val height = arguments?.get("height")?.asInt() ?: 100
                    
                    val img = server.takeScreenshotWithHighlight(x, y, width, height)
                    val b64 = server.imageToBase64(img)
                    ToolResult(content = listOf(mapOf(
                        "type" to "image",
                        "mimeType" to "image/png",
                        "data" to b64
                    )))
                } else if (name == "detect_ui_elements") {
                    val vision = VisionFallback()
                    val img = server.takeFullScreenshot()
                    val elements = vision.detectElements(img)
                    ToolResult(content = listOf(mapOf(
                        "type" to "text",
                        "text" to jacksonObjectMapper().writeValueAsString(mapOf("detected_elements" to elements))
                    )))
                } else {
                    ToolResult(content = listOf(mapOf("type" to "text", "text" to "Unknown tool: $name")))
                }
            } catch (e: Exception) {
                ToolResult(content = listOf(mapOf("type" to "text", "text" to (e.message ?: "Error"))), isError = true)
            }
        }
        else -> emptyMap<String, Any>()
    }
}

