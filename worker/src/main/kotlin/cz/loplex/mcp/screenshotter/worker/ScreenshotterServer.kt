package cz.loplex.mcp.screenshotter.worker

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

    init {
        // Xlib's default error handler calls exit() at the native level on any error (e.g.
        // BadWindow from resizing a window that already closed, or one a client made up) -
        // that bypasses the JVM entirely and kills the whole worker process for what should be
        // a single failed tool call. Installing a handler that just logs and returns replaces
        // that behavior with "the offending X11 call silently has no effect," which is safe
        // here since none of our X11 calls inspect their return value for error conditions.
        x11.XSetErrorHandler(silentX11ErrorHandler)
    }

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
     * Takes a screenshot of the default screen.
     * Optionally crops the image to the specified rectangle before returning,
     * but always stores the FULL screen in memory for accurate future delta comparisons.
     */
    fun takeScreenshot(cropRect: Rectangle? = null): BufferedImage {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val fullRect = Rectangle(screenSize)
        val fullImage = robot.createScreenCapture(fullRect)

        return if (cropRect != null) cropImage(fullImage, cropRect) else fullImage
    }

    /**
     * Crops `img` to `cropRect`, clamping it to `img`'s actual bounds first (see
     * [clampCropRect]'s doc comment).
     *
     * Split out from [takeScreenshot] so a caller that already has a captured image in hand -
     * e.g. WorkerMain's `takeScreenshot` action, which needs a cropped view of the *exact same*
     * capture its delta comparison ran against - can crop it directly instead of calling
     * [takeScreenshot] a second time. That second call used to take its own fresh screen capture,
     * a separate moment in time from the one the deltas were computed against; on an animated
     * screen the two could disagree, so the changed-areas the client received didn't reliably
     * describe the image it was looking at.
     */
    fun cropImage(img: BufferedImage, cropRect: Rectangle): BufferedImage {
        val safe = clampCropRect(cropRect, img.width, img.height)
        return img.getSubimage(safe.x, safe.y, safe.width, safe.height)
    }

    /**
     * Clamps `cropRect` so it fits entirely within a `imageWidth`x`imageHeight` image, for
     * [takeScreenshot] to then pass straight to [BufferedImage.getSubimage].
     *
     * The previous version only clamped `x`/`y` from below (`maxOf(0, ...)`), not from above
     * against the actual image dimensions - a crop starting past the right/bottom edge (e.g.
     * `x=2000` on a 1024px-wide screen) left `width`/`height` negative, and `getSubimage()` threw
     * an opaque `RasterFormatException` for it. Clamping both edges of both axes fixes the normal
     * case (a crop that only partially hangs off an edge); a crop that doesn't overlap the image
     * at all has no sensible sub-image to return, so that case throws an [IllegalArgumentException]
     * with a clear, actionable message instead of letting `getSubimage()` fail unexplained.
     */
    internal fun clampCropRect(cropRect: Rectangle, imageWidth: Int, imageHeight: Int): Rectangle {
        // Interval-clip each axis to [0, imageWidth)/[0, imageHeight) rather than clamping x/y and
        // width/height independently: shifting a negative x up to 0 without also shrinking width
        // by the same amount would grow the crop to cover columns it was never asked for.
        val safeX = maxOf(cropRect.x, 0)
        val safeY = maxOf(cropRect.y, 0)
        val safeW = minOf(cropRect.x + cropRect.width, imageWidth) - safeX
        val safeH = minOf(cropRect.y + cropRect.height, imageHeight) - safeY
        if (safeW <= 0 || safeH <= 0) {
            throw IllegalArgumentException(
                "Crop rectangle $cropRect doesn't overlap the screen at all " +
                    "(screen is ${imageWidth}x$imageHeight)"
            )
        }
        return Rectangle(safeX, safeY, safeW, safeH)
    }

    /**
     * Finds bounding boxes of areas that changed between two images.
     * Uses a fast 16x16 grid-based clustering algorithm.
     */
    fun getChangedBoundingBoxes(newImg: BufferedImage, oldImg: BufferedImage?): List<Rectangle> {
        if (oldImg == null || oldImg.width != newImg.width || oldImg.height != newImg.height) {
            return emptyList() // Can't compare or everything changed
        }

        val cellSize = 16
        val cols = (newImg.width + cellSize - 1) / cellSize
        val rows = (newImg.height + cellSize - 1) / cellSize
        val grid = Array(rows) { BooleanArray(cols) }

        // 1. Mark changed cells. Every pixel gets checked (not just every 2nd one in each
        // direction, as this used to sample) - hasScreenChanged() diffs every pixel, and sampling
        // here could disagree with it: a single-pixel change at odd x/y (a blinking cursor, a
        // thin focus ring) would report changed=true from hasScreenChanged() but changed_areas=[]
        // from here, since the sampling grid could skip the only pixel that actually changed.
        // Once a cell is marked, skip re-checking its remaining pixels - still correct (every
        // pixel was eligible to mark its cell before being skipped), just avoids redundant work.
        for (y in 0 until newImg.height) {
            val row = y / cellSize
            for (x in 0 until newImg.width) {
                val col = x / cellSize
                if (grid[row][col]) continue
                if (newImg.getRGB(x, y) != oldImg.getRGB(x, y)) {
                    grid[row][col] = true
                }
            }
        }

        // 2. Group adjacent changed cells into bounding boxes using simple DFS
        val visited = Array(rows) { BooleanArray(cols) }
        val boxes = mutableListOf<Rectangle>()

        fun dfs(r: Int, c: Int, box: IntArray) {
            if (r < 0 || c < 0 || r >= rows || c >= cols || visited[r][c] || !grid[r][c]) return
            visited[r][c] = true
            box[0] = minOf(box[0], c) // minCol
            box[1] = minOf(box[1], r) // minRow
            box[2] = maxOf(box[2], c) // maxCol
            box[3] = maxOf(box[3], r) // maxRow
            dfs(r - 1, c, box)
            dfs(r + 1, c, box)
            dfs(r, c - 1, box)
            dfs(r, c + 1, box)
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] && !visited[r][c]) {
                    val box = intArrayOf(c, r, c, r)
                    dfs(r, c, box)
                    
                    val x = box[0] * cellSize
                    val y = box[1] * cellSize
                    val w = minOf((box[2] - box[0] + 1) * cellSize, newImg.width - x)
                    val h = minOf((box[3] - box[1] + 1) * cellSize, newImg.height - y)
                    boxes.add(Rectangle(x, y, w, h))
                }
            }
        }

        return boxes
    }

    /**
     * Updates the lastScreenshot state. Call this AFTER all delta computations are done.
     */
    fun updateLastScreenshot(img: BufferedImage) {
        lastScreenshot = img
    }

    /**
     * Gets the previous screenshot
     */
    fun getLastScreenshot(): BufferedImage? {
        return lastScreenshot
    }

    /**
     * Compares a new screenshot to the last stored screenshot.
     */
    fun hasScreenChanged(newImg: BufferedImage, threshold: Double): Boolean {
        val last = lastScreenshot
        if (last == null || last.width != newImg.width || last.height != newImg.height) {
            lastScreenshot = newImg
            return true
        }

        var diffPixels = 0
        val totalPixels = newImg.width * newImg.height

        for (y in 0 until newImg.height) {
            for (x in 0 until newImg.width) {
                if (newImg.getRGB(x, y) != last.getRGB(x, y)) {
                    diffPixels++
                }
            }
        }

        val diffPercentage = (diffPixels.toDouble() / totalPixels) * 100.0

        if (diffPercentage <= threshold) {
            return false
        }

        return true
    }

    /**
     * Takes a screenshot and draws a semi-transparent highlight box over the specified coordinates.
     * Useful for visual documentation of what the agent is clicking/interacting with.
     */
    fun takeScreenshotWithHighlight(x: Int, y: Int, width: Int, height: Int): BufferedImage {
        // Deliberately NOT calling updateLastScreenshot() on the annotated result: it has the
        // highlight baked in, and storing it as the baseline would make the next get_screenshot's
        // delta comparison see a fake "change" exactly where the highlight was drawn. Leave
        // lastScreenshot as whatever the most recent real screenshot set it to - drawHighlight()
        // itself doesn't touch it either, so this holds regardless of how the image was captured.
        return drawHighlight(takeScreenshot(null), x, y, width, height)
    }

    /**
     * Draws a semi-transparent red fill plus a solid red border over `(x, y, width, height)` on
     * `image`, in place, and returns it.
     *
     * Split out from [takeScreenshotWithHighlight] - same reasoning as [cropImage] being split
     * from [takeScreenshot] - so the annotation logic can be unit-tested against a synthetic
     * image without a real `Robot`/`DISPLAY` capturing the actual host screen as a side effect.
     */
    internal fun drawHighlight(image: BufferedImage, x: Int, y: Int, width: Int, height: Int): BufferedImage {
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
        return image
    }

    /**
     * Downscales `image` so its width is at most `maxWidth`, preserving aspect ratio. Lets a
     * caller trade image detail for a cheaper vision payload - vision-model token cost scales
     * with pixel dimensions, not file size/format, so this (not e.g. lossy compression) is the
     * lever that actually reduces it.
     *
     * Intended for use only on the image actually handed back to the caller (see WorkerMain) -
     * never pass the result into hasScreenChanged()/getChangedBoundingBoxes()/
     * updateLastScreenshot(), so delta comparisons always stay pixel-exact regardless of what
     * resolution a given call asked to receive.
     */
    fun scaleToMaxWidth(image: BufferedImage, maxWidth: Int?): BufferedImage {
        if (maxWidth == null || maxWidth <= 0 || maxWidth >= image.width) return image

        val scaledHeight = (image.height.toDouble() * maxWidth / image.width).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(maxWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB)
        val g2d = scaled.createGraphics()
        try {
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            g2d.drawImage(image, 0, 0, maxWidth, scaledHeight, null)
        } finally {
            g2d.dispose()
        }
        return scaled
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
     * Uses native X11 emulation (via Robot) to move the mouse and optionally click, press,
     * release, or scroll the wheel. "press" and "release" are separate calls on purpose:
     * the X server tracks button state independently of our Robot instance, so a "press" at
     * one point followed by a plain "move" to another point (button still held) followed by
     * a "release" there produces a genuine drag - e.g. for dragging a GtkPaned splitter.
     */
    fun mouseAction(action: String, x: Int, y: Int, amount: Int = 0, button: Int = 1) {
        robot.mouseMove(x, y)
        val mask = when (button) {
            1 -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK
            3 -> java.awt.event.InputEvent.BUTTON3_DOWN_MASK
            else -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK
        }
        when (action) {
            "click" -> {
                robot.mousePress(mask)
                robot.mouseRelease(mask)
            }
            "press" -> robot.mousePress(mask)
            "release" -> robot.mouseRelease(mask)
            "scroll" -> robot.mouseWheel(amount)
            else -> { /* "move" (default): mouseMove above already covers it */ }
        }
    }

    interface X11Ext : com.sun.jna.platform.unix.X11 {
        companion object {
            val INSTANCE: X11Ext = com.sun.jna.Native.load("X11", X11Ext::class.java)
        }

        fun XResizeWindow(display: com.sun.jna.platform.unix.X11.Display?, w: com.sun.jna.platform.unix.X11.Window?, width: Int, height: Int): Int
    }

    /**
     * Resizes windows in the sandbox display using JNA and native X11 calls.
     *
     * When [windowId] is null (the default, and the prior behavior), every viewable top-level
     * window (direct child of the root window) is resized - fine for a single-app sandbox, but
     * ambiguous once more than one app is running (see FUTURE_WORK #8). Pass a specific
     * [windowId] (from [listWindows]) to scope the resize to just that one window instead.
     */
    fun resizeTopLevelWindows(width: Int, height: Int, windowId: Long? = null) {
        val display = x11.XOpenDisplay(null) ?: throw RuntimeException("Failed to open X11 display for resizing")

        try {
            if (windowId != null) {
                x11.XResizeWindow(display, com.sun.jna.platform.unix.X11.Window(windowId), width, height)
                return
            }

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

    /** One viewable top-level window, as reported by [listWindows]. */
    data class WindowInfo(
        val windowId: Long,
        val pid: Long?,
        val title: String?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Lists every viewable top-level window (direct child of the root window), together with the
     * PID of the process that owns it (from the `_NET_WM_PID` property, when the app/toolkit sets
     * one - GTK/Qt/AWT all do), its title, and its geometry. Lets a caller correlate a window with
     * the PID [SandboxManager.launchApp] returned for it (see FUTURE_WORK #8), instead of every
     * tool implicitly operating on "whatever is on screen."
     *
     * There is no window manager running inside the sandbox, so top-level windows are the root's
     * direct children and [com.sun.jna.platform.unix.X11.XWindowAttributes]' geometry is already
     * screen-relative - no reparenting/frame offset to account for.
     */
    fun listWindows(): List<WindowInfo> {
        val display = x11.XOpenDisplay(null) ?: throw RuntimeException("Failed to open X11 display for listing windows")
        try {
            val root = x11.XDefaultRootWindow(display)
            val netWmPidAtom = x11.XInternAtom(display, "_NET_WM_PID", false)

            val rootRef = com.sun.jna.platform.unix.X11.WindowByReference()
            val parentRef = com.sun.jna.platform.unix.X11.WindowByReference()
            val childrenRef = com.sun.jna.ptr.PointerByReference()
            val childrenCountRef = com.sun.jna.ptr.IntByReference()

            val status = x11.XQueryTree(display, root, rootRef, parentRef, childrenRef, childrenCountRef)
            if (status == 0 || childrenCountRef.value <= 0) return emptyList()

            val childrenPtr = childrenRef.value
            val childCount = childrenCountRef.value
            val windows = mutableListOf<WindowInfo>()

            for (i in 0 until childCount) {
                val winId = if (com.sun.jna.Native.LONG_SIZE == 8) {
                    childrenPtr.getLong(i * 8L)
                } else {
                    childrenPtr.getInt(i * 4L).toLong()
                }
                val win = com.sun.jna.platform.unix.X11.Window(winId)

                val attributes = com.sun.jna.platform.unix.X11.XWindowAttributes()
                x11.XGetWindowAttributes(display, win, attributes)
                if (attributes.map_state != com.sun.jna.platform.unix.X11.IsViewable) continue

                windows.add(
                    WindowInfo(
                        windowId = winId,
                        pid = readWmPid(display, win, netWmPidAtom),
                        title = readWindowTitle(display, win),
                        x = attributes.x,
                        y = attributes.y,
                        width = attributes.width,
                        height = attributes.height
                    )
                )
            }
            x11.XFree(childrenPtr)
            return windows
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    /** Reads the single-CARDINAL `_NET_WM_PID` property off `win`; null if it was never set. */
    private fun readWmPid(
        display: com.sun.jna.platform.unix.X11.Display,
        win: com.sun.jna.platform.unix.X11.Window,
        atom: com.sun.jna.platform.unix.X11.Atom
    ): Long? {
        val actualTypeReturn = com.sun.jna.platform.unix.X11.AtomByReference()
        val actualFormatReturn = com.sun.jna.ptr.IntByReference()
        val nitemsReturn = com.sun.jna.ptr.NativeLongByReference()
        val bytesAfterReturn = com.sun.jna.ptr.NativeLongByReference()
        val propReturn = com.sun.jna.ptr.PointerByReference()
        val result = x11.XGetWindowProperty(
            display, win, atom,
            com.sun.jna.NativeLong(0), com.sun.jna.NativeLong(1), false,
            com.sun.jna.platform.unix.X11.XA_CARDINAL,
            actualTypeReturn, actualFormatReturn, nitemsReturn, bytesAfterReturn, propReturn
        )
        val prop = propReturn.value
        if (result != 0 || prop == null) return null
        // Xlib allocates prop_return whenever the call succeeds, even if the property doesn't
        // exist (nitems == 0) - it must be XFree()'d in that case too, or every window without a
        // _NET_WM_PID leaks one native allocation per list_windows() call.
        try {
            if (nitemsReturn.value.toLong() == 0L) return null
            return prop.getInt(0).toLong()
        } finally {
            x11.XFree(prop)
        }
    }

    /** Reads `win`'s title via `XFetchName` (`WM_NAME`); null if it was never set. */
    private fun readWindowTitle(
        display: com.sun.jna.platform.unix.X11.Display,
        win: com.sun.jna.platform.unix.X11.Window
    ): String? {
        val nameRef = com.sun.jna.ptr.PointerByReference()
        val status = x11.XFetchName(display, win, nameRef)
        val namePtr = nameRef.value
        if (status == 0 || namePtr == null) return null
        val name = namePtr.getString(0)
        x11.XFree(namePtr)
        return name
    }

    companion object {
        // Kept as a top-level val (strong reference) rather than a local/lambda passed straight
        // into XSetErrorHandler: JNA only holds a weak reference to Callback instances on the
        // native side, so a handler with no other Java-side reference is eligible for GC and can
        // silently stop being called once collected.
        internal val silentX11ErrorHandler = com.sun.jna.platform.unix.X11.XErrorHandler { _, errorEvent ->
            System.err.println(
                "Ignored X11 error (error_code=${errorEvent.error_code}, " +
                    "request_code=${errorEvent.request_code}, minor_code=${errorEvent.minor_code}) " +
                    "to avoid crashing the worker process"
            )
            0
        }
    }
}
