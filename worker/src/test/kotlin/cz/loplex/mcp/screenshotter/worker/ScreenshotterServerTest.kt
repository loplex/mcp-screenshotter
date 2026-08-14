package cz.loplex.mcp.screenshotter.worker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotterServerTest {

    @Test
    fun `test hasScreenChanged with threshold`() {
        val server = ScreenshotterServer()
        val img1 = createTestImage(100, 100, Color.WHITE)
        val img2 = createTestImage(100, 100, Color.WHITE)
        
        server.updateLastScreenshot(img1)
        
        // 0% change
        assertEquals(false, server.hasScreenChanged(img2, 0.0))
        
        // Change exactly 1 pixel out of 10,000 (0.01%)
        img2.setRGB(50, 50, Color.BLACK.rgb)
        assertEquals(false, server.hasScreenChanged(img2, 0.05), "Should not trigger if under threshold")
        assertEquals(true, server.hasScreenChanged(img2, 0.005), "Should trigger if over threshold")
    }

    @Test
    fun `test getChangedBoundingBoxes`() {
        val server = ScreenshotterServer()
        val img1 = createTestImage(100, 100, Color.WHITE)
        val img2 = createTestImage(100, 100, Color.WHITE)
        
        // Draw a small 10x10 square at (20, 20)
        val g1 = img2.createGraphics()
        g1.color = Color.BLACK
        g1.fillRect(20, 20, 10, 10)
        
        // Draw another 5x5 square at (80, 80)
        g1.color = Color.RED
        g1.fillRect(80, 80, 5, 5)
        g1.dispose()

        val boxes = server.getChangedBoundingBoxes(img2, img1)
        
        assertEquals(2, boxes.size, "Should detect exactly 2 distinct changed regions")
        
        // Due to the 16x16 grid, the boxes will be snapped to the grid boundaries
        // Square at 20,20 (size 10) spans from x=20 to 29. 
        // Grid cell 1 (x=16..31), Grid cell 2 (x=16..31) -> width 16
        val box1 = boxes.find { it.x == 16 && it.y == 16 }
        assertNotNull(box1, "Box 1 should snap to grid at x=16, y=16")
        assertEquals(16, box1.width)
        assertEquals(16, box1.height)

        // Square at 80,80 (size 5) spans from x=80 to 84.
        // Grid cell (x=80..95, y=80..95)
        val box2 = boxes.find { it.x == 80 && it.y == 80 }
        assertNotNull(box2, "Box 2 should snap to grid at x=80, y=80")
        assertEquals(16, box2.width)
        assertEquals(16, box2.height)
    }

    @Test
    fun `test scaleToMaxWidth downscales preserving aspect ratio`() {
        val server = ScreenshotterServer()
        val img = createTestImage(200, 100, Color.WHITE)

        val scaled = server.scaleToMaxWidth(img, 100)

        assertEquals(100, scaled.width)
        assertEquals(50, scaled.height, "Height should scale down proportionally with width")
    }

    @Test
    fun `test scaleToMaxWidth leaves image untouched when not needed`() {
        val server = ScreenshotterServer()
        val img = createTestImage(200, 100, Color.WHITE)

        assertEquals(img, server.scaleToMaxWidth(img, null), "null maxWidth means full resolution")
        assertEquals(img, server.scaleToMaxWidth(img, 0), "non-positive maxWidth is ignored")
        assertEquals(img, server.scaleToMaxWidth(img, 200), "maxWidth >= actual width is a no-op")
        assertEquals(img, server.scaleToMaxWidth(img, 500), "maxWidth larger than the image never upscales")
    }

    @Test
    fun `test getChangedBoundingBoxes detects a single-pixel change at odd coordinates`() {
        val server = ScreenshotterServer()
        val img1 = createTestImage(100, 100, Color.WHITE)
        val img2 = createTestImage(100, 100, Color.WHITE)

        // A step-2 sampling grid (checking only even x/y) would skip this pixel entirely, since
        // both coordinates are odd - exactly the gap that let hasScreenChanged() report
        // changed=true while this reported changed_areas=[].
        img2.setRGB(51, 51, Color.BLACK.rgb)

        val boxes = server.getChangedBoundingBoxes(img2, img1)
        assertEquals(1, boxes.size, "The single changed pixel should still produce one bounding box")
    }

    @Test
    fun `test drawHighlight draws the annotation without contaminating the delta baseline`() {
        val server = ScreenshotterServer()
        val baseline = createTestImage(100, 100, Color.WHITE)
        server.updateLastScreenshot(baseline)

        // A separate captured image (not the baseline itself) gets annotated, the same way
        // takeScreenshotWithHighlight() feeds it a fresh takeScreenshot(null) result.
        val captured = createTestImage(100, 100, Color.WHITE)
        val annotated = server.drawHighlight(captured, 10, 10, 20, 20)

        // The highlight was actually drawn - the border is a fully opaque red stroke (no alpha
        // blending, unlike the semi-transparent fill), so it should come out as exactly Color.RED.
        assertEquals(Color.RED.rgb, annotated.getRGB(10, 15), "the box's left border should be solid red")
        // The fill (25% opacity red over white) blends rather than overwriting outright - just
        // confirm it moved away from the untouched white background.
        assertTrue(annotated.getRGB(15, 15) != Color.WHITE.rgb, "the box's interior should show the semi-transparent fill")

        // The regression this guards against (code review #3): drawing a highlight used to also
        // overwrite lastScreenshot with the annotated image, so the next delta comparison saw a
        // fake "change" exactly where the highlight was. The stored baseline must still be the
        // untouched image set via updateLastScreenshot() above, not `annotated`.
        assertEquals(baseline, server.getLastScreenshot())
        assertEquals(Color.WHITE.rgb, server.getLastScreenshot()!!.getRGB(15, 15))
    }

    @Test
    fun `test clampCropRect leaves an in-bounds crop untouched`() {
        val server = ScreenshotterServer()
        val clamped = server.clampCropRect(Rectangle(10, 20, 30, 40), 200, 100)
        assertEquals(Rectangle(10, 20, 30, 40), clamped)
    }

    @Test
    fun `test clampCropRect clamps a crop that hangs off the right and bottom edges`() {
        val server = ScreenshotterServer()
        // Screen is 100x100; crop starts at (90, 90) and asks for 50x50, which would put its far
        // edge well past the screen on both axes.
        val clamped = server.clampCropRect(Rectangle(90, 90, 50, 50), 100, 100)
        assertEquals(Rectangle(90, 90, 10, 10), clamped)
    }

    @Test
    fun `test clampCropRect clamps negative x,y up to the screen origin`() {
        val server = ScreenshotterServer()
        val clamped = server.clampCropRect(Rectangle(-20, -30, 50, 60), 200, 100)
        assertEquals(Rectangle(0, 0, 30, 30), clamped)
    }

    @Test
    fun `test clampCropRect throws a clear error for a crop entirely off-screen`() {
        val server = ScreenshotterServer()
        // x=2000 on a 1024px-wide screen used to make getSubimage() throw an opaque
        // RasterFormatException (width went negative) - see clampCropRect()'s doc comment.
        val ex = assertThrows<IllegalArgumentException> {
            server.clampCropRect(Rectangle(2000, 0, 100, 100), 1024, 768)
        }
        assertEquals(true, ex.message?.contains("doesn't overlap the screen"))
    }

    private fun createTestImage(width: Int, height: Int, color: Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }
}
