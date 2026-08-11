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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object ImageSaver {

    private const val FOLDER = "StackScan"
    private val nameCounter = AtomicInteger(0)

    // Millis + counter monotonik: nama tetap terbaca tapi tidak bertabrakan
    // bila beberapa hasil disimpan beruntun dalam milidetik yang sama.
    private fun uniqueName(ext: String): String {
        val seq = nameCounter.incrementAndGet() and 0xFFFF
        return "StackScan_${System.currentTimeMillis()}_$seq.$ext"
    }

    fun save(context: Context, bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName("jpg"))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Gagal menyimpan gambar.")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    error("Gagal menulis gambar.")
                }
            } ?: error("Gagal membuka output.")
        } catch (t: Throwable) {
            // Jangan tinggalkan entri MediaStore yang setengah jadi.
            resolver.delete(uri, null, null)
            throw t
        }

        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    fun saveTiff(context: Context, bytes: ByteArray, width: Int, height: Int): Uri {
        val fileName = uniqueName("tif")
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/tiff")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.WIDTH, width)
                put(MediaStore.Images.Media.HEIGHT, height)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Gagal menyimpan TIFF.")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                } ?: error("Gagal membuka output TIFF.")
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }

        // API 24-28: tanpa permission tambahan, simpan di folder aplikasi
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val file = File(dir, fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }
}
