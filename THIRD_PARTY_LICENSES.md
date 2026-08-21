# Kredit & Lisensi Pihak Ketiga — StackScan

Kode StackScan sendiri dilisensikan **Apache License 2.0** (lihat `LICENSE` dan
`NOTICE`). Bagian di bawah hanya mencakup pustaka **pihak ketiga** yang dipakai
StackScan. Semua hak cipta tetap milik pemilik masing-masing; teks lisensi
lengkap ikut ter-distribusi di dalam APK (`assets/licenses/`) dan di repositori ini.

## Daftar teknologi

| Teknologi | Versi | Lisensi | Sumber resmi | Pemegang hak cipta | Dipakai untuk |
|---|---|---|---|---|---|
| OpenCV | 4.13.0 | Apache 2.0 | [opencv.org](https://opencv.org/) | OpenCV team (Intel, Itseez, kontributor) | Alignment ECC, deteksi bintang, blending, sharpen, resize |
| LibRaw | 0.21.3 | LGPL v2.1 / CDDL v1.0 | [libraw.org](https://www.libraw.org/) | LibRaw LLC | Decode RAW/DNG (NEF, ARW, CR2, dst) via JNI |
| Jetpack Compose (Material 3) | BOM 2024.09.03 | Apache 2.0 | [developer.android.com/jetpack/compose](https://developer.android.com/jetpack/compose) | Google LLC | UI |
| AndroidX (Activity, Core, Lifecycle, ExifInterface, icons) | Activity 1.9.2 · Core 1.13.1 · Lifecycle 2.8.6 · ExifInterface 1.3.7 | Apache 2.0 | [developer.android.com/jetpack/androidx](https://developer.android.com/jetpack/androidx) | Google LLC | Dasar aplikasi |
| Kotlin | 2.0.21 | Apache 2.0 | [kotlinlang.org](https://kotlinlang.org/) | JetBrains s.r.o. | Bahasa pemrograman |
| LLVM libc++ (NDK r26) | r26 | Apache 2.0 + LLVM exception | [llvm.org](https://llvm.org/) | LLVM Project | Pustaka C++ standar untuk `librawdecoder.so` |
| Kotlinx Coroutines | 1.8.1 | Apache 2.0 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | JetBrains s.r.o. | Concurrency |
| Gradle | 8.14.2 | Apache 2.0 | [gradle.org](https://gradle.org/) | Gradle Inc. | Build system |
| Android Gradle Plugin | 8.7.3 | Apache 2.0 | [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) | Google LLC | Build system |

## Catatan penting per pustaka

### LibRaw (LGPL v2.1 / CDDL v1.0 — dual license)
- Sumber lengkap LibRaw disertakan di repo: `app/src/main/cpp/libraw/` (tanpa modifikasi).
- File lisensi resmi: `app/src/main/cpp/libraw/LICENSE.LGPL` dan `app/src/main/cpp/libraw/LICENSE.CDDL`.
- Kredit internal LibRaw (dari `app/src/main/cpp/libraw/COPYRIGHT`):
  - `dcraw.c` — Dave Coffin (1997–2018).
  - DCB demosaic & FBDD denoise — Jacek Gozdz (BSD 3-clause).
  - X3F (Foveon) — Roland Karlsson (BSD).
  - Potongan Adobe DNG SDK 1.4 — Adobe Systems (MIT).
- Kode LibRaw dikompilasi **statis** ke dalam `librawdecoder.so` — modul JNI tersendiri yang di-load aplikasi (batas pemisahan tetap ada: Java ⇄ JNI ⇄ LibRaw). Konsekuensinya kewajiban LGPL v2.1 pasal 6 dipenuhi sebagai berikut:
  - **(a) Sumber:** kode sumber LibRaw **utuh & tanpa modifikasi** disertakan di `app/src/main/cpp/libraw/`.
  - **(b) Pemberitahuan:** lisensi LGPL, CDDL, dan kredit internal disertakan di repo dan di dalam APK (`assets/licenses/`).
  - **(c) Relink:** objek/sumber lengkap + `scripts/build-raw-libs.sh` disertakan sehingga pengguna bisa membangun ulang/relink `librawdecoder.so`.

### OpenCV (Apache 2.0)
- AAR Maven tidak menyertakan file lisensi, maka atribusi disertakan di sini dan di `assets/licenses/THIRD_PARTY_NOTICES.txt`.

### Konsep stacking astronomi
- Algoritma yang dipakai (image alignment, kappa-sigma clipping, light pollution reduction, vignette correction, polynomial background extraction, percentile histogram stretch) adalah metode standar astronomi/fotografi yang terdokumentasi luas di ranah publik. StackScan tidak berafiliasi dengan Sequator atau lembaga/instansi mana pun.

## Teks lisensi lengkap (di dalam APK & repo)
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) — `app/src/main/assets/licenses/Apache-2.0.txt`
- [GNU LGPL v2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) — `app/src/main/assets/licenses/LGPL-2.1.txt`
- [CDDL v1.0](https://opensource.org/license/cddl-1-0/) — `app/src/main/assets/licenses/CDDL-1.0.txt`
- Nota gabungan — `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt`

Semua pustaka di atas tetap berlisensi terbuka; penggunaan di StackScan tidak mengubah lisensi pustaka itu sendiri.
