package cz.loplex.mcp.screenshotter

import java.awt.AWTException
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Core logic for taking screenshots, computing deltas, and sending native events
 * via java.awt.Robot (which uses native X11 libraries on Linux).
 */
class ScreenshotterServer(
    private val x11: X11Ext = X11Ext.INSTANCE
) {

    private var lastScreenshot: BufferedImage? = null
    
    // Lazy initialization so tests without X11 DISPLAY won't crash during class instantiation
    private val robot: Robot by lazy {
        try {
            Robot()
        } catch (e: AWTException) {
            throw RuntimeException("Failed to initialize AWT Robot for native interaction. Ensure DISPLAY is set.", e)
        }
    }

    /**
     * Takes a full screenshot of the default screen.
     */
    fun takeFullScreenshot(): BufferedImage {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val rect = Rectangle(screenSize)
        val image = robot.createScreenCapture(rect)
        lastScreenshot = image
        return image
    }

    /**
     * Takes a screenshot and draws a semi-transparent highlight box over the specified coordinates.
     * Useful for visual documentation of what the agent is clicking/interacting with.
     */
    fun takeScreenshotWithHighlight(x: Int, y: Int, width: Int, height: Int): BufferedImage {
        val image = takeFullScreenshot()
        
        // Use Graphics2D to draw the highlight
        val g2d = image.createGraphics()
        try {
            // Draw a semi-transparent red fill
            g2d.color = java.awt.Color(255, 0, 0, 64) // 25% opacity red
            g2d.fillRect(x, y, width, height)
            
            // Draw a solid red border
            g2d.color = java.awt.Color.RED
            g2d.stroke = java.awt.BasicStroke(3f) // 3px border
            g2d.drawRect(x, y, width, height)
        } finally {
            g2d.dispose()
        }
        
        lastScreenshot = image
        return image
    }

    /**
     * Converts a BufferedImage to a base64 encoded PNG string.
     */
    fun imageToBase64(image: BufferedImage): String {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    /**
     * Compares the previous image with a new one and returns the cropped changed area 
     * along with its bounding box coordinates (x, y, width, height).
     */
    fun getImageDelta(oldImg: BufferedImage, newImg: BufferedImage): Pair<BufferedImage?, Rectangle?> {
        if (oldImg.width != newImg.width || oldImg.height != newImg.height) {
            return Pair(newImg, Rectangle(0, 0, newImg.width, newImg.height))
        }

        var minX = newImg.width
        var minY = newImg.height
        var maxX = 0
        var maxY = 0

        // Pixel-by-pixel comparison
        for (y in 0 until newImg.height) {
            for (x in 0 until newImg.width) {
                if (oldImg.getRGB(x, y) != newImg.getRGB(x, y)) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (minX > maxX || minY > maxY) {
            // No changes found
            return Pair(null, null)
        }

        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val boundingBox = Rectangle(minX, minY, width, height)
        val cropped = newImg.getSubimage(minX, minY, width, height)

        return Pair(cropped, boundingBox)
    }

    /**
     * Uses native X11 emulation (via Robot) to move the mouse and optionally click.
     */
    fun mouseAction(action: String, x: Int, y: Int, button: Int = 1) {
        robot.mouseMove(x, y)
        if (action == "click") {
            val mask = when (button) {
                1 -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK
                3 -> java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                else -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK
            }
            robot.mousePress(mask)
            robot.mouseRelease(mask)
        }
    }

    interface X11Ext : com.sun.jna.platform.unix.X11 {
        companion object {
            val INSTANCE: X11Ext = com.sun.jna.Native.load("X11", X11Ext::class.java)
        }

        fun XResizeWindow(display: com.sun.jna.platform.unix.X11.Display?, w: com.sun.jna.platform.unix.X11.Window?, width: Int, height: Int): Int
    }

    /**
     * Resizes all viewable top-level windows (direct children of the root window)
     * using JNA and native X11 calls.
     */
    fun resizeTopLevelWindows(width: Int, height: Int) {
        val display = x11.XOpenDisplay(null) ?: throw RuntimeException("Failed to open X11 display for resizing")
        
        try {
            val root = x11.XDefaultRootWindow(display)
            
            val rootRef = com.sun.jna.platform.unix.X11.WindowByReference()
            val parentRef = com.sun.jna.platform.unix.X11.WindowByReference()
            val childrenRef = com.sun.jna.ptr.PointerByReference()
            val childrenCountRef = com.sun.jna.ptr.IntByReference()
            
            val status = x11.XQueryTree(display, root, rootRef, parentRef, childrenRef, childrenCountRef)
            if (status != 0 && childrenCountRef.value > 0) {
                val childrenPtr = childrenRef.value
                val childCount = childrenCountRef.value
                
                for (i in 0 until childCount) {
                    val winId = if (com.sun.jna.Native.LONG_SIZE == 8) {
                        childrenPtr.getLong(i * 8L)
                    } else {
                        childrenPtr.getInt(i * 4L).toLong()
                    }
                    val win = com.sun.jna.platform.unix.X11.Window(winId)
                    
                    val attributes = com.sun.jna.platform.unix.X11.XWindowAttributes()
                    x11.XGetWindowAttributes(display, win, attributes)
                    
                    // IsViewable = 2
                    if (attributes.map_state == com.sun.jna.platform.unix.X11.IsViewable) {
                        x11.XResizeWindow(display, win, width, height)
                    }
                }
                x11.XFree(childrenPtr)
            }
        } finally {
            x11.XCloseDisplay(display)
        }
    }
}
