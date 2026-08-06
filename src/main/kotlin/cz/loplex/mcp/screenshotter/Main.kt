package cz.loplex.mcp.screenshotter

/**
 * Entry point for the MCP Server.
 * Here we will implement the JSON-RPC stdio protocol.
 */
fun main() {
    val server = ScreenshotterServer()
    
    // In a full implementation, you would read from System.`in` in a loop,
    // parse the JSON-RPC messages using Jackson, and call the tools defined
    // in ScreenshotterServer (e.g. server.takeFullScreenshot()).
    //
    // Then you serialize the response (e.g. Base64 images) and write 
    // back to System.out.
    
    println("MCP Screenshotter Server in Kotlin started (Standard IO mode).")
}
