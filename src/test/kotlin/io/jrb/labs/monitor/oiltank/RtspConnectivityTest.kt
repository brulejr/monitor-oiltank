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

package io.jrb.labs.monitor.oiltank

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.*

class RtspConnectivityTest {

    @Test
    fun `test RTSP DESCRIBE with Basic Auth`() {

        val host = "10.10.30.249"
        val port = 554
        val path = "/stream1"
        val requestUri = "rtsp://$host:$port$path"

        val username = "tapo@brulenet.dev"
        val password = "_!7iPzMrVXzVc4UorbAY9B"

        val userpass = "$username:$password"
        val auth = Base64.getEncoder().encodeToString(userpass.toByteArray())

        Socket(host, port).use { socket ->
            socket.soTimeout = 5000

            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val request = buildString {
                append("DESCRIBE $requestUri RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                append("Authorization: Basic $auth\r\n")
                append("User-Agent: KotlinRTSP/1.0\r\n")
                append("Accept: application/sdp\r\n")
                append("\r\n")
            }

            println("---- BEGIN REQUEST ----")
            print(request)
            println("---- END REQUEST ----")

            out.write(request.toByteArray())
            out.flush()

            val status = reader.readLine()
            println("STATUS = $status")

            assertTrue(status!!.contains("200"), "Expected RTSP 200 OK")
        }
    }
}
