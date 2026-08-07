package cz.loplex.mcp.screenshotter.worker

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    val serverLogic = ScreenshotterServer()
    val mapper = jacksonObjectMapper()
    val atSpiReader = AtSpiReader()
    val clipboardManager = ClipboardManager()
    val visionFallback = VisionFallback()

    val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    httpServer.createContext("/execute") { exchange: HttpExchange ->
        if (exchange.requestMethod == "POST") {
            try {
                val reqBody = InputStreamReader(exchange.requestBody, StandardCharsets.UTF_8).readText()
                @Suppress("UNCHECKED_CAST")
                val request = mapper.readValue(reqBody, Map::class.java) as Map<String, Any>
                val action = request["action"] as? String ?: ""

                val response: MutableMap<String, Any?> = mutableMapOf("status" to "ok")

                when (action) {
                    "takeScreenshot" -> {
                        val x = (request["x"] as? Number)?.toInt()
                        val y = (request["y"] as? Number)?.toInt()
                        val width = (request["width"] as? Number)?.toInt()
                        val height = (request["height"] as? Number)?.toInt()
                        val threshold = (request["threshold"] as? Number)?.toDouble() ?: -1.0
                        val includeDeltas = request["includeDeltas"] as? Boolean ?: false

                        val cropRect = if (x != null && y != null && width != null && height != null) {
                            java.awt.Rectangle(x, y, width, height)
                        } else null

                        val fullImg = serverLogic.takeScreenshot(null)
                        val oldImg = serverLogic.getLastScreenshot()

                        val changedBoxes = if (includeDeltas) {
                            serverLogic.getChangedBoundingBoxes(fullImg, oldImg).map {
                                mapOf("x" to it.x, "y" to it.y, "width" to it.width, "height" to it.height)
                            }
                        } else emptyList<Map<String, Int>>()

                        val hasChanged = serverLogic.hasScreenChanged(fullImg, threshold)
                        serverLogic.updateLastScreenshot(fullImg)

                        if (threshold >= 0.0 && !hasChanged) {
                            response["changed"] = false
                            response["changedAreas"] = changedBoxes
                        } else {
                            val finalImg = if (cropRect != null) serverLogic.takeScreenshot(cropRect) else fullImg
                            response["changed"] = true
                            response["imageB64"] = serverLogic.imageToBase64(finalImg)
                            response["changedAreas"] = changedBoxes
                        }
                    }
                    "mouseAction" -> {
                        val mouseAction = request["mouseAction"] as? String ?: "move"
                        val mx = (request["x"] as? Number)?.toInt() ?: 0
                        val my = (request["y"] as? Number)?.toInt() ?: 0
                        serverLogic.mouseAction(mouseAction, mx, my)
                    }
                    "resizeWindow" -> {
                        val rw = (request["width"] as? Number)?.toInt() ?: 1024
                        val rh = (request["height"] as? Number)?.toInt() ?: 768
                        serverLogic.resizeTopLevelWindows(rw, rh)
                    }
                    "getUiTree" -> {
                        response["tree"] = atSpiReader.getUiTree()
                    }
                    "getClipboard" -> {
                        response["text"] = clipboardManager.getText() ?: ""
                    }
                    "setClipboard" -> {
                        clipboardManager.setText((request["text"] as? String) ?: "")
                    }
                    "highlightArea" -> {
                        val hx = (request["x"] as? Number)?.toInt() ?: 0
                        val hy = (request["y"] as? Number)?.toInt() ?: 0
                        val hw = (request["width"] as? Number)?.toInt() ?: 100
                        val hh = (request["height"] as? Number)?.toInt() ?: 100
                        val img = serverLogic.takeScreenshotWithHighlight(hx, hy, hw, hh)
                        response["imageB64"] = serverLogic.imageToBase64(img)
                    }
                    "detectUiElements" -> {
                        val img = serverLogic.takeScreenshot()
                        response["detectedElements"] = visionFallback.detectElements(img)
                    }
                    else -> {
                        response["status"] = "error"
                        response["error"] = "Unknown action: $action"
                    }
                }

                val resBody = mapper.writeValueAsBytes(response)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, resBody.size.toLong())
                exchange.responseBody.use { os -> os.write(resBody) }
            } catch (e: Throwable) {
                val errRes = mapper.writeValueAsBytes(mapOf("status" to "error", "error" to e.stackTraceToString()))
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(500, errRes.size.toLong())
                exchange.responseBody.use { os -> os.write(errRes) }
            }
        } else {
            exchange.sendResponseHeaders(405, -1) // Method Not Allowed
        }
    }

    httpServer.executor = java.util.concurrent.Executors.newCachedThreadPool()
    httpServer.start()
    System.err.println("Worker started on HTTP port ${httpServer.address.port} (DISPLAY=${System.getenv("DISPLAY")})")
}
