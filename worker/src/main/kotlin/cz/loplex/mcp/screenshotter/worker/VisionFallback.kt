package cz.loplex.mcp.screenshotter.worker

import nu.pattern.OpenCV
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

class VisionFallback {

    // Loads the native OpenCV library bundled by OpenPnP only on first actual use, not merely on
    // VisionFallback() construction. Loading it eagerly at worker startup - regardless of whether
    // detect_ui_elements is ever called - was triggering OpenCV's native thread-pool
    // initialization on every single worker start, which in this environment attempted a bogus
    // ~896GB stack allocation (visible in dmesg as "Thread (pooled) ... not enough memory for the
    // allocation") that never succeeded but still added avoidable memory pressure to every run.
    //
    // setNumThreads(0) right after loading is the actual fix for that bug, not just a deferral of
    // it - per OpenCV's own docs, 0 (not 1!) is the special value that disables its threading
    // optimizations outright and runs everything on the calling thread. Passing 1 still asks for a
    // pool sized for one *managed* worker thread, which still goes through the same parallel-
    // framework setup (and its apparently miscomputed per-thread stack size) that requested
    // ~896GB in the first place - only 0 skips that code path entirely. Single-threaded
    // Canny/contour-finding on a screenshot-sized image is still well under a second, so this
    // costs nothing in practice.
    private val openCvLoaded by lazy {
        OpenCV.loadLocally()
        Core.setNumThreads(0)
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
        openCvLoaded // force the lazy native-library load before touching any org.opencv.* class

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
            // findContours() hands back native-backed MatOfPoint objects that Mat.release() below
            // never touches - each one needs releasing individually, or every detect_ui_elements
            // call leaks the native memory behind whichever contours it found.
            contour.release()
        }

        src.release()
        gray.release()
        edges.release()
        hierarchy.release()

        return results
    }
}
