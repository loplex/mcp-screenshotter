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
                "XSetErrorHandler" -> null
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

    @Test
    fun `test resizeTopLevelWindows with windowId targets only that window`() {
        var resizeCalled = false
        var queryTreeCalled = false

        val displayPtr = Memory(8)
        val mockDisplay = com.sun.jna.platform.unix.X11.Display().apply { pointer = displayPtr }

        val x11Mock = java.lang.reflect.Proxy.newProxyInstance(
            ScreenshotterServer.X11Ext::class.java.classLoader,
            arrayOf(ScreenshotterServer.X11Ext::class.java)
        ) { _, method, args ->
            when (method.name) {
                "XOpenDisplay" -> mockDisplay
                "XQueryTree" -> {
                    queryTreeCalled = true
                    0
                }
                "XResizeWindow" -> {
                    val w = args[1] as? com.sun.jna.platform.unix.X11.Window
                    assertEquals(999L, w?.toLong())
                    assertEquals(640, args[2])
                    assertEquals(480, args[3])
                    resizeCalled = true
                    1
                }
                "XCloseDisplay" -> 1
                "XSetErrorHandler" -> null
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "MockX11"
                else -> 0
            }
        } as ScreenshotterServer.X11Ext

        val server = ScreenshotterServer(x11Mock)

        assertDoesNotThrow {
            server.resizeTopLevelWindows(640, 480, windowId = 999L)
        }

        assertTrue(resizeCalled, "XResizeWindow should be called for the targeted window")
        assertFalse(queryTreeCalled, "XQueryTree should not be called when a specific windowId is given")
    }

    @Test
    fun `test listWindows with mock X11`() {
        val displayPtr = Memory(8)
        val rootWin = com.sun.jna.platform.unix.X11.Window(1L)
        val mockDisplay = com.sun.jna.platform.unix.X11.Display().apply { pointer = displayPtr }
        val pidPropMem = Memory(4).apply { setInt(0, 4242) }
        val titlePropMem = Memory(7).apply { setString(0, "Sample") }

        val x11Mock = java.lang.reflect.Proxy.newProxyInstance(
            ScreenshotterServer.X11Ext::class.java.classLoader,
            arrayOf(ScreenshotterServer.X11Ext::class.java)
        ) { _, method, args ->
            when (method.name) {
                "XOpenDisplay" -> mockDisplay
                "XDefaultRootWindow" -> rootWin
                "XInternAtom" -> com.sun.jna.platform.unix.X11.Atom(42L)
                "XQueryTree" -> {
                    val childrenReturn = args[4] as com.sun.jna.ptr.PointerByReference
                    val nchildrenReturn = args[5] as com.sun.jna.ptr.IntByReference
                    val childPtr = Memory(8).apply { setLong(0, 12345L) }
                    childrenReturn.value = childPtr
                    nchildrenReturn.value = 1
                    1
                }
                "XGetWindowAttributes" -> {
                    val attrs = args[2] as com.sun.jna.platform.unix.X11.XWindowAttributes
                    attrs.map_state = com.sun.jna.platform.unix.X11.IsViewable
                    attrs.x = 10
                    attrs.y = 20
                    attrs.width = 300
                    attrs.height = 400
                    1
                }
                "XGetWindowProperty" -> {
                    val nitemsReturn = args[9] as com.sun.jna.ptr.NativeLongByReference
                    val propReturn = args[11] as com.sun.jna.ptr.PointerByReference
                    nitemsReturn.value = com.sun.jna.NativeLong(1)
                    propReturn.value = pidPropMem
                    0
                }
                "XFetchName" -> {
                    val nameReturn = args[2] as com.sun.jna.ptr.PointerByReference
                    nameReturn.value = titlePropMem
                    1
                }
                "XFree" -> 1
                "XCloseDisplay" -> 1
                "XSetErrorHandler" -> null
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "MockX11"
                else -> 0
            }
        } as ScreenshotterServer.X11Ext

        val server = ScreenshotterServer(x11Mock)

        val windows = server.listWindows()

        assertEquals(1, windows.size)
        val window = windows[0]
        assertEquals(12345L, window.windowId)
        assertEquals(4242L, window.pid)
        assertEquals("Sample", window.title)
        assertEquals(10, window.x)
        assertEquals(20, window.y)
        assertEquals(300, window.width)
        assertEquals(400, window.height)
    }

    @Test
    fun `test silentX11ErrorHandler swallows an error instead of letting Xlib's default handler exit()`() {
        // The real Xlib error handler can't be exercised through the mock (a mocked XResizeWindow
        // never actually triggers a native error callback) - this instead calls
        // ScreenshotterServer's installed handler directly, the same way Xlib itself would, to
        // confirm it just logs and returns 0 rather than throwing or exiting.
        val errorEvent = com.sun.jna.platform.unix.X11.XErrorEvent()
        errorEvent.error_code = 3 // BadWindow
        errorEvent.request_code = 18 // X_ChangeProperty, arbitrary for this test
        errorEvent.minor_code = 0

        val result = assertDoesNotThrow {
            ScreenshotterServer.silentX11ErrorHandler.apply(null, errorEvent)
        }

        assertEquals(0, result, "handler should return 0 (success) so Xlib doesn't treat this as unhandled")
    }
}
