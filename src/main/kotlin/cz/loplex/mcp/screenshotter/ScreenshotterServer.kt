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
class ScreenshotterServer {

    private var lastScreenshot: BufferedImage? = null
    private val robot: Robot

    init {
        try {
            // Robot uses native OS bindings (X11 XTEST extension on Linux)
            robot = Robot()
        } catch (e: AWTException) {
            throw RuntimeException("Failed to initialize AWT Robot for native interaction. Ensure DISPLAY is set.", e)
        }
    }

    /**
     * Takes a full screenshot of the default screen.
     */
    fun takeFullScreenshot(): BufferedImage {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val screenRect = Rectangle(screenSize)
        return robot.createScreenCapture(screenRect)
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

    // Note: For advanced window resizing of arbitrary X11 windows, 
    // JNA (Java Native Access) with X11 Xlib is required.
}
