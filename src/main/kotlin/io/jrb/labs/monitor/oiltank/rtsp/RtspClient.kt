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

class RtspClient(
    private val datafill: CameraDatafill
) {

    private val log by LoggerDelegate()

    /**
     * Performs a single RTSP DESCRIBE using Basic Auth and returns the response.
     * Use this as the “known good” baseline that matches your working unit test.
     */
    fun describe(): RtspResponse {
        val rtspUrl = parseRtspUrl(datafill.snapshotUrl)
        val requestUri = "rtsp://${rtspUrl.host}:${rtspUrl.port}${rtspUrl.path}"

        val userpass = "${datafill.username}:${datafill.password}"
        val auth = Base64.getEncoder().encodeToString(userpass.toByteArray(StandardCharsets.UTF_8))

        log.info("RTSP DESCRIBE to {}:{}", rtspUrl.host, rtspUrl.port)

        Socket(rtspUrl.host, rtspUrl.port).use { socket ->
            socket.soTimeout = 5000

            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))

            // --- send request (CRLF) ---
            val request = buildString {
                append("DESCRIBE $requestUri RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                append("Authorization: Basic $auth\r\n")
                append("User-Agent: monitor-oiltank/1.0\r\n")
                append("Accept: application/sdp\r\n")
                append("\r\n")
            }

            log.debug("RTSP request:\n{}", request)

            writer.write(request)
            writer.flush()

            // --- read status line ---
            val statusLine = reader.readLine()
                ?: throw IllegalStateException("No RTSP status line returned by camera")

            log.debug("RTSP status line: {}", statusLine)

            // --- read headers ---
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

            // For DESCRIBE, server usually returns an SDP body (small, can read it all)
            val bodyBuilder = StringBuilder()
            while (reader.ready()) {
                val ch = reader.read()
                if (ch == -1) break
                bodyBuilder.append(ch.toChar())
            }

            val body = bodyBuilder.toString().ifBlank { null }

            return RtspResponse(
                statusLine = statusLine,
                headers = headers,
                body = body
            )
        }
    }
}
