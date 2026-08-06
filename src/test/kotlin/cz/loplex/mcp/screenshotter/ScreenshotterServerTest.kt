package cz.loplex.mcp.screenshotter

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScreenshotterServerTest {

    @Test
    fun `test getImageDelta with no changes returns null`() {
        val server = ScreenshotterServer()
        val img1 = createTestImage(100, 100, Color.WHITE)
        val img2 = createTestImage(100, 100, Color.WHITE)

        val (cropped, bbox) = server.getImageDelta(img1, img2)

        assertNull(cropped, "Cropped image should be null when there are no changes")
        assertNull(bbox, "Bounding box should be null when there are no changes")
    }

    @Test
    fun `test getImageDelta with changes returns correct bounding box`() {
        val server = ScreenshotterServer()
        val img1 = createTestImage(100, 100, Color.WHITE)
        val img2 = createTestImage(100, 100, Color.WHITE)
        
        // Draw a black 10x10 square at (50, 50)
        val graphics = img2.createGraphics()
        graphics.color = Color.BLACK
        graphics.fillRect(50, 50, 10, 10)
        graphics.dispose()

        val (cropped, bbox) = server.getImageDelta(img1, img2)

        assertNotNull(cropped, "Cropped image should not be null")
        assertNotNull(bbox, "Bounding box should not be null")
        
        assertEquals(Rectangle(50, 50, 10, 10), bbox)
        assertEquals(10, cropped.width)
        assertEquals(10, cropped.height)
    }
    
    @Test
    fun `test getImageDelta with different dimensions returns new image`() {
        val server = ScreenshotterServer()
        val img1 = createTestImage(100, 100, Color.WHITE)
        val img2 = createTestImage(200, 200, Color.BLACK)

        val (cropped, bbox) = server.getImageDelta(img1, img2)

        assertNotNull(cropped)
        assertNotNull(bbox)
        
        assertEquals(Rectangle(0, 0, 200, 200), bbox)
        assertEquals(200, cropped.width)
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
