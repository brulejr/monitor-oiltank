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

import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

class RtspDisconnectedException : RuntimeException("RTSP disconnected")

class RtspClient(
    private val rtsp: RtspUrl,
    private val username: String,
    private val password: String
) {
    private val log = LoggerFactory.getLogger(RtspClient::class.java)

    private lateinit var socket: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: PrintWriter
    private lateinit var rawInput: BufferedInputStream

    private var cseq = 1
    private var sessionId: String? = null

    fun open() {
        log.info("Opening RTSP TCP socket to ${rtsp.host}:${rtsp.port}")

        socket = Socket(rtsp.host, rtsp.port)
        socket.soTimeout = 7000

        rawInput = BufferedInputStream(socket.getInputStream())
        reader = BufferedReader(InputStreamReader(rawInput, StandardCharsets.UTF_8))
        writer = PrintWriter(socket.getOutputStream(), true)

        val streamUrl = rtsp.fullUrl

        // -----------------------------
        // STEP 1 — DESCRIBE
        // -----------------------------
        val describeHeaders = mapOf(
            "Accept" to "application/sdp",
            "User-Agent" to "KotlinRTSP/1.0",
            "Range" to "npt=0-",
            "Authorization" to basicAuthHeader()
        )

        val describeResp = sendRtspRequest("DESCRIBE", streamUrl, describeHeaders)

        if (describeResp.code != 200) {
            throw IllegalStateException("DESCRIBE failed (${describeResp.code})")
        }

        val sdp = describeResp.body ?: throw IllegalStateException("Missing SDP in DESCRIBE")

        val track = extractControlTrack(sdp)
        val controlUrl = streamUrl.trimEnd('/') + "/" + track

        log.info("Using RTSP control URI: $controlUrl")

        // -----------------------------
        // STEP 2 — SETUP
        // -----------------------------
        val setupResp = sendRtspRequest(
            method = "SETUP",
            uri = controlUrl,
            headers = mapOf(
                "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1",
                "User-Agent" to "KotlinRTSP/1.0"
            )
        )

        if (setupResp.code != 200) {
            throw IllegalStateException("SETUP failed (${setupResp.code})")
        }

        sessionId = setupResp.headers["Session"]?.substringBefore(";")?.trim()
        log.info("RTSP session id: $sessionId")

        // -----------------------------
        // STEP 3 — PLAY
        // -----------------------------
        val playResp = sendRtspRequest(
            method = "PLAY",
            uri = controlUrl,
            headers = mapOf(
                "Session" to sessionId!!,
                "Range" to "npt=0-",
                "User-Agent" to "KotlinRTSP/1.0"
            )
        )

        if (playResp.code != 200) {
            throw IllegalStateException("PLAY failed (${playResp.code})")
        }

        log.info("RTSP PLAY OK, starting to read interleaved RTP packets")
    }

    fun readRtpLoop(onFrame: (ByteArray) -> Unit) {
        try {
            while (true) {
                val first = rawInput.read()
                if (first == -1) throw RtspDisconnectedException()
                if (first != 0x24) continue // $ = interleaved RTP

                val channel = rawInput.read()
                val sizeHi = rawInput.read()
                val sizeLo = rawInput.read()

                if (channel < 0 || sizeHi < 0 || sizeLo < 0)
                    throw RtspDisconnectedException()

                val packetSize = (sizeHi shl 8) or sizeLo
                val packet = ByteArray(packetSize)
                rawInput.read(packet)

                log.debug("RTP packet: channel=$channel size=$packetSize")

                onFrame(packet)
            }
        } catch (e: Exception) {
            throw RtspDisconnectedException()
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun basicAuthHeader(): String {
        val encoded = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray())
        return "Basic $encoded"
    }

    private fun extractControlTrack(sdp: String): String {
        return sdp.lineSequence()
            .firstOrNull { it.startsWith("a=control:") }
            ?.substringAfter("a=control:")
            ?.trim()
            ?: "track1"
    }

    private fun sendRtspRequest(
        method: String,
        uri: String,
        headers: Map<String, String>
    ): RtspResponse {

        val seq = cseq++
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $seq\r\n")

        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")

        log.debug("RTSP REQUEST →\n$sb")

        writer.print(sb.toString())
        writer.flush()

        return readRtspResponse()
    }

    private fun readRtspResponse(): RtspResponse {
        val statusLine = reader.readLine() ?: throw RtspDisconnectedException()

        val parts = statusLine.split(" ", limit = 3)
        val code = parts[1].toInt()
        val statusText = parts.getOrNull(2) ?: ""

        val headers = mutableMapOf<String, String>()
        var body: String? = null

        while (true) {
            val line = reader.readLine() ?: throw RtspDisconnectedException()
            if (line.isBlank()) break
            val idx = line.indexOf(":")
            if (idx > 0) headers[line.substring(0, idx)] = line.substring(idx + 1).trim()
        }

        val contentLen = headers["Content-Length"]?.toIntOrNull()
        if (contentLen != null && contentLen > 0) {
            val chars = CharArray(contentLen)
            val n = reader.read(chars)
            body = String(chars, 0, n)
        }

        log.debug("RTSP RESPONSE ←\n$statusLine\n$headers\n\n$body")

        return RtspResponse(code, statusText, headers, body)
    }
}
