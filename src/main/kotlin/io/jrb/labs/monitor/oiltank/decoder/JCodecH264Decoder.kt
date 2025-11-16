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

package io.jrb.labs.monitor.oiltank.decoder

import org.jcodec.codecs.h264.H264Decoder
import org.jcodec.common.model.Picture
import org.jcodec.scale.AWTUtil
import java.awt.image.BufferedImage
import java.nio.ByteBuffer

class JCodecH264Decoder {

    private val decoder = H264Decoder()

    fun decode(nals: List<ByteArray>): BufferedImage? {
        if (nals.isEmpty()) return null

        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)

        val buffers = nals.map { nal ->
            ByteBuffer.allocate(nal.size + 4).apply {
                put(startCode)
                put(nal)
                flip()
            }
        }

        // IMPORTANT: 2nd parameter is REQUIRED in 0.2.5
        val picture: Picture = decoder.decodeFrameFromNals(buffers, null) ?: return null

        return AWTUtil.toBufferedImage(picture)
    }

}