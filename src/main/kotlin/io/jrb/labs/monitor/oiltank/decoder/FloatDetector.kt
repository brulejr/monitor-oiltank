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

import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.global.opencv_imgcodecs.*
import org.bytedeco.opencv.opencv_core.*

import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min

object FloatDetector {

    /**
     * Detect relative float position (0.0 = bottom, 1.0 = top)
     */
    fun detect(imageBytes: ByteArray): Double {
        // --------------------------------------------------------------------
        // 1. Decode JPEG from bytes using a temp file (avoids BytePointer mess)
        // --------------------------------------------------------------------
        val tmpFile = Files.createTempFile("float-detector-", ".jpg")
        try {
            Files.write(tmpFile, imageBytes)

            val bgr = imread(tmpFile.toString(), IMREAD_COLOR)
            if (bgr == null || bgr.empty()) {
                return 0.0
            }

            // ----------------------------------------------------------------
            // 2. Grayscale + histogram equalization for contrast
            // ----------------------------------------------------------------
            val gray = Mat()
            cvtColor(bgr, gray, COLOR_BGR2GRAY)

            val grayEq = Mat()
            equalizeHist(gray, grayEq)

            val w = grayEq.cols()
            val h = grayEq.rows()
            if (w <= 0 || h <= 0) {
                return 0.0
            }

            // ----------------------------------------------------------------
            // 3. Define ROI around the gauge (center-ish, tuned later)
            // ----------------------------------------------------------------
            val roiWidth = 100
            val roiHeight = 300

            val roiX = max(0, w / 2 - roiWidth / 2)
            val roiY = max(0, h / 2 - roiHeight / 2)
            val roiW = min(roiWidth, w - roiX)
            val roiH = min(roiHeight, h - roiY)

            if (roiW <= 0 || roiH <= 0) {
                return 0.0
            }

            val roi = Rect(roiX, roiY, roiW, roiH)
            val gauge = Mat(grayEq, roi)

            // ----------------------------------------------------------------
            // 4. Blur + Otsu threshold
            // ----------------------------------------------------------------
            val blurred = Mat()
            GaussianBlur(gauge, blurred, Size(5, 5), 0.0)

            val thresh = Mat()
            threshold(
                blurred,
                thresh,
                0.0,
                255.0,
                THRESH_BINARY or THRESH_OTSU
            )

            // If mostly white, invert so float becomes bright
            val meanScalar = mean(thresh)
            val brightness = meanScalar.get(0)
            if (brightness > 127.0) {
                bitwise_not(thresh, thresh)
            }

            // ----------------------------------------------------------------
            // 5. Find contours in the ROI
            // ----------------------------------------------------------------
            val contours = MatVector()
            val hierarchy = Mat()
            findContours(
                thresh,
                contours,
                hierarchy,
                RETR_EXTERNAL,
                CHAIN_APPROX_SIMPLE
            )

            if (contours.size() == 0L) {
                return 0.0
            }

            // ----------------------------------------------------------------
            // 6. Pick the largest contour as the float / plunger body
            // ----------------------------------------------------------------
            var maxArea = 0.0
            var bestRect: Rect? = null

            var i = 0L
            while (i < contours.size()) {
                val c = contours.get(i)
                val area = contourArea(c)
                if (area > maxArea) {
                    maxArea = area
                    bestRect = boundingRect(c)
                }
                i++
            }

            if (bestRect == null) {
                return 0.0
            }

            // ----------------------------------------------------------------
            // 7. Compute relative height of float (0 bottom, 1 top)
            // ----------------------------------------------------------------
            val floatCenterY = bestRect.y() + bestRect.height() / 2.0
            val relative = 1.0 - (floatCenterY / gauge.rows().toDouble())

            return relative.coerceIn(0.0, 1.0)
        } finally {
            try {
                Files.deleteIfExists(tmpFile)
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}
