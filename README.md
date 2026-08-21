# StackScan

**Multi-frame photo stacking for Android** — aligns and stacks multiple exposures into a single clean, sharp, detailed image. 100% offline, no data leaves your device.

## Highlights

- **Sequator-quality pipeline** — per-frame polynomial background extraction, percentile histogram stretch, post-stack gradient removal, background neutralization.
- **5 stacking algorithms** — Real (kappa-sigma), Lighten, Median, Trails, Align only.
- **Rotation-tolerant star alignment** — Lowe's ratio matching + RANSAC affine with ECC fallback.
- **Pro streaming mode** — unlimited frames, constant memory, RAW/DNG support.
- **Dark & flat frame calibration** — applied before alignment in all paths.
- **16-bit RAW/DNG** — native 16-bit decode, not truncated to 8-bit.
- **Auto sky detection** — per-pixel sky mask, ground freeze, vignette correction, light pollution reduction, hot pixel removal, star color enhancement.
- **Professional output** — JPG + 16-bit TIFF with ICC profiles (sRGB / Adobe RGB / Display P3), 300 DPI, HDR compositing, white balance (2700–10000 K).
- **Presets** — General, Astro, Deep-sky, Sequator, Manual, and custom.
- **Background processing** — WorkManager with progress notifications.

## Quick Start

1. Pick **2+ photos** of the same scene from your gallery.
2. Choose a preset in **Settings** (or leave defaults).
3. Tap **Stack & Save** → result saved to `Pictures/StackScan`.

## Build

```bash
# Prerequisites: JDK 17, Android SDK, NDK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Signing credentials are read from environment variables (`STACKSCAN_STORE_PASSWORD`, `STACKSCAN_KEY_ALIAS`, `STACKSCAN_KEY_PASSWORD`). Keystore at `../stackscan-release.keystore` (git-ignored).

## Architecture

```
app/src/main/
├── assets/licenses/          # Third-party license texts
├── java/com/stackscan/
│   ├── MainActivity.kt       # Entry point, photo picker, OpenCV init
│   ├── ui/                   # StackScreen, SettingsScreen, StackViewModel
│   ├── processing/
│   │   ├── ImageStacker.kt   # Core pipeline: align → stack → post-process → output
│   │   ├── BitmapLoader.kt   # Decode, downscale, EXIF orientation
│   │   ├── ImageSaver.kt     # Gallery save (JPG + TIFF)
│   │   ├── RawDecoder.kt     # 16-bit RAW via LibRaw JNI
│   │   └── TiffEncoder.kt    # 16-bit TIFF encoder (multi-strip + ICC)
│   └── work/StackWorker.kt   # Background stacking (WorkManager)
├── cpp/
│   ├── rawdecoder.cpp        # JNI bridge
│   └── libraw/               # LibRaw vendor (unmodified)
scripts/
├── build-raw-libs.sh         # Build librawdecoder.so
└── gen_icc.py                # ICC profile generator
```

## Tech Stack

| Component | Technology | License |
|---|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) | Apache 2.0 |
| Image Processing | OpenCV 4.13 | Apache 2.0 |
| RAW Decode | LibRaw 0.21.3 (NDK/JNI) | LGPL 2.1 / CDDL 1.0 |
| Background Tasks | WorkManager | Apache 2.0 |
| Language | Kotlin 2.0 | Apache 2.0 |

## Changelog

**v9.1** — Sequator-quality pipeline: per-frame background extraction, 5σ star detection, percentile histogram stretch, post-stack gradient removal, background neutralization. Presets rebalanced.

**v9.0** — Native 16-bit RAW, dark/flat calibration before warp, rotation-tolerant star alignment, ICC profiles, stream-save align-only, Apache 2.0 license.

## Limitations

- Needs 2+ photos of the same scene — stacking cannot invent missing detail.
- Built-in camera night mode often stacks internally before saving (shifted/ghosted stars). Use RAW or disable night mode for best results.
- 48MP+ RAW is slow — use Fast/HD quality.
- Full/Original quality adapts to device memory (auto-downscale on low-RAM devices).
- Up to 16 frames in batch; 17+ uses streaming mode.

## License

StackScan is licensed under [Apache 2.0](LICENSE). Third-party licenses are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and bundled in the APK.

## Privacy

100% offline. No internet permission, no data collection, no telemetry, no ads. Full details in [PRIVACY.md](PRIVACY.md).
