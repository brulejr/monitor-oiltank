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

import java.nio.ByteBuffer

object RtpPacketParser {

    /**
     * Parse an RTP packet extracted from an RTSP interleaved frame.
     *
     * RTSP over TCP interleaved frame format:
     *   $ (0x24)
     *   channel (1 byte)
     *   size high (1 byte)
     *   size low  (1 byte)
     *   RTP packet (size bytes)
     */
    fun parse(channel: Int, data: ByteArray): RtpPacket {
        val buffer = ByteBuffer.wrap(data)

        // RTP Header (first 12 bytes minimum)
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF

        val version = b0 shr 6
        require(version == 2) { "Unsupported RTP version: $version" }

        val padding = (b0 and 0x20) != 0
        val extension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        val marker = (b1 and 0x80) != 0
        val payloadType = b1 and 0x7F

        val sequenceNumber = buffer.short.toInt() and 0xFFFF
        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL

        // Skip SSRC
        buffer.int

        // Skip CSRC entries (rare)
        if (csrcCount > 0) {
            buffer.position(buffer.position() + csrcCount * 4)
        }

        // Skip header extension
        if (extension) {
            if (buffer.remaining() < 4) return incomplete(channel)
            val extId = buffer.short.toInt() and 0xFFFF
            val extLenWords = buffer.short.toInt() and 0xFFFF
            val bytesToSkip = extLenWords * 4
            if (buffer.remaining() < bytesToSkip) return incomplete(channel)
            buffer.position(buffer.position() + bytesToSkip)
        }

        // Adjust for padding
        val payloadSize = if (padding) {
            val padAmount = data.last().toInt() and 0xFF
            data.size - buffer.position() - padAmount
        } else {
            data.size - buffer.position()
        }

        if (payloadSize <= 0) return incomplete(channel)

        val payload = ByteArray(payloadSize)
        buffer.get(payload)

        return RtpPacket(
            channel = channel,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            payloadType = payloadType,
            marker = marker,
            payload = payload
        )
    }

    private fun incomplete(channel: Int): RtpPacket {
        return RtpPacket(
            channel = channel,
            sequenceNumber = -1,
            timestamp = -1,
            payloadType = -1,
            marker = false,
            payload = ByteArray(0)
        )
    }

}
