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

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class RtspUrl(
    val host: String,
    val port: Int,
    val path: String
)

fun parseRtspUrl(raw: String): RtspUrl {
    // raw like: rtsp://tapo%40brulenet.dev:_%217...@10.10.30.249:554/stream1
    val withoutScheme = raw.removePrefix("rtsp://")

    // Strip userinfo if present: user:pass@host:port/path
    val atIndex = withoutScheme.indexOf('@')
    val hostPart = if (atIndex >= 0) withoutScheme.substring(atIndex + 1) else withoutScheme

    val slashIndex = hostPart.indexOf('/')
    val hostPort = if (slashIndex >= 0) hostPart.substring(0, slashIndex) else hostPart
    val path = if (slashIndex >= 0) hostPart.substring(slashIndex) else "/"

    val colonIndex = hostPort.indexOf(':')

    val host: String
    val port: Int

    if (colonIndex >= 0) {
        host = hostPort.substring(0, colonIndex)
        port = hostPort.substring(colonIndex + 1).toInt()
    } else {
        host = hostPort
        port = 554
    }

    return RtspUrl(
        host = URLDecoder.decode(host, StandardCharsets.UTF_8),
        port = port,
        path = URLDecoder.decode(path, StandardCharsets.UTF_8)
    )

}
