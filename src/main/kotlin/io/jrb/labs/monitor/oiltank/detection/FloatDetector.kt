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

package io.jrb.labs.monitor.oiltank.detection

import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Size
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min

object FloatDetector {

    private const val ENABLE_CALIBRATION = false
    private const val CAL_SLOPE = 1.2857143
    private const val CAL_INTERCEPT = -0.2857143

    /**
     * Detect relative float position (0.0 = bottom, 1.0 = top)
     */
    fun detect(imageBytes: ByteArray): Double {

        val tmp = Files.createTempFile("float-detector-", ".jpg")
        try {
            Files.write(tmp, imageBytes)
            val bgr = opencv_imgcodecs.imread(tmp.toString(), opencv_imgcodecs.IMREAD_COLOR)
            if (bgr == null || bgr.empty()) return 0.0

            // --- Convert to grayscale and equalize ---
            val gray = Mat()
            opencv_imgproc.cvtColor(bgr, gray, opencv_imgproc.COLOR_BGR2GRAY)

            val grayEq = Mat()
            opencv_imgproc.equalizeHist(gray, grayEq)

            val w = grayEq.cols()
            val h = grayEq.rows()
            if (w <= 0 || h <= 0) return 0.0

            // --------------------------------------------------------
            // ROI tuned based on actual gauge location (verified image)
            // Gauge vertical center ≈ 55% down from frame
            // --------------------------------------------------------
            val roiWidth = 300
            val roiHeight = 500
            val roiCenterY = (h * 0.55).toInt()

            val roiX = max(0, w / 2 - roiWidth / 2)
            val roiY = max(0, roiCenterY - roiHeight / 2)
            val roiW = min(roiWidth, w - roiX)
            val roiH = min(roiHeight, h - roiY)

            val gauge = Mat(grayEq, Rect(roiX, roiY, roiW, roiH))

            // --- Blur + threshold ---
            val blurred = Mat()
            opencv_imgproc.GaussianBlur(gauge, blurred, Size(5, 5), 0.0)

            val thresh = Mat()
            opencv_imgproc.threshold(
                blurred,
                thresh,
                0.0,
                255.0,
                opencv_imgproc.THRESH_BINARY or opencv_imgproc.THRESH_OTSU
            )

            // if mostly white, invert
            val meanVal = opencv_core.mean(thresh).get(0)
            if (meanVal > 127.0) {
                opencv_core.bitwise_not(thresh, thresh)
            }

            // --- Find contours ---
            val contours = MatVector()
            val hierarchy = Mat()

            opencv_imgproc.findContours(
                thresh,
                contours,
                hierarchy,
                opencv_imgproc.RETR_EXTERNAL,
                opencv_imgproc.CHAIN_APPROX_SIMPLE
            )

            if (contours.size() == 0L) return 0.0

            // --- Largest contour = float body ---
            var maxArea = 0.0
            var bestRect: Rect? = null

            for (i in 0 until contours.size()) {
                val c = contours.get(i.toLong())
                val area = opencv_imgproc.contourArea(c)
                if (area > maxArea) {
                    maxArea = area
                    bestRect = opencv_imgproc.boundingRect(c)
                }
            }

            val rect = bestRect ?: return 0.0

            // --- Compute float vertical position ---
            val floatCenterY = rect.y() + rect.height() / 2.0
            val raw = 1.0 - (floatCenterY / gauge.rows().toDouble())
            val clipped = raw.coerceIn(0.0, 1.0)

            return if (ENABLE_CALIBRATION) {
                (CAL_SLOPE * clipped + CAL_INTERCEPT).coerceIn(0.0, 1.0)
            } else clipped

        } finally {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
        }
    }
}