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

/**
 * Minimalistic H.264 over RTP depacketizer.
 *
 * NOTE: This is intentionally simplified. It:
 *  - Supports single NALU packets (types 1..23)
 *  - Supports FU-A (type 28) with very basic reassembly
 *
 * For production, maintain FU-A state across packets (START/MIDDLE/END).
 */
object RtpH264Depacketizer {

    // Simple buffer for FU-A reassembly (per stream)
    private var fuBuffer: MutableList<ByteArray> = mutableListOf()

    fun extractNalUnits(packet: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()

        // Basic sanity check: RTP header is 12 bytes
        if (packet.size <= 12) return emptyList()

        val nalHeader = packet[12].toInt()
        val nalType = nalHeader and 0x1F

        return when (nalType) {
            in 1..23 -> { // Single NALU
                nalUnits += packet.copyOfRange(12, packet.size)
                nalUnits
            }

            28 -> { // FU-A fragmentation
                if (packet.size <= 14) return emptyList()
                val fuIndicator = nalHeader
                val fuHeader = packet[13].toInt()
                val start = fuHeader and 0x80 != 0
                val end = fuHeader and 0x40 != 0
                val origNalType = fuHeader and 0x1F

                val fuPayload = packet.copyOfRange(14, packet.size)

                if (start) {
                    fuBuffer = mutableListOf()
                    val reconstructedHeader = ((fuIndicator and 0xE0) or origNalType).toByte()
                    fuBuffer.add(byteArrayOf(reconstructedHeader))
                }

                fuBuffer.add(fuPayload)

                if (end) {
                    val totalSize = fuBuffer.sumOf { it.size }
                    val nal = ByteArray(totalSize)
                    var pos = 0
                    for (chunk in fuBuffer) {
                        System.arraycopy(chunk, 0, nal, pos, chunk.size)
                        pos += chunk.size
                    }
                    fuBuffer.clear()
                    listOf(nal)
                } else {
                    emptyList()
                }
            }

            else -> {
                // Ignore other NAL types for now (STAP-A, etc.)
                emptyList()
            }
        }
    }
}