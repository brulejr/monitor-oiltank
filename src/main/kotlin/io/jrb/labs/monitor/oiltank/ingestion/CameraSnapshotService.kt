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
import io.jrb.labs.monitor.oiltank.decoder.JCodecH264Decoder
import io.jrb.labs.monitor.oiltank.events.EventBus
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.jrb.labs.monitor.oiltank.rtsp.RtpH264Depacketizer
import io.jrb.labs.monitor.oiltank.rtsp.RtspClient
import io.jrb.labs.monitor.oiltank.rtsp.RtspDisconnectedException
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@Service
class CameraSnapshotService(
    private val datafill: CameraDatafill,
    private val eventBus: EventBus
) {

    private val log by LoggerDelegate()

    private val decoder = JCodecH264Decoder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    log.info("Starting RTSP session to ${datafill.snapshotUrl}")
                    startRtspSession()
                } catch (ex: RtspDisconnectedException) {
                    log.warn("RTSP connection dropped — reconnecting in 2s")
                    delay(2000)
                } catch (ex: Exception) {
                    log.error("Unexpected RTSP error — reconnecting in 2s", ex)
                    delay(2000)
                }
            }
        }
    }

    private suspend fun startRtspSession() {
        val rtsp = RtspClient(
            url = datafill.snapshotUrl,
            username = datafill.username,
            password = datafill.password
        )

        rtsp.rtpStream().collect { packet ->

            val nals = RtpH264Depacketizer.extractNalUnits(packet)
            if (nals.isEmpty()) return@collect

            val img = decoder.decode(nals)
            if (img != null) {
                val baos = ByteArrayOutputStream()
                ImageIO.write(img, "jpeg", baos)
                eventBus.publish(OilEvent.SnapshotReceived(baos.toByteArray()))
            }
        }
    }

}
