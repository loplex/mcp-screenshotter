package cz.loplex.mcp.screenshotter.worker

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun createTestImage(width: Int, height: Int, color: Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }
}
