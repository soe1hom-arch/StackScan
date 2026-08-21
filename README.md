# StackScan

Aplikasi Android untuk **stacking foto multi-frame**: ambil atau pilih beberapa foto dari scene yang sama, lalu StackScan menyelaraskan (align) dan menggabungkannya (stack) menjadi satu gambar yang lebih bersih, lebih tajam, dan lebih kaya detail — semua diproses **100% offline di perangkat**. Versi APK saat ini: **9.1** (versionCode 19).

## Fitur

**Stacking — Pipeline Kualitas Sequator**
- **Background extraction per-frame** (polynomial surface fitting) — menghilangkan gradien polusi cahaya **sebelum** deteksi bintang, sehingga alignment jauh lebih presisi.
- **Detektor bintang sensitif** (threshold 5σ) — menemukan bintang redup yang sebelumnya tidak terdeteksi.
- **Percentile histogram stretch** (asimetris + sigmoid midtone boost) — mengganti auto-brightness sederhana; menghasilkan kontras langit yang lebih dramatis seperti Sequator.
- **Gradient removal pasca-stack** — polynomial fitting kedua setelah stacking untuk menghilangkan gradien residual.
- **Background neutralization** — netralisasi warna latar per-channel mencegah color cast.
- 5 algoritma: **Real (kappa-sigma)**, **Lighten**, **Median**, **Trails** (star trails), dan **Align only**.
- Alignment sub-pixel (OpenCV ECC) + **alignment berbasis bintang toleran rotasi** (pencocokan rasio Lowe + RANSAC affine) untuk foto astro, dengan fallback ECC.
- **Mode Pro streaming** untuk 17+ frame, file RAW/DNG, dan kualitas Full (tanpa batas jumlah, memori konstan).
- Kualitas kerja: **Cepat**, **HD**, dan **Full/Asli** (hingga 4096px, menyesuaikan memori perangkat).
- **Dark frames** & **flat frames** opsional — dikoreksi **sebelum** penyelarasan di semua jalur; dark master mempertahankan hot pixel agar pengurangannya tepat.
- **RAW/DNG 16-bit asli** — dibaca dalam 16-bit penuh (bukan dipotong ke 8-bit) dengan orientasi EXIF diterapkan.
- **Deteksi langit otomatis** (sky mask per-piksel), kunci tanah (freeze ground), koreksi vignette, reduksi polusi cahaya, kecerahan langit, hapus hot pixels, perkuat warna bintang, dan normalisasi eksposur antar-frame.

**Output & pengaturan**
- Simpan **JPG** + **TIFF 16-bit** (multi-strip, profil ICC sesuai ruang warna, resolusi 300 DPI), kecerahan otomatis, merge piksel 2×2, komposisi **HDR** berbasis luminance, ketajaman.
- **Keseimbangan warna** (slider tint 2700–10000 K) dan **ruang warna** (sRGB / Adobe RGB / Display P3) — TIFF menyertakan profil ICC yang sesuai.
- Preset **Umum** (makro), **Astro** (bintang, kappa-sigma + auto brightness), **Gelap (Deep-sky)** (objek samar, LPR agresif), **Sequator** (lighten/ensemble, auto brightness, tanpa koreksi tambahan), **Manual**, dan preset kustom; semua setelan tersimpan otomatis antar sesi.
- **Riwayat hasil**: 50 hasil terakhir dengan thumbnail, statistik, dan akses cepat.
- Stacking berjalan di latar belakang (WorkManager) dengan notifikasi progres — boleh keluar aplikasi atau mematikan layar.

## Cara pakai

1. Buka aplikasi, pilih **Pilih Foto** dari galeri.
2. Pilih minimal **2 foto** dari scene yang sama (makin banyak, makin bersih hasilnya).
3. Atur preset/algoritma/kualitas di **Setelan** (opsional; preset sudah optimal untuk tiap skenario).
4. Tekan **Stack & Simpan** — hasil otomatis tersimpan ke galeri `Pictures/StackScan`.

## Build dari sumber

Prasyarat: JDK 17, Android SDK, dan NDK.

```bash
./gradlew :app:assembleDebug
```

APK dihasilkan di `app/build/outputs/apk/debug/app-debug.apk`.

**Release signing.** Kredensial penandatanganan dibaca dari **variabel
lingkungan** (dianjurkan) atau `gradle.properties` lokal — jangan simpan
kredensial di file yang ikut ter-commit: `STACKSCAN_STORE_PASSWORD`,
`STACKSCAN_KEY_ALIAS`, `STACKSCAN_KEY_PASSWORD`. Keystore diletakkan di
`../stackscan-release.keystore` (relatif ke folder proyek) dan tidak ikut
di-commit (lihat `.gitignore`).

## Struktur proyek

```
app/src/main/
├── assets/
│   └── licenses/                  # teks lisensi pihak ketiga (dipakai dialog Tentang)
├── java/com/stackscan/
│   ├── MainActivity.kt            # entry point + photo picker + init OpenCV
│   ├── ui/
│   │   ├── StackScreen.kt         # layar utama, riwayat hasil, dialog Tentang/Panduan
│   │   ├── SettingsScreen.kt      # setelan (Algoritma / Koreksi / Output)
│   │   └── StackViewModel.kt      # state, preset, persistensi
│   ├── processing/
│   │   ├── ImageStacker.kt        # inti: align → stack → koreksi → output
│   │   ├── BitmapLoader.kt        # decode + downscale (+ orientasi EXIF RAW)
│   │   ├── ImageSaver.kt          # simpan ke galeri (JPG + TIFF)
│   │   ├── RawDecoder.kt          # decode RAW 16-bit via JNI LibRaw
│   │   └── TiffEncoder.kt         # encoder TIFF 16-bit (multi-strip + ICC)
│   └── work/
│       └── StackWorker.kt         # stacking latar belakang (WorkManager)
├── cpp/
│   ├── rawdecoder.cpp             # JNI milik StackScan
│   └── libraw/                    # LibRaw vendor (utuh, tanpa modifikasi)
└── res/
scripts/
├── build-raw-libs.sh              # build librawdecoder.so (NDK)
└── gen_icc.py                     # generator profil ICC untuk TIFF
```

## Teknologi

- **Kotlin + Jetpack Compose** (Material 3) — UI.
- **OpenCV 4.13** — alignment ECC, deteksi bintang, koreksi & blending.
- **LibRaw 0.21.3 + NDK (JNI)** — decode RAW/DNG 16-bit di perangkat.
- **WorkManager** — proses stacking di latar belakang.
- Min SDK 24 (Android 7+), target SDK 34.

## Kredit & lisensi

Daftar lengkap pustaka pihak ketiga, versi, lisensi, dan pemegang hak cipta ada di **[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)**; teks lisensi juga ikut ter-distribusi di dalam APK (menu **Tentang & Lisensi**).

- [OpenCV](https://opencv.org/) — Apache 2.0
- [LibRaw](https://www.libraw.org/) — LGPL v2.1 / CDDL v1.0 (sumber utuh: `app/src/main/cpp/libraw/`, tanpa modifikasi)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Apache 2.0
- [Kotlin](https://kotlinlang.org/) — Apache 2.0

Metode pemrosesan (alignment, kappa-sigma clipping, reduksi polusi cahaya, koreksi vignette) adalah teknik standar astronomi & fotografi yang terdokumentasi luas di ranah publik. StackScan tidak berafiliasi dengan lembaga/instansi mana pun.

## Lisensi proyek

Kode StackScan sendiri dilisensikan di bawah **Apache License 2.0** — lihat
[`LICENSE`](LICENSE) dan [`NOTICE`](NOTICE). Lisensi pihak ketiga tidak berubah
dan tetap milik pemegang hak ciptanya masing-masing (lihat
`THIRD_PARTY_LICENSES.md`).

## Privasi & data

StackScan memproses foto **100% offline di perangkat**: tidak ada izin
`INTERNET`, tidak ada pengumpulan data, telemetri, akun, atau iklan. Foto hanya
diproses lokal dan hasilnya disimpan ke `Pictures/StackScan`. Pernyataan
lengkap: [`PRIVACY.md`](PRIVACY.md).

## Riwayat perubahan

**9.1 (versionCode 19)** — pipeline kualitas Sequator:
- **Background extraction per-frame** (polynomial surface fitting quadratic) menghilangkan gradien polusi cahaya sebelum deteksi bintang — alignment presisi naik drastis.
- **Detektor bintang threshold diturunkan** dari 8σ ke 5σ, floor 3px — bintang redup yang sebelumnya tidak terdeteksi sekarang ditemukan.
- **Percentile histogram stretch** (asimetris 0.5%–99.5% + sigmoid midtone boost) mengganti autoBrightness lama — kontras langit mendekati Sequator.
- **Gradient removal pasca-stack** — polynomial fitting kedua setelah stacking untuk menghilangkan gradien residual.
- **Background neutralization** — netralisasi warna latar per-channel mencegah color cast dari polusi cahaya.
- Pipeline stacking diperbarui: background extraction → star detection → alignment → stacking → percentile stretch → gradient removal → neutralization → color space.
- Preset disesuaikan: ASTRO nyalakan vignette/LPR/auto brightness, DEEP_SKY tambah LPR agresif + sky brightness, SEQUATOR pakai lighten + auto brightness.

**9.0 (versionCode 18)** — pembaruan besar engine & tata kelola proyek:
- RAW/DNG dibaca **16-bit asli** (sebelumnya terpotong ke 8-bit) dengan orientasi EXIF diterapkan.
- Kalibrasi dark/flat diterapkan **sebelum warp** di semua jalur; dark master mempertahankan hot pixel.
- Alignment bintang **toleran rotasi** (uji rasio Lowe + deteksi bintang adaptif) dengan fallback ECC.
- Hot pixel adaptif, HDR berbasis luminance, dan kappa-sigma dengan sigma konsisten.
- TIFF: profil ICC (sRGB/Adobe RGB/P3), **multi-strip**, resolusi 300 DPI & tag Software; upscale dilewati saat binning 2× aktif.
- Align-only **stream-save** (simpan langsung + recycle — anti-OOM pada frame banyak).
- Konvensi white balance diperjelas sebagai slider tint (bukan suhu fisik cahaya).
- Tata kelola proyek: lisensi **Apache-2.0** (`LICENSE`/`NOTICE`), `PRIVACY.md`, kredit pihak ketiga diperbarui (termasuk LibRaw static-link & LGPL pasal 6), header lisensi di semua file sumber, dialog Tentang lengkap (versi, pengembang, tautan GitHub).

## Keterbatasan

- Butuh minimal 2 foto dari scene yang sama; stacking tidak bisa memunculkan detail yang tidak pernah terekam.
- **Kamera bawaan (night mode)** sering melakukan stacking internal sebelum foto tersimpan, sehingga bintang sudah bergeser/ghosting. Hasil StackScan akan lebih baik dengan foto RAW atau foto tanpa night mode bawaan.
- Proses RAW resolusi sangat besar (48MP+) lebih lambat; gunakan kualitas **Cepat/HD** untuk itu.
- Kualitas **Full/Asli** menyesuaikan kapasitas memori perangkat (resolusi diturunkan otomatis pada perangkat dengan RAM kecil).
- Sampai 16 frame diproses sekaligus (kappa-sigma iteratif penuh / Median); 17+ frame memakai Mode Pro streaming (lebih hemat memori, sedikit lebih lama).
