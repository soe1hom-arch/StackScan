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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapLoader {

    private val RAW_EXTENSIONS = listOf(
        "dng", "nef", "arw", "cr2", "cr3", "orf", "pef", "rw2",
        "raf", "srw", "iiq", "x3f", "raw",
    )

    fun loadBitmaps(context: Context, uris: List<Uri>, maxDim: Int): List<Bitmap> =
        uris.map { loadBitmap(context, it, maxDim) }

    fun loadBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap {
        return if (isRaw(uri)) {
            loadRaw(context, uri, maxDim)
        } else if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (targetW, targetH) = scaledSize(info.size.width, info.size.height, maxDim)
                decoder.setTargetSize(targetW, targetH)
            }
        } else {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(options.outWidth, options.outHeight, maxDim)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: error("Tidak dapat membaca gambar.")
            applyExifRotation(context, uri, decoded)
        }
    }

    fun isRaw(uri: Uri): Boolean {
        val path = uri.lastPathSegment?.lowercase() ?: return false
        return RAW_EXTENSIONS.any { path.endsWith(it) }
    }

    fun readBytes(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    /** Sudut rotasi dari tag EXIF Orientation (0/90/180/270). */
    fun exifRotationDegrees(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (t: Throwable) {
        0
    }

    private fun loadRaw(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Tidak dapat membaca file RAW.")
        val size = IntArray(2)
        val rgba = RawDecoder.decodeRgba(data, size)
            ?: error("Gagal memproses RAW/DNG. Format mungkin belum didukung.")
        if (size[0] <= 0 || size[1] <= 0) error("Ukuran gambar RAW tidak valid.")

        val decoded = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        decoded.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))

        val (targetW, targetH) = scaledSize(size[0], size[1], maxDim)
        val oriented = applyExifRotation(context, uri, decoded)
        if (targetW == oriented.width && targetH == oriented.height) return oriented
        val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        oriented.recycle()
        return scaled
    }

    private fun scaledSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        if (width <= maxDim && height <= maxDim) return width to height
        val scale = maxDim.toFloat() / max(width, height)
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val rotation = exifRotationDegrees(context, uri)
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
