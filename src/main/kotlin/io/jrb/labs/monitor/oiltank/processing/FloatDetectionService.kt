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

package io.jrb.labs.monitor.oiltank.processing

import io.jrb.labs.monitor.oiltank.config.TankLevelCalibrationDatafill
import io.jrb.labs.monitor.oiltank.decoder.FloatDetector
import io.jrb.labs.monitor.oiltank.events.EventBus
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.jrb.labs.monitor.oiltank.model.FloatPosition
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import kotlin.math.roundToInt

@Service
class FloatDetectionService(
    private val eventBus: EventBus,
    private val datafill: TankLevelCalibrationDatafill
) {

    private val log = LoggerFactory.getLogger(FloatDetectionService::class.java)

    /** Last smoothed tank percent, used for hysteresis */
    private var lastPercent: Double? = null

    @PostConstruct
    fun listen() {
        eventBus.events()
            .ofType(OilEvent.SnapshotReceived::class.java)
            .flatMap { event ->
                detectFloat(event.bytes).map { pos -> event to pos }
            }
            .subscribe { (event, pos) ->
                log.info("Float detected: ${"%.1f".format(pos.relativeHeight * 100)}%")
                eventBus.publish(OilEvent.FloatPositionDetected(pos))
            }
    }

    /**
     * Apply datafill + smoothing to raw detector output.
     */
    fun detectFloat(bytes: ByteArray): Mono<FloatPosition> {
        return Mono.fromCallable {
            val raw = FloatDetector.detect(bytes)
            val safeRaw = raw ?: 0.0

            val calibrated = calibrate(safeRaw)
            val smoothed = smooth(calibrated)

            log.info(
                "FloatDetection raw=${"%.4f".format(safeRaw)}, " +
                        "calibrated=${"%.4f".format(calibrated)}, " +
                        "smoothed=${"%.1f".format(smoothed * 100)}%"
            )

            FloatPosition(smoothed)
        }
    }

    /**
     * Convert raw float height into real-world percent using config.
     */
    private fun calibrate(raw: Double): Double {
        val scaled = (raw - datafill.rawEmpty) /
                (datafill.rawFull - datafill.rawEmpty)

        return scaled.coerceIn(0.0, 1.0)
    }

    /**
     * Smooth jitter (low-pass filter + optional quantization)
     */
    private fun smooth(current: Double): Double {
        val prev = lastPercent

        val blended = if (prev == null) {
            current
        } else {
            0.8 * prev + 0.2 * current   // low-pass smoothing
        }

        // Optional: snap to nearest 2.5% to remove flicker
        val snapped = ((blended * 100) / 2.5).roundToInt() * 2.5
        val result = (snapped / 100.0).coerceIn(0.0, 1.0)

        lastPercent = result
        return result
    }
}

