package cz.loplex.mcp.screenshotter.worker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.assertTrue

/**
 * Basic coverage for [VisionFallback]. The main things worth pinning down here aren't the exact
 * detected boxes (Canny edge detection + contour bounding boxes is inherently a bit fuzzy) but:
 *  - `OpenCV.loadLocally()` actually succeeds in this environment without crashing the JVM (see
 *    `openCvLoaded`'s doc comment on the ~896GB native stack allocation bug this guards against),
 *  - `detectElements()` finds *something* on an image with clearly distinguishable rectangles,
 *    and finds nothing on a featureless one.
 *
 * Doesn't attempt to verify the native `MatOfPoint.release()` fix directly (no native memory
 * profiler in a plain JUnit run) - see `2026-08-14-code-review.md` #9 for why that fix is
 * accepted on inspection instead.
 */
class VisionFallbackTest {

    @Test
    fun `detectElements finds distinct rectangles on a synthetic image without crashing the JVM`() {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK
        g.fillRect(20, 20, 100, 60)
        g.fillRect(200, 150, 80, 50)
        g.dispose()

        val elements = assertDoesNotThrow { VisionFallback().detectElements(image) }

        assertTrue(elements.isNotEmpty(), "expected at least one detected element on an image with two clear rectangles")
        for (element in elements) {
            assertTrue(element["width"]!! > 15, "detected elements below the size filter shouldn't be returned")
            assertTrue(element["height"]!! > 10, "detected elements below the size filter shouldn't be returned")
        }
    }

    @Test
    fun `detectElements finds nothing on a featureless blank image`() {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.dispose()

        val elements = VisionFallback().detectElements(image)

        assertTrue(elements.isEmpty(), "a blank image has no edges to find contours from")
    }
}
