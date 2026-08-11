/*
 * Copyright (C) 2026 soe1hom-arch (https://github.com/soe1hom-arch)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stackscan.processing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object TiffEncoder {

    private const val SOFTWARE = "StackScan"
    private const val RESOLUTION_DPI = 300
    private const val MAX_STRIP_BYTES = 16 * 1024 * 1024
    private const val MAX_ROWS_PER_STRIP = 256

    /**
     * Encode BGR float image (0..255 per channel, urutan OpenCV) menjadi TIFF 16-bit
     * little-endian, uncompressed, multi-strip. Strip dibatasi ~16 MB / 256 baris agar
     * file tetap bisa dibuka aplikasi lain (banyak viewer lama tidak suka satu strip raksasa).
     * Sertakan resolusi fisik (300 DPI) dan profil ICC bila tersedia.
     */
    fun encodeRgb16(width: Int, height: Int, floatRgb: FloatArray, iccProfile: ByteArray? = null): ByteArray {
        val pixelCount = width * height
        require(floatRgb.size >= pixelCount * 3) { "Data piksel kurang dari ukuran gambar." }

        val icc = iccProfile?.takeIf { it.isNotEmpty() }
        val softwareBytes = (SOFTWARE + "\u0000").toByteArray(Charsets.US_ASCII)

        val bytesPerRow = width * 6
        val rowsPerStrip = if (bytesPerRow > 0) {
            maxOf(1, minOf(MAX_ROWS_PER_STRIP, MAX_STRIP_BYTES / bytesPerRow))
        } else MAX_ROWS_PER_STRIP
        val stripCount = (height + rowsPerStrip - 1) / rowsPerStrip

        val entryCount = 14 + if (icc != null) 1 else 0
        val ifdOffset = 8

        // Tata letak: header -> IFD -> data tambahan (BitsPerSample, resolusi, software,
        // offset/count strip, ICC) -> piksel. Semua offset dihitung di awal agar sesuai IFD.
        var cursor = ifdOffset + 2 + entryCount * 12 + 4
        val bpsOffset = cursor; cursor += 6
        val xResolutionOffset = cursor; cursor += 8
        val yResolutionOffset = cursor; cursor += 8
        val softwareOffset = cursor; cursor += softwareBytes.size
        val stripOffsetsOffset = cursor; cursor += stripCount * 4
        val stripByteCountsOffset = cursor; cursor += stripCount * 4
        val iccOffset = if (icc != null) {
            val off = cursor
            cursor += icc.size
            off
        } else 0
        val pixelOffset = cursor
        val total = pixelOffset + pixelCount * 6

        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        // Header TIFF
        buffer.put(0x49.toByte()) // "II" = little-endian
        buffer.put(0x49.toByte())
        buffer.putShort(42)
        buffer.putInt(ifdOffset)

        // IFD (Baseline TIFF 6.0)
        buffer.putShort(entryCount.toShort())
        putEntry(buffer, 256, 4, 1, width)                      // ImageWidth (LONG)
        putEntry(buffer, 257, 4, 1, height)                     // ImageLength (LONG)
        putEntry(buffer, 258, 3, 3, bpsOffset)                  // BitsPerSample -> 16,16,16
        putEntry(buffer, 259, 3, 1, 1)                          // Compression = none
        putEntry(buffer, 262, 3, 1, 2)                          // Photometric = RGB
        putEntry(buffer, 273, 4, stripCount, stripOffsetsOffset) // StripOffsets
        putEntry(buffer, 277, 3, 1, 3)                          // SamplesPerPixel
        putEntry(buffer, 278, 3, 1, rowsPerStrip)               // RowsPerStrip
        putEntry(buffer, 279, 4, stripCount, stripByteCountsOffset) // StripByteCounts
        putEntry(buffer, 284, 3, 1, 1)                          // PlanarConfiguration = chunky
        putEntry(buffer, 282, 5, 1, xResolutionOffset)          // XResolution (RATIONAL, 300/1)
        putEntry(buffer, 283, 5, 1, yResolutionOffset)          // YResolution (RATIONAL, 300/1)
        putEntry(buffer, 296, 3, 1, 2)                          // ResolutionUnit = inch
        putEntry(buffer, 305, 2, softwareBytes.size, softwareOffset) // Software (ASCII)
        if (icc != null) {
            putEntry(buffer, 34675, 7, icc.size, iccOffset)     // ICC Profile (tanpa profil, Adobe RGB/P3 salah dibaca aplikasi lain)
        }
        buffer.putInt(0)                                        // next IFD = none

        // BitsPerSample values
        buffer.putShort(16)
        buffer.putShort(16)
        buffer.putShort(16)

        // Resolusi fisik
        buffer.putInt(RESOLUTION_DPI)
        buffer.putInt(1)
        buffer.putInt(RESOLUTION_DPI)
        buffer.putInt(1)

        // Nama software
        buffer.put(softwareBytes)

        // Offset & ukuran tiap strip (strip disusun berurutan)
        repeat(stripCount) { i ->
            buffer.putInt(pixelOffset + i * rowsPerStrip * bytesPerRow)
        }
        repeat(stripCount) { i ->
            val rows = minOf(rowsPerStrip, height - i * rowsPerStrip)
            buffer.putInt(rows * bytesPerRow)
        }

        if (icc != null) {
            buffer.put(icc)
        }

        check(buffer.position() == pixelOffset) { "TIFF layout mismatch: ${buffer.position()} != $pixelOffset" }

        // Pixel data (16-bit, urutan RGB). Input dari OpenCV berformat BGR,
        // jadi kanal dibalik per piksel agar warna merah/biru tidak tertukar di TIFF.
        var src = 0
        val limit = pixelCount * 3
        val scale = 65535f / 255f
        while (src + 2 < limit) {
            val r = floatRgb[src + 2]
            val g = floatRgb[src + 1]
            val b = floatRgb[src]
            buffer.putShort((r.coerceIn(0f, 255f) * scale).roundToInt().toShort())
            buffer.putShort((g.coerceIn(0f, 255f) * scale).roundToInt().toShort())
            buffer.putShort((b.coerceIn(0f, 255f) * scale).roundToInt().toShort())
            src += 3
        }
        return buffer.array()
    }

    private fun putEntry(buffer: ByteBuffer, tag: Int, type: Int, count: Int, valueOrOffset: Int) {
        buffer.putShort(tag.toShort())
        buffer.putShort(type.toShort())
        buffer.putInt(count)
        buffer.putInt(valueOrOffset)
    }
}
