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

