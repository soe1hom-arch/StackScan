#!/usr/bin/env bash
# Copyright (C) 2026 soe1hom-arch (https://github.com/soe1hom-arch)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -euo pipefail

# Build librawdecoder.so (LibRaw + JNI) untuk arm64-v8a & armeabi-v7a.
# Menghasilkan file di app/src/main/jniLibs/<abi>/librawdecoder.so.
# Prasyarat: clang alat aarch64 native, NDK Linux (sumber), lld.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK_PREBUILT="${ANDROID_NDK_PREBUILT:-/opt/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64}"
SYS="$NDK_PREBUILT"
CPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/app/src/main/jniLibs"

SRCS=$(find "$CPP/libraw/src" -name '*.cpp' ! -name '*_ph.cpp')

build_abi() {
    local target="$1"
    local dest="$2"
    local extra_cflags="${3:-}"
    local libdir="$4"
    mkdir -p "$OUT/$dest"
    clang++ -target "$target" -D__ANDROID_API__=24 -O2 -std=c++17 $extra_cflags \
        -fPIC -shared --sysroot="$SYS/sysroot" -resource-dir="$RT/lib/clang/17" \
        -I "$CPP" -I "$CPP/libraw" -I "$SYS/sysroot/usr/include/c++/v1" \
        -stdlib=libc++ -fvisibility=hidden -DLIBRAW_NOTHREADS -rtlib=compiler-rt \
        $SRCS "$CPP/rawdecoder.cpp" \
        -L "$SYS/sysroot/usr/lib/$libdir" -Wl,-Bdynamic -l:libc.so -l:libdl.so -l:libm.so -lc++ \
        -fuse-ld=lld -o "$OUT/$dest/librawdecoder.so"
}

build_abi aarch64-linux-android24 arm64-v8a "" aarch64-linux-android
build_abi armv7a-linux-androideabi24 armeabi-v7a "-march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16" arm-linux-androideabi

echo "OK: librawdecoder.so dibuat untuk arm64-v8a & armeabi-v7a."
