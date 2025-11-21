/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025 Jon Brule <brulejr@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.jrb.labs.monitor.oiltank.decoder

import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core.CV_8U
import org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR
import org.bytedeco.opencv.global.opencv_imgcodecs.imdecode
import org.bytedeco.opencv.global.opencv_imgproc.CHAIN_APPROX_SIMPLE
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.Canny
import org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur
import org.bytedeco.opencv.global.opencv_imgproc.RETR_EXTERNAL
import org.bytedeco.opencv.global.opencv_imgproc.boundingRect
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.global.opencv_imgproc.findContours
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Size
import org.slf4j.LoggerFactory

class FloatDetector {

    private val log = LoggerFactory.getLogger(FloatDetector::class.java)

    companion object {
        init {
            // Ensure OpenCV natives are loaded once
            Loader.load(Mat::class.java)
        }
    }

    /**
     * @param imageBytes JPEG bytes from the camera
     * @return float level as a relative height [0.0, 1.0], or null if detection fails
     */
    fun detect(imageBytes: ByteArray): Float? {
        if (imageBytes.isEmpty()) {
            log.warn("Empty image bytes passed to FloatDetector")
            return null
        }

        // Wrap bytes in a 1×N CV_8U Mat and copy the JPEG data into it.
        val buf = Mat(1, imageBytes.size, CV_8U)
        val dataPtr = buf.data()
        // IMPORTANT: use BytePointer.put(byte[], int, int), NOT asByteBuffer()
        dataPtr.put(imageBytes, 0, imageBytes.size)

        // Decode JPEG -> BGR Mat
        val frame = imdecode(buf, IMREAD_COLOR)
        if (frame == null || frame.empty()) {
            log.warn("Failed to decode JPEG frame")
            buf.release()
            return null
        }

        // Convert to grayscale
        val gray = Mat()
        cvtColor(frame, gray, COLOR_BGR2GRAY)

        // Blur to reduce noise
        val blurred = Mat()
        GaussianBlur(gray, blurred, Size(5, 5), 0.0)

        // Edge detection
        val edges = Mat()
        Canny(blurred, edges, 50.0, 150.0)

        // Find external contours
        val contours = MatVector()
        findContours(edges, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE)

        if (contours.size() == 0L) {
            log.warn("No contours found in snapshot")
            cleanup(frame, gray, blurred, edges, buf)
            return null
        }

        // Look for a tall, thin contour -> likely the float
        var bestRect: Rect? = null
        var bestHeight = 0

        for (i in 0 until contours.size()) {
            val contour = contours[i]
            val rect = boundingRect(contour)

            // Heuristic: height at least 2x width
            if (rect.height() > rect.width() * 2) {
                if (rect.height() > bestHeight) {
                    bestHeight = rect.height()
                    bestRect = rect
                }
            }
        }

        val floatRect = bestRect
        if (floatRect == null) {
            log.warn("No candidate float contour found")
            cleanup(frame, gray, blurred, edges, buf)
            return null
        }

        val y = floatRect.y().toFloat()
        val h = frame.rows().toFloat()

        // Convert pixel Y → tank level (0 at bottom, 1 at top)
        val level = (1f - (y / h)).coerceIn(0f, 1f)

        log.debug("Float detected at relative height {}", level)

        cleanup(frame, gray, blurred, edges, buf)
        return level
    }

    private fun cleanup(vararg mats: Mat) {
        mats.forEach { mat ->
            try {
                mat.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }
}
