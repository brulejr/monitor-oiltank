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

import java.io.BufferedInputStream

/**
 * Parsed RTSP response returned from the camera.
 *
 * Example:
 *   RTSP/1.0 200 OK
 *   CSeq: 2
 *   Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY
 */
data class RtspResponse(
    val code: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: String?
)

fun readRtspResponse(input: BufferedInputStream): RtspResponse {
    val statusLine = input.readLine() ?: throw RtspDisconnectedException()

    val statusParts = statusLine.split(" ", limit = 3)
    if (statusParts.size < 2)
        throw RtspDisconnectedException()

    val code = statusParts[1].toInt()
    val text = if (statusParts.size > 2) statusParts[2] else ""

    val headers = mutableMapOf<String, String>()

    while (true) {
        val line = input.readLine() ?: break
        if (line.isBlank()) break

        val idx = line.indexOf(':')
        if (idx > 0) {
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            headers[k] = v
        }
    }

    // Read body if Content-Length present
    val body = if (headers.containsKey("Content-Length")) {
        val len = headers["Content-Length"]!!.toInt()
        val buf = ByteArray(len)
        input.read(buf)
        String(buf)
    } else null

    return RtspResponse(code, text, headers, body)
}

/**
 * Reads a single line (terminated by CRLF) from a BufferedInputStream.
 */
private fun BufferedInputStream.readLine(): String? {
    val sb = StringBuilder()
    while (true) {
        val c = this.read()
        if (c == -1) return null
        if (c == '\r'.code) continue
        if (c == '\n'.code) break
        sb.append(c.toChar())
    }
    return sb.toString()
}