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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service

@Service
class CameraSnapshotService(
    private val datafill: CameraDatafill
) {

    private val log by LoggerDelegate()
    private val rtspClient = RtspClient(datafill)

    init {
        // Just as a smoke test: verify DESCRIBE works when the app starts
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = rtspClient.describe()
                log.info("Initial RTSP DESCRIBE: {}", resp.statusLine)
                log.debug("RTSP headers: {}", resp.headers)
                log.debug("RTSP body (SDP):\n{}", resp.body)
            } catch (ex: Exception) {
                log.error("Failed initial RTSP DESCRIBE", ex)
            }
        }
    }

    // Later you’ll extend this to SETUP, PLAY, read RTP, decode H264, etc.
}
