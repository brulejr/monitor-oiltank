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

package io.jrb.labs.monitor.oiltank.rtsp

import io.jrb.labs.commons.logging.LoggerDelegate
import io.jrb.labs.monitor.oiltank.config.CameraDatafill
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

data class RtspResponse(
    val statusLine: String,
    val headers: Map<String, String>,
    val body: String?
)

data class RtpPacket(
    val channel: Int,
    val payload: ByteArray
)

class RtspDisconnectedException(message: String) : RuntimeException(message)

/**
 * Minimal RTSP client for Tapo C100:
 * - DESCRIBE with Basic auth
 * - SETUP with interleaved RTP over TCP (channels 0/1)
 * - PLAY
 * - Read interleaved RTP packets on the same TCP socket
 */
class RtspClient(
    private val datafill: CameraDatafill
) : Closeable {

    private val log by LoggerDelegate()

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private var cSeq: Int = 1
    private var sessionId: String? = null
    private lateinit var requestUri: String
    private lateinit var rtspUrl: RtspUrl

    private fun connectIfNeeded() {
        if (socket != null) return

        rtspUrl = parseRtspUrl(datafill.snapshotUrl)
        requestUri = "rtsp://${rtspUrl.host}:${rtspUrl.port}${rtspUrl.path}"

        log.info("Opening RTSP TCP socket to {}:{}", rtspUrl.host, rtspUrl.port)

        val s = Socket(rtspUrl.host, rtspUrl.port)
        s.soTimeout = 10000

        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1))
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        writer = null
    }

    private fun basicAuthHeader(): String {
        val userPass = "${datafill.username}:${datafill.password}"
        val encoded = Base64.getEncoder().encodeToString(userPass.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
    }

    private fun sendRequest(
        method: String,
        uri: String = requestUri,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null
    ): RtspResponse {
        connectIfNeeded()

        val r = reader ?: throw IllegalStateException("RTSP reader not initialized")
        val w = writer ?: throw IllegalStateException("RTSP writer not initialized")

        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq++}\r\n")
        sb.append("User-Agent: monitor-oiltank/1.0\r\n")
        sb.append("Authorization: ${basicAuthHeader()}\r\n")

        sessionId?.let { sid ->
            sb.append("Session: $sid\r\n")
        }

        for ((k, v) in extraHeaders) {
            sb.append("$k: $v\r\n")
        }

        if (body != null) {
            val bytes = body.toByteArray(StandardCharsets.ISO_8859_1)
            sb.append("Content-Length: ${bytes.size}\r\n")
            sb.append("\r\n")
            sb.append(body)
        } else {
            sb.append("\r\n")
        }

        val request = sb.toString()
        log.debug("RTSP request:\n{}", request)

        w.write(request)
        w.flush()

        return readResponse(r)
    }

    private fun readResponse(reader: BufferedReader): RtspResponse {
        val statusLine = reader.readLine()
            ?: throw RtspDisconnectedException("No RTSP status line (connection closed)")

        log.debug("RTSP status line: {}", statusLine)

        val headers = mutableMapOf<String, String>()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break

            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                headers[name.lowercase()] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()
        val body: String? = if (contentLength != null && contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            String(buf, 0, read)
        } else {
            null
        }

        return RtspResponse(statusLine = statusLine, headers = headers, body = body)
    }

    fun describe(): RtspResponse =
        sendRequest(
            method = "DESCRIBE",
            uri = requestUri,
            extraHeaders = mapOf("Accept" to "application/sdp")
        )

    private fun extractControlUrl(sdp: String?, defaultUri: String): String {
        if (sdp == null) return "$defaultUri/trackID=1"

        // Look for a=control: lines, prefer video track
        val lines = sdp.lines()
        var control: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("a=control:", ignoreCase = true)) {
                val value = trimmed.removePrefix("a=control:").trim()
                // Some SDP use "trackID=1", some absolute rtsp://...
                control = value
                // We just take the first for now; could refine by context (m=video)
                break
            }
        }

        if (control.isNullOrBlank()) {
            return "$defaultUri/trackID=1"
        }

        // Absolute control URI
        return if (control.startsWith("rtsp://", ignoreCase = true)) {
            control
        } else {
            // Relative: append to base URI
            if (control.startsWith("/")) {
                // absolute path relative to host
                "rtsp://${rtspUrl.host}:${rtspUrl.port}$control"
            } else {
                // relative to defaultUri path
                if (defaultUri.endsWith("/")) {
                    "$defaultUri$control"
                } else {
                    "$defaultUri/$control"
                }
            }
        }
    }

    fun setup(controlUri: String): RtspResponse {
        val transport = "RTP/AVP/TCP;unicast;interleaved=0-1"
        val resp = sendRequest(
            method = "SETUP",
            uri = controlUri,
            extraHeaders = mapOf("Transport" to transport)
        )

        // Example: Session: 12345678;timeout=60
        val sessionHeader = resp.headers["session"]
        if (sessionHeader != null) {
            sessionId = sessionHeader.substringBefore(';').trim()
            log.info("RTSP session id: {}", sessionId)
        }

        return resp
    }

    fun play(controlUri: String): RtspResponse =
        sendRequest(
            method = "PLAY",
            uri = controlUri,
            extraHeaders = emptyMap()
        )

    /**
     * Full handshake and RTP loop:
     *  - DESCRIBE (get SDP)
     *  - SETUP (TCP interleaved RTP)
     *  - PLAY
     *  - Then read interleaved RTP packets and hand them to the callback.
     */
    fun startStreaming(onRtpPacket: (RtpPacket) -> Unit) {
        connectIfNeeded()

        // 1) DESCRIBE
        val describeResp = describe()
        if (!describeResp.statusLine.contains("200")) {
            throw IllegalStateException("DESCRIBE failed: ${describeResp.statusLine}")
        }

        val controlUri = extractControlUrl(describeResp.body, requestUri)
        log.info("Using RTSP control URI: {}", controlUri)

        // 2) SETUP
        val setupResp = setup(controlUri)
        if (!setupResp.statusLine.contains("200")) {
            throw IllegalStateException("SETUP failed: ${setupResp.statusLine}")
        }

        // 3) PLAY
        val playResp = play(controlUri)
        if (!playResp.statusLine.contains("200")) {
            throw IllegalStateException("PLAY failed: ${playResp.statusLine}")
        }

        log.info("RTSP PLAY OK, starting to read interleaved RTP packets")

        // 4) Read RTP frames from same TCP socket
        val s = socket ?: throw IllegalStateException("Socket is null after PLAY")
        val input = s.getInputStream()

        while (true) {
            val marker = input.read()
            if (marker == -1) {
                log.warn("RTSP/RTP TCP stream closed by camera")
                break
            }

            if (marker != '$'.code) {
                // Could be RTSP keepalive or junk; for now we just ignore until '$'
                continue
            }

            val channel = input.read()
            if (channel == -1) break

            val sizeHi = input.read()
            val sizeLo = input.read()
            if (sizeHi == -1 || sizeLo == -1) break

            val size = (sizeHi shl 8) or sizeLo
            if (size <= 0) continue

            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n == -1) break
                read += n
            }

            if (read == size) {
                onRtpPacket(RtpPacket(channel = channel, payload = buf))
            } else {
                log.warn("Short read on RTP packet: expected {}, got {}", size, read)
                break
            }
        }
    }
}