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

#include <jni.h>

#include <cstdlib>
#include <vector>

#include "libraw/libraw.h"
#include "libraw/libraw_version.h"

namespace {

int decodeRaw(const unsigned char* data, size_t size, std::vector<unsigned char>& outData,
              int& outWidth, int& outHeight, bool keep16) {
    LibRaw processor;
    int ret = processor.open_buffer(data, size);
    if (ret != LIBRAW_SUCCESS) {
        processor.recycle();
        return ret;
    }
    // Keluaran 16-bit asli (default LibRaw = 8-bit; tanpa ini data `image->data`
    // berupa byte 8-bit dan pembacaan unsigned short di bawah jadi salah).
    processor.imgdata.params.output_bps = 16;
    // Matikan rotasi internal LibRaw (dipakai di copy_mem_image via flip_index);
    // orientasi EXIF dipegang sisi aplikasi agar tidak dobel-rotasi.
    processor.imgdata.params.user_flip = 0;
    ret = processor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        processor.recycle();
        return ret;
    }
    ret = processor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        processor.recycle();
        return ret;
    }

    libraw_processed_image_t* image = processor.dcraw_make_mem_image(&ret);
    if (image == nullptr || ret != LIBRAW_SUCCESS) {
        if (image != nullptr) free(image);
        processor.recycle();
        return ret != LIBRAW_SUCCESS ? ret : LIBRAW_UNSPECIFIED_ERROR;
    }
    if (image->type != LIBRAW_IMAGE_BITMAP || image->colors < 3) {
        free(image->data);
        free(image);
        processor.recycle();
        return LIBRAW_UNSPECIFIED_ERROR;
    }

    outWidth = static_cast<int>(image->width);
    outHeight = static_cast<int>(image->height);
    const size_t pixelCount = static_cast<size_t>(outWidth) * static_cast<size_t>(outHeight);

    const unsigned short* src = reinterpret_cast<const unsigned short*>(image->data);
    const int colors = static_cast<int>(image->colors);
    if (keep16) {
        // Pertahankan bit dinamis 16-bit (0..65535) dalam urutan RGB (LE).
        // Konversi ke float baru dilakukan di sisi Kotlin, supaya langit redup
        // (ADU < 256) tidak dibuang oleh operasi >>8 dan TIFF 16-bit benar-benar 16-bit.
        outData.resize(pixelCount * 6);
        for (size_t i = 0; i < pixelCount; ++i) {
            const size_t o = i * 6;
            for (int c = 0; c < 3; ++c) {
                const unsigned short v = src[i * colors + c];
                outData[o + c * 2 + 0] = static_cast<unsigned char>(v & 0xFF);
                outData[o + c * 2 + 1] = static_cast<unsigned char>(v >> 8);
            }
        }
    } else {
        outData.resize(pixelCount * 4);
        for (size_t i = 0; i < pixelCount; ++i) {
            outData[i * 4 + 0] = static_cast<unsigned char>(src[i * colors + 0] >> 8);
            outData[i * 4 + 1] = static_cast<unsigned char>(src[i * colors + 1] >> 8);
            outData[i * 4 + 2] = static_cast<unsigned char>(src[i * colors + 2] >> 8);
            outData[i * 4 + 3] = 255;
        }
    }

    free(image->data);
    free(image);
    processor.recycle();
    return LIBRAW_SUCCESS;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_stackscan_processing_RawDecoder_decodeRgba(JNIEnv* env, jobject thiz, jbyteArray data,
                                                    jintArray outSize) {
    if (env == nullptr || data == nullptr || outSize == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return nullptr;

    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

    std::vector<unsigned char> rgba;
    int width = 0;
    int height = 0;
    const int ret = decodeRaw(bytes.data(), bytes.size(), rgba, width, height, false);
    if (ret != LIBRAW_SUCCESS || width <= 0 || height <= 0) return nullptr;

    jint size[2] = {static_cast<jint>(width), static_cast<jint>(height)};
    env->SetIntArrayRegion(outSize, 0, 2, size);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(rgba.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(rgba.size()),
                            reinterpret_cast<const jbyte*>(rgba.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_stackscan_processing_RawDecoder_decodeRgb16(JNIEnv* env, jobject thiz, jbyteArray data,
                                                     jintArray outSize) {
    if (env == nullptr || data == nullptr || outSize == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return nullptr;

    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

    std::vector<unsigned char> rgb16;
    int width = 0;
    int height = 0;
    const int ret = decodeRaw(bytes.data(), bytes.size(), rgb16, width, height, true);
    if (ret != LIBRAW_SUCCESS || width <= 0 || height <= 0) return nullptr;

    jint size[2] = {static_cast<jint>(width), static_cast<jint>(height)};
    env->SetIntArrayRegion(outSize, 0, 2, size);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(rgb16.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(rgb16.size()),
                            reinterpret_cast<const jbyte*>(rgb16.data()));
    return result;
}
