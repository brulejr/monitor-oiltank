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

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parse an RTSP URL into an RtspUrl data class.
 *
 * Handles:
 *   - embedded credentials
 *   - percent-decoding of username/password
 *   - missing port (defaults to 554)
 *   - path extraction
 */
fun parseRtspUrl(rawUrl: String): RtspUrl {
    val uri = URI(rawUrl)

    val userInfo = uri.userInfo
    var username: String? = null
    var password: String? = null

    if (userInfo != null) {
        val parts = userInfo.split(":", limit = 2)
        username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)

        if (parts.size == 2) {
            password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
        }
    }

    val host = uri.host ?: throw IllegalArgumentException("Invalid RTSP URL: missing host")
    val port = if (uri.port == -1) 554 else uri.port

    val path = if (uri.path.isNullOrBlank()) "/stream1" else uri.path

    return RtspUrl(
        username = username,
        password = password,
        host = host,
        port = port,
        path = path
    )
}
