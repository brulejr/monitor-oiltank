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

package io.jrb.labs.monitor.oiltank.ingest

import io.jrb.labs.commons.logging.LoggerDelegate
import io.jrb.labs.monitor.oiltank.config.CameraDatafill
import io.jrb.labs.monitor.oiltank.rtsp.RtspClient
import io.jrb.labs.monitor.oiltank.rtsp.RtspDisconnectedException
import io.jrb.labs.monitor.oiltank.rtsp.parseRtspUrl
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import kotlin.concurrent.thread

@Service
class CameraSnapshotService(
    private val datafill: CameraDatafill
) {

    private val log by LoggerDelegate()

    @Volatile private var isRunning = true

    @PostConstruct
    fun start() {
        log.info("Starting CameraSnapshotService RTSP loop")

        thread(start = true, isDaemon = true, name = "rtsp-loop") {
            runRtspLoop()
        }
    }

    private fun runRtspLoop() {
        val rtspUrl = parseRtspUrl(rawUrl = datafill.snapshotUrl)

        while (isRunning) {
            try {
                log.info("Opening RTSP streaming session")

                val client = RtspClient(
                    rtsp = rtspUrl,
                    username = datafill.username,
                    password = datafill.password
                )

                client.open()

                client.readRtpLoop { packet ->
                    // TODO: Extract H264 → JPEG → Float detection
                    log.debug("Received RTP packet ${packet.size} bytes")
                }

            } catch (e: RtspDisconnectedException) {
                log.warn("RTSP connection dropped: ${e.message} — reconnecting in 2s")
                Thread.sleep(2000)
            } catch (e: Exception) {
                log.error("RTSP error: ${e.message}", e)
                Thread.sleep(3000)
            }
        }
    }
}
