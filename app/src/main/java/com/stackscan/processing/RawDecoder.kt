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

object RawDecoder {

    init {
        System.loadLibrary("rawdecoder")
    }

    external fun decodeRgba(data: ByteArray, size: IntArray): ByteArray?

    /** Decode RAW ke RGB 16-bit (3 kanal, little-endian, 0..65535) agar bit dinamis tidak dibuang. */
    external fun decodeRgb16(data: ByteArray, size: IntArray): ByteArray?
}
