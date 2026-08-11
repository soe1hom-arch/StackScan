#!/usr/bin/env python3
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
"""Buat profil ICC (sRGB, Adobe RGB 1998, Display P3) untuk disematkan ke TIFF.

sRGB  -> profil bawaan littlecms via Pillow (kalau tersedia).
Adobe RGB & Display P3 -> generator ICC v2 matrix-shaper (D65->D50, Bradford),
  TRC AdobeRGB = gamma 2.19921875, TRC P3 = kurva sRGB (sampled 1024).
Validasi akhir memakai lcms (Pillow ImageCms).
"""
import io
import os
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "icc")


def pad4(b: bytes) -> bytes:
    return b + b"\x00" * ((4 - len(b) % 4) % 4)


def text_description(s: str) -> bytes:
    asc = s.encode("utf-8")
    return b"desc" + struct.pack(">I", 0) + (
        pad4(struct.pack(">I", len(asc)) + asc)
        + struct.pack(">II", 0, 0)          # Unicode: lang 0, count 0
        + struct.pack(">I", 0)              # ScriptCode lang
        + b"\x00"                           # ScriptCode count
        + b"\x00" * 3                       # pad
    )


def xyz_raw(x: float, y: float, z: float) -> bytes:
    return struct.pack(">iii", int(round(x * 65536)), int(round(y * 65536)), int(round(z * 65536)))


def xyz_number(x: float, y: float, z: float) -> bytes:
    return b"XYZ " + struct.pack(">I", 0) + xyz_raw(x, y, z)


def curv_gamma(gamma: float) -> bytes:
    return b"curv" + struct.pack(">I", 0) + struct.pack(">II", 1, int(round(gamma * 256)))


def curv_srgb_samples() -> bytes:
    n = 1024
    out = b"curv" + struct.pack(">I", 0) + struct.pack(">I", n)
    for i in range(n):
        v = i / (n - 1)
        if v <= 0.0031308:
            e = 12.92 * v
        else:
            e = 1.055 * (v ** (1.0 / 2.4)) - 0.055
        out += struct.pack(">H", int(round(e * 65535)))
    return out


def build_profile(desc: str, cmm: bytes, gamut: str, matrix: tuple) -> bytes:
    """matrix = (rXYZ, gXYZ, bXYZ), masing-masing (x,y,z) relatif D50."""
    rXYZ, gXYZ, bXYZ = matrix
    wtpt_illuminant = xyz_raw(0.9642, 1.0, 0.8249)
    wtpt = xyz_number(0.9642, 1.0, 0.8249)

    tags = [
        (b"desc", text_description(desc)),
        (b"cprt", text_description("Public Domain / generated for StackScan")),
        (b"wtpt", wtpt),
        (b"rXYZ", xyz_number(*rXYZ)),
        (b"gXYZ", xyz_number(*gXYZ)),
        (b"bXYZ", xyz_number(*bXYZ)),
        (b"rTRC", curv_gamma(563 / 256) if gamut == "adobe" else curv_srgb_samples()),
        (b"gTRC", curv_gamma(563 / 256) if gamut == "adobe" else curv_srgb_samples()),
        (b"bTRC", curv_gamma(563 / 256) if gamut == "adobe" else curv_srgb_samples()),
    ]

    header_len = 128
    tag_table_len = 4 + 12 * len(tags)
    offset = header_len + tag_table_len
    body = b""
    table_entries = []
    for sig, payload in tags:
        aligned = pad4(payload)
        table_entries.append(struct.pack(">III", int.from_bytes(sig, "big"), offset, len(payload)))
        body += aligned
        offset += len(aligned)

    header = bytearray(header_len)
    size = header_len + tag_table_len + len(body)
    header[0:4] = struct.pack(">I", size)
    header[4:8] = cmm
    header[8:12] = b"\x02\x10\x00\x00"          # v2.1
    header[12:16] = b"mntr"
    header[16:20] = b"RGB "
    header[20:24] = b"XYZ "
    header[24:36] = struct.pack(">HHHHHH", 2020, 1, 1, 0, 0, 0)
    header[36:40] = b"acsp"
    header[40:44] = b"APPL"
    header[44:48] = struct.pack(">I", 0)
    header[48:52] = struct.pack(">I", 0)
    header[52:56] = struct.pack(">I", 0)
    header[56:64] = struct.pack(">Q", 0)
    header[64:68] = struct.pack(">I", 0)        # perceptual
    header[68:80] = wtpt_illuminant
    header[80:84] = struct.pack(">I", 0)
    return bytes(header) + struct.pack(">I", len(tags)) + b"".join(table_entries) + body


def srgb_via_pillow() -> bytes:
    from PIL import ImageCms
    return ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()


PROFILES = {
    "sRGB.icc": srgb_via_pillow,
    "AdobeRGB1998.icc": lambda: build_profile(
        "Adobe RGB (1998)", b"ADBE", "adobe",
        (
            (0.6098404, 0.3111580, 0.0194727),
            (0.2052953, 0.6256388, 0.0608777),
            (0.1491862, 0.0632076, 0.7445829),
        ),
    ),
    "DisplayP3.icc": lambda: build_profile(
        "Display P3", b"appl", "p3",
        (
            (0.5151464, 0.2412004, -0.0010501),
            (0.2920100, 0.6922225, 0.0418786),
            (0.1571393, 0.0665771, 0.7842765),
        ),
    ),
}


def validate(data: bytes) -> str:
    from PIL import ImageCms
    prof = ImageCms.ImageCmsProfile(io.BytesIO(data))
    return ImageCms.getProfileDescription(prof)


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, fn in PROFILES.items():
        data = fn()
        assert len(data) == int.from_bytes(data[0:4], "big"), f"{name}: size header tidak cocok"
        assert data[36:40] == b"acsp", f"{name}: bukan profil ICC (acsp?)"
        try:
            desc = validate(data)
        except Exception as e:
            print(f"{name}: VALIDASI GAGAL -> {type(e).__name__}: {e}", file=sys.stderr)
            return 1
        path = os.path.join(OUT_DIR, name)
        with open(path, "wb") as f:
            f.write(data)
        print(f"OK  {name}  {len(data):6d} bytes  desc={desc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
