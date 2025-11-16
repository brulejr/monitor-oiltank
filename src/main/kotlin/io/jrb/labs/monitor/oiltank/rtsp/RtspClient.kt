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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

class RtspClient(
    private val url: String,
    private val username: String? = null,
    private val password: String? = null,
    private val rtpPort: Int = 5000
) {

    fun rtpStream(): Flow<ByteArray> = flow {
        val uri = URI(url)
        val host = uri.host
        val port = if (uri.port == -1) 554 else uri.port
        val path = uri.rawPath.ifEmpty { "/" }

        Socket(host, port).use { socket ->
            val writer = PrintWriter(socket.getOutputStream(), true)
            val input = BufferedInputStream(socket.getInputStream())

            var cseq = 1
            var session: String? = null

            fun writeHeaders(startLine: String, extraHeaders: List<String> = emptyList()) {
                writer.println(startLine)
                writer.println("CSeq: $cseq")
                if (username != null && password != null) {
                    val basic = Base64.getEncoder()
                        .encodeToString("$username:$password".toByteArray(StandardCharsets.ISO_8859_1))
                    writer.println("Authorization: Basic $basic")
                }
                extraHeaders.forEach { writer.println(it) }
                writer.println()
                writer.flush()
                cseq++
            }

            // OPTIONS
            writeHeaders("OPTIONS $url RTSP/1.0")
            input.readRtspResponse()

            // DESCRIBE (grab SDP, but we won’t fully parse it here)
            writeHeaders(
                "DESCRIBE $url RTSP/1.0",
                listOf("Accept: application/sdp")
            )
            val sdpResponse = input.readRtspResponse()
            val sdpBody = sdpResponse.body

            // SETUP: assume first video track and client RTP port
            writeHeaders(
                "SETUP $url/trackID=1 RTSP/1.0",
                listOf("Transport: RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}")
            )
            val setupResponse = input.readRtspResponse()
            session = setupResponse.headers["Session"]?.split(";")?.firstOrNull()

            // PLAY
            writeHeaders(
                "PLAY $url RTSP/1.0",
                listOfNotNull(session?.let { "Session: $it" })
            )
            input.readRtspResponse()

            // Now receive RTP packets over UDP
            val rtpSocket = DatagramSocket(rtpPort)
            val buf = ByteArray(2048)

            while (true) {
                val dp = DatagramPacket(buf, buf.size)
                rtpSocket.receive(dp)
                emit(dp.data.copyOf(dp.length))
            }
        }
    }
}

// --- Helpers for RTSP parsing ---

private data class RtspResponse(
    val statusLine: String,
    val headers: Map<String, String>,
    val body: String
)

private fun InputStream.readRtspResponse(): RtspResponse {
    val reader = this.bufferedReader(StandardCharsets.ISO_8859_1)

    val statusLine = reader.readLine() ?: throw IllegalStateException("No RTSP status line")
    val headers = mutableMapOf<String, String>()

    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }
    }

    // Some responses (DESCRIBE) have SDP body; others don't.
    val contentLength = headers["Content-Length"]?.toIntOrNull()
    val body = if (contentLength != null && contentLength > 0) {
        val bodyChars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = reader.read(bodyChars, read, contentLength - read)
            if (r == -1) break
            read += r
        }
        String(bodyChars, 0, read)
    } else {
        ""
    }

    return RtspResponse(statusLine, headers, body)
}