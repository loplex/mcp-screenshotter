package cz.loplex.mcp.screenshotter

import nu.pattern.OpenCV
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

class VisionFallback {

    init {
        // Loads the native OpenCV library bundled by OpenPnP
        OpenCV.loadLocally()
    }

    private fun bufferedImageToMat(bi: BufferedImage): Mat {
        val mat = Mat(bi.height, bi.width, CvType.CV_8UC3)
        val data = (bi.raster.dataBuffer as DataBufferByte).data
        mat.put(0, 0, data)
        return mat
    }

    /**
     * Runs Canny Edge Detection and finds rectangular contours.
     * Returns a list of bounding boxes (x, y, width, height) of detected UI elements.
     */
    fun detectElements(image: BufferedImage): List<Map<String, Int>> {
        // Convert the image to 3-byte BGR format which OpenCV expects natively
        val bgrImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
        val g = bgrImage.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()

        val src = bufferedImageToMat(bgrImage)
        val gray = Mat()
        val edges = Mat()

        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        // Must use RETR_LIST or RETR_TREE to find elements inside the main window!
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val results = mutableListOf<Map<String, Int>>()

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            // Filter out noise (tiny dots or huge background panels)
            if (rect.width > 15 && rect.height > 10 && rect.width < image.width * 0.9) {
                results.add(mapOf(
                    "x" to rect.x,
                    "y" to rect.y,
                    "width" to rect.width,
                    "height" to rect.height
                ))
            }
        }

        src.release()
        gray.release()
        edges.release()
        hierarchy.release()

        return results
    }
}
