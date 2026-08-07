package cz.loplex.mcp.screenshotter.worker

import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.*

class ScreenshotterServerNativeTest {

    @Test
    fun `test resizeTopLevelWindows with mock X11`() {
        var resizeCalled = false
        var freeCalled = false
        var closeCalled = false

        val displayPtr = Memory(8)
        val rootWin = com.sun.jna.platform.unix.X11.Window(1L)
        val mockDisplay = com.sun.jna.platform.unix.X11.Display().apply { pointer = displayPtr }

        val x11Mock = java.lang.reflect.Proxy.newProxyInstance(
            ScreenshotterServer.X11Ext::class.java.classLoader,
            arrayOf(ScreenshotterServer.X11Ext::class.java)
        ) { _, method, args ->
            when (method.name) {
                "XOpenDisplay" -> mockDisplay
                "XDefaultRootWindow" -> rootWin
                "XQueryTree" -> {
                    val children_return = args[4] as? com.sun.jna.ptr.PointerByReference
                    val nchildren_return = args[5] as? com.sun.jna.ptr.IntByReference
                    val childPtr = Memory(8).apply { setLong(0, 12345L) }
                    children_return?.value = childPtr
                    nchildren_return?.value = 1
                    1
                }
                "XGetWindowAttributes" -> {
                    val attrs = args[2] as? com.sun.jna.platform.unix.X11.XWindowAttributes
                    attrs?.map_state = com.sun.jna.platform.unix.X11.IsViewable
                    1
                }
                "XResizeWindow" -> {
                    val w = args[1] as? com.sun.jna.platform.unix.X11.Window
                    val width = args[2] as Int
                    val height = args[3] as Int
                    assertEquals(12345L, w?.toLong())
                    assertEquals(800, width)
                    assertEquals(600, height)
                    resizeCalled = true
                    1
                }
                "XFree" -> {
                    freeCalled = true
                    1
                }
                "XCloseDisplay" -> {
                    closeCalled = true
                    1
                }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "MockX11"
                else -> 0
            }
        } as ScreenshotterServer.X11Ext

        val server = ScreenshotterServer(x11Mock)
        
        assertDoesNotThrow {
            server.resizeTopLevelWindows(800, 600)
        }

        assertTrue(resizeCalled, "XResizeWindow should be called")
        assertTrue(freeCalled, "XFree should be called")
        assertTrue(closeCalled, "XCloseDisplay should be called")
    }
}
