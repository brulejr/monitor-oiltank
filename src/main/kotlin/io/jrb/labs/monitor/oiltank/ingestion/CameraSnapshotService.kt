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

package io.jrb.labs.monitor.oiltank.ingestion

import io.jrb.labs.commons.eventbus.SystemEventBus
import io.jrb.labs.monitor.oiltank.events.LocalEventBus
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.jrb.labs.monitor.oiltank.events.OilEventBus
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds

/**
 * Simpler implementation:
 *
 * - Uses the system `ffmpeg` binary to grab a single JPEG frame from the RTSP stream.
 * - Runs in a coroutine loop with [CameraDatafill.intervalSeconds].
 * - Saves the latest snapshot to /tmp/oiltank-latest.jpg (for now).
 *
 * This **does not** use the RTSP/RTP/H.264 pipeline at all. It’s just a
 * reliable baseline that works end-to-end.
 */
@Service
class CameraSnapshotService(
    private val datafill: CameraDatafill,
    private val localEventBus: LocalEventBus,
    private val eventBus: OilEventBus,
    systemEventBus: SystemEventBus
) {

    private val log = LoggerFactory.getLogger(CameraSnapshotService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val snapshotPath: Path = Path.of(datafill.snapshotPath)

    @PostConstruct
    fun start() {
        log.info("Starting CameraSnapshotService FFmpeg snapshot loop")
        scope.launch {
            runSnapshotLoop()
        }
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping CameraSnapshotService FFmpeg snapshot loop")
        scope.cancel()
    }

    private suspend fun runSnapshotLoop() {
        val interval = datafill.intervalSeconds.coerceAtLeast(5)
        while (currentCoroutineContext().isActive) {
            try {
                captureSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Error capturing snapshot from camera", e)
            }

            delay(interval.seconds)
        }
    }

    private suspend fun captureSnapshot() = withContext(Dispatchers.IO) {
        val rtspUrl = datafill.snapshotUrl
        log.info("Capturing snapshot via FFmpeg from RTSP URL: {}", rtspUrl)

        val pb = ProcessBuilder(
            "ffmpeg",
            "-hide_banner",
            "-loglevel", "error",
            "-rtsp_transport", "tcp",
            "-i", rtspUrl,
            "-frames:v", "1",
            "-f", "mjpeg",
            "pipe:1"
        )
        pb.redirectErrorStream(true)

        val process = pb.start()

        val imageBytes = process.inputStream.readBytes()
        val finished = process.waitFor()

        if (finished != 0) {
            log.warn("FFmpeg exited with code {} while capturing snapshot", finished)
            if (imageBytes.isNotEmpty()) {
                log.debug("FFmpeg output:\n{}", String(imageBytes))
            }
            return@withContext
        }

        if (imageBytes.isEmpty()) {
            log.warn("FFmpeg produced no image data")
            return@withContext
        }

        // Validate JPEG
        ByteArrayInputStream(imageBytes).use { bais ->
            val img = ImageIO.read(bais)
            if (img == null) {
                log.warn("FFmpeg output was not a valid image")
                return@withContext
            }
        }

        // Save for quick debugging
        Files.write(snapshotPath, imageBytes)
        log.info(
            "Saved latest snapshot ({} bytes) to {}",
            imageBytes.size,
            snapshotPath.toAbsolutePath()
        )

        // 🔥🔥🔥 Publish snapshot into the event pipeline
        localEventBus.publish(OilEvent.SnapshotReceived(imageBytes))
        log.info("Published snapshot to EventBus ({} bytes)", imageBytes.size)
    }
}