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

import io.jrb.labs.monitor.oiltank.decoder.FloatDetector
import io.jrb.labs.monitor.oiltank.events.EventBus
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.jrb.labs.monitor.oiltank.model.FloatPosition
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class FloatDetectionService(
    private val eventBus: EventBus
) {

    private val log = LoggerFactory.getLogger(FloatDetectionService::class.java)

    @PostConstruct
    fun listen() {
        eventBus.events()
            .ofType(OilEvent.SnapshotReceived::class.java)
            .flatMap { event ->
                detectFloat(event.bytes).map { pos -> event to pos }
            }
            .subscribe { (_, pos) ->
                log.info("Float detected: ${"%.1f".format(pos.relativeHeight * 100)}%")
                eventBus.publish(OilEvent.FloatPositionDetected(pos))
            }
    }

    /**
     * Run OpenCV float detection.
     */
    fun detectFloat(bytes: ByteArray): Mono<FloatPosition> {
        return Mono.fromCallable {
            val result = FloatDetector.detect(bytes)   // <—— correct call

            if (result == null) {
                log.warn("Float detection returned null — using 0.0")
                FloatPosition(0.0)
            } else {
                FloatPosition(result)                 // <—— already Double
            }
        }
    }
}

