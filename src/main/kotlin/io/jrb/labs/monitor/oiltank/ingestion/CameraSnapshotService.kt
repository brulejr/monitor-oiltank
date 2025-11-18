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

import io.jrb.labs.commons.logging.LoggerDelegate
import io.jrb.labs.monitor.oiltank.config.CameraDatafill
import io.jrb.labs.monitor.oiltank.rtsp.RtspClient
import io.jrb.labs.monitor.oiltank.rtsp.RtspDisconnectedException
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service

@Service
class CameraSnapshotService(
    private val datafill: CameraDatafill
) {

    private val log by LoggerDelegate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun start() {
        log.info("Starting CameraSnapshotService RTSP loop")
        scope.launch {
            runRtspLoop()
        }
    }

    @PreDestroy
    fun stop() {
        log.info("[RTSP] Stopping CameraSnapshotService RTSP loop")
        scope.cancel()
    }

    private suspend fun runRtspLoop() = coroutineScope {
        while (isActive) {
            try {
                RtspClient(datafill).use { client ->
                    log.info("[RTSP] Opening RTSP streaming session")
                    client.startStreaming { packet ->
                        // For now, just log packet metadata.
                        // Next step: parse RTP and extract H264 NAL units.
                        if (log.isDebugEnabled) {
                            log.debug(
                                "RTP packet on channel {} length={}",
                                packet.channel,
                                packet.payload.size
                            )
                        }
                    }
                }
            } catch (e: RtspDisconnectedException) {
                log.warn("[RTSP] RTSP connection dropped: ${e.message} — reconnecting in 2s")
                delay(2000)
            } catch (e: CancellationException) {
                log.info("[RTSP] RTSP loop cancelled")
                break
            } catch (e: Exception) {
                log.error("[RTSP] Unexpected error in RTSP loop", e)
                delay(5000)
            }
        }
    }

}