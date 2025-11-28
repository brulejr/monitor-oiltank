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

import io.jrb.labs.monitor.oiltank.events.LocalEventBus
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import kotlin.math.abs

@Service
class FloatDetectionService(
    private val localEventBus: LocalEventBus,
    private val datafill: FloatDetectionDatafill,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(FloatDetectionService::class.java)

    @Volatile
    private var lastSmoothed: Double? = null

    @Volatile
    private var lastPublished: Double? = null

    // ---- Micrometer metrics ----

    private val detectionTimer: Timer =
        Timer.builder("oiltank.float.detection.time")
            .description("Time taken to process a float snapshot")
            .register(meterRegistry)

    private val rawLevelSummary: DistributionSummary =
        DistributionSummary.builder("oiltank.float.level.raw")
            .description("Raw float level from OpenCV [0..1]")
            .register(meterRegistry)

    private val calibratedLevelSummary: DistributionSummary =
        DistributionSummary.builder("oiltank.float.level.calibrated")
            .description("Calibrated float level [0..1]")
            .register(meterRegistry)

    private val smoothedLevelSummary: DistributionSummary =
        DistributionSummary.builder("oiltank.float.level.smoothed")
            .description("Smoothed float level [0..1]")
            .register(meterRegistry)

    private val percentLevelSummary: DistributionSummary =
        DistributionSummary.builder("oiltank.float.level.percent")
            .description("Smoothed tank level [%]")
            .register(meterRegistry)

    @PostConstruct
    fun listen() {
        localEventBus.events()
            .ofType(OilEvent.SnapshotReceived::class.java)
            .flatMap { event ->
                detectFloat(event.bytes).map { pos -> event to pos }
            }
            .subscribe { (_, pos) ->
                if (shouldPublish(pos.relativeHeight)) {
                    val percent = pos.relativeHeight * 100.0
                    log.info("Float detected: {}%", String.format("%.1f", percent))

                    localEventBus.publish(OilEvent.FloatPositionDetected(pos))
                } else {
                    val last = lastPublished
                    val delta = last?.let { abs(pos.relativeHeight - it) }
                    log.debug(
                        "Float change below hysteresis threshold; not publishing. level={}, lastPublished={}, delta={}",
                        String.format("%.4f", pos.relativeHeight),
                        last?.let { String.format("%.4f", it) },
                        delta?.let { String.format("%.4f", it) }
                    )
                }
            }
    }

    fun detectFloat(bytes: ByteArray): Mono<FloatPosition> =
        Mono.fromCallable {
            val sample = Timer.start(meterRegistry)

            val raw = FloatDetector.detect(bytes).coerceIn(0.0, 1.0)
            val calibrated = calibrate(raw)
            val smoothed = smooth(calibrated)
            val percent = smoothed * 100.0

            sample.stop(detectionTimer)

            // ---- metrics ----
            rawLevelSummary.record(raw)
            calibratedLevelSummary.record(calibrated)
            smoothedLevelSummary.record(smoothed)
            percentLevelSummary.record(percent)

            // ---- structured logging ----
            log.info(
                "FloatDetection raw={}, calibrated={}, smoothed={}, percent={}",
                String.format("%.4f", raw),
                String.format("%.4f", calibrated),
                String.format("%.4f", smoothed),
                String.format("%.1f", percent)
            )

            FloatPosition(smoothed)
        }

    // --- Calibration, smoothing, hysteresis --------------------------------

    private fun calibrate(raw: Double): Double =
        (datafill.calibrationSlope * raw + datafill.calibrationIntercept)
            .coerceIn(0.0, 1.0)

    private fun smooth(value: Double): Double {
        val prev = lastSmoothed
        val alpha = datafill.smoothingAlpha

        val smoothed = if (prev == null) {
            value
        } else {
            alpha * value + (1 - alpha) * prev
        }

        lastSmoothed = smoothed
        return smoothed
    }

    private fun shouldPublish(newValue: Double): Boolean {
        val prev = lastPublished ?: run {
            lastPublished = newValue
            return true
        }

        val delta = abs(newValue - prev)
        return if (delta >= datafill.hysteresisDelta) {
            lastPublished = newValue
            true
        } else {
            false
        }
    }
}