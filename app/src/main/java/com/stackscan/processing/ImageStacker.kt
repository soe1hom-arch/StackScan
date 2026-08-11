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
import android.net.Uri
import android.util.Log
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class OutputColorSpace(val label: String, val description: String) {
    SRGB("sRGB", "Standar universal \u2014 warna konsisten di semua layar dan galeri."),
    ADOBE_RGB("Adobe RGB", "Gamut lebih luas, merah\u2013hijau lebih kaya \u2014 ideal untuk cetak."),
    DISPLAY_P3("Display P3", "Gamut luas modern \u2014 akurat di layar flagship & Apple."),
}

object ImageStacker {

    private const val TAG = "StackScanEngine"

    const val MAX_FRAMES = 16
    const val MAX_PICKER_IMAGES = 100
    private const val MIN_STARS_COUNT = 4
    // Jarak cari diperlebar + uji rasio tetangga terdekat agar rotasi frame
    // (hingga beberapa derajat di tepi) tetap bisa dicocokkan, bukan langsung jatuh ke ECC.
    private const val MAX_STAR_MATCH_DIST = 150.0
    private const val STAR_MATCH_RATIO = 0.75
    private var iccCache: Map<OutputColorSpace, ByteArray>? = null

    private fun iccAsset(context: Context, name: String): ByteArray = try {
        context.assets.open(name).use { it.readBytes() }
    } catch (t: Throwable) {
        ByteArray(0)
    }

    private fun ensureIccLoaded(context: Context) {
        if (iccCache != null) return
        val profiles = mapOf(
            OutputColorSpace.SRGB to "icc/sRGB.icc",
            OutputColorSpace.ADOBE_RGB to "icc/AdobeRGB1998.icc",
            OutputColorSpace.DISPLAY_P3 to "icc/DisplayP3.icc",
        )
        iccCache = profiles.mapValues { (_, path) -> iccAsset(context, path) }
    }
    data class StackResult(
        val bitmap: Bitmap,
        val usedFrames: Int,
        val tiffBytes: ByteArray?,
        val tiffWidth: Int,
        val tiffHeight: Int,
        /** Jumlah frame align-only yang tersimpan (stream-save; total termasuk result.bitmap). */
        val alignedSavedFrames: Int = 0,
    )

    fun stack(
        context: Context,
        bitmaps: List<Bitmap>,
        darkBitmaps: List<Bitmap>? = null,
        flatBitmaps: List<Bitmap>? = null,
        astroMode: Boolean,
        lightenMode: Boolean,
        medianMode: Boolean,
        upscale2x: Boolean,
        sharpenStrength: Float,
        vignetteCorrection: Boolean,
        vignetteStrength: Float,
        lightPollutionReduction: Boolean,
        lprStrength: Float,
        skyBrightness: Float,
        kappa: Double,
        kappaPasses: Int,
        exposureNormalize: Boolean,
        removeHotPixels: Boolean,
        enhanceStarColor: Boolean,
        starColorStrength: Float,
        freezeGround: Boolean,
        horizonFraction: Float,
        autoSkyMask: Boolean,
        saveTiff: Boolean,
        autoBrightness: Boolean,
        mergePixels: Boolean,
        hdr: Boolean,
        wbTemperatureK: Int = 6500,
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        require(bitmaps.size >= 2) { "Butuh minimal 2 foto untuk stacking." }
        ensureIccLoaded(context)

        val frameMats = ArrayList<Mat>(bitmaps.size)
        val grays = ArrayList<Mat>(bitmaps.size)
        val aligned = ArrayList<Mat>(bitmaps.size)

        var skyMask: Mat? = null
        var alignedReference: Mat? = null
        try {
            var dark8 = darkBitmaps?.takeIf { it.isNotEmpty() }
                ?.let { list ->
                    // Master dark TIDAK boleh di-smooth/hot-removal: hot pixel di
                    // master harus dipertahankan agar pengurangan dark benar-benar
                    // menghilangkannya dari frame light.
                    val darkFloat = buildDarkMatFromBitmaps(list)
                    darkFloat?.let { d ->
                        val d8 = Mat()
                        d.convertTo(d8, CvType.CV_8UC3)
                        d.release()
                        d8
                    }
                }
            var flat8 = flatBitmaps?.takeIf { it.isNotEmpty() }
                ?.let { list ->
                    val flatFloat = buildFlatMatFromBitmaps(list, removeHotPixels)
                    flatFloat?.let { f ->
                        val f8 = Mat()
                        // Flat ternormalisasi (rata-rata = 1.0); skala 255 agar
                        // pembagian Core.divide(a, b, 255.0) = a di area normal.
                        f.convertTo(f8, CvType.CV_8UC3, 255.0)
                        f.release()
                        f8
                    }
                }
            bitmaps.forEach { bmp ->
                var mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                val dark = dark8
                val flat = flat8
                // Kalibrasi hanya diterapkan bila ukuran cocok dengan frame ini;
                // frame yang lebih kecil/berbeda aspek dilewati (bukan crash).
                val darkFits = dark == null || (dark.cols() == mat.cols() && dark.rows() == mat.rows())
                val flatFits = flat == null || (flat.cols() == mat.cols() && flat.rows() == mat.rows())
                if (dark != null && darkFits) {
                    // Kurangi master dark frame (bias sensor) dari tiap frame.
                    Core.subtract(mat, dark, mat)
                    Core.max(mat, Scalar.all(0.0), mat)
                } else if (dark != null) {
                    Log.w(TAG, "Dark frame dilewati untuk satu frame: ukuran ${dark.cols()}x${dark.rows()} != ${mat.cols()}x${mat.rows()}.")
                }
                // Hot pixel dibersihkan SETELAH dark subtraction, jadi spike
                // sensor yang tersisa (bukan bias) yang dihilangkan.
                if (removeHotPixels) mat = removeHotPixels(mat)
                if (flat != null && flatFits) {
                    // Flat-field correction: bagi tiap piksel dengan master flat.
                    Core.divide(mat, flat, mat, 255.0, CvType.CV_8U)
                } else if (flat != null) {
                    Log.w(TAG, "Flat frame dilewati untuk satu frame: ukuran ${flat.cols()}x${flat.rows()} != ${mat.cols()}x${mat.rows()}.")
                }
                frameMats.add(mat)
                val gray = Mat()
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
                grays.add(gray)
            }

            val refIndex = pickReferenceIndex(grays, astroMode)
            if (refIndex != 0) {
                Log.i(TAG, "Frame acuan dipilih: #$refIndex (bukan #0) — berdasarkan kualitas/star count.")
            }
            val reference = frameMats[refIndex]
            val referenceGray = grays[refIndex]
            aligned.add(reference)
            alignedReference = reference

            // Validasi kalibrasi: dark/flat harus kompatibel ukurannya dengan
            // frame acuan. Bila tidak, frame kalibrasi dilewati dengan alasan
            // yang jelas (bukan error diam-diam).
            val refW = reference.cols()
            val refH = reference.rows()
            if (dark8 != null && (dark8.cols() != refW || dark8.rows() != refH)) {
                Log.w(TAG, "Dark frame dilewati: ukuran ${dark8.cols()}x${dark8.rows()} tidak cocok dengan frame ${refW}x$refH.")
                dark8.release()
                dark8 = null
            }
            if (flat8 != null && (flat8.cols() != refW || flat8.rows() != refH)) {
                Log.w(TAG, "Flat frame dilewati: ukuran ${flat8.cols()}x${flat8.rows()} tidak cocok dengan frame ${refW}x$refH.")
                flat8.release()
                flat8 = null
            }
            var usedFrames = 1
            skyMask = if (autoSkyMask && astroMode) buildSkyMask(referenceGray) else null

            val eccCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-4)

            var processed = 0
            for (i in frameMats.indices) {
                if (i == refIndex) continue
                processed++
                onProgress(
                    0.05f + 0.45f * (processed - 1) / (frameMats.size - 1),
                    if (astroMode) {
                        "Menjajarkan bintang ${processed + 1}/${frameMats.size}..."
                    } else {
                        "Menyelaraskan frame ${processed + 1}/${frameMats.size}..."
                    },
                )

                val grayRef = sameSizeAsReference(referenceGray, grays[i])
                val skyWarp = if (astroMode) {
                    starWarp(referenceGray, grayRef)
                        ?: eccWarp(referenceGray, grayRef, Video.MOTION_EUCLIDEAN, eccCriteria, 0.15)
                } else {
                    eccWarp(referenceGray, grayRef, Video.MOTION_AFFINE, eccCriteria, 0.3)
                        ?: eccWarp(referenceGray, grayRef, Video.MOTION_EUCLIDEAN, eccCriteria, 0.15)
                }
                val groundWarp = if (freezeGround && astroMode && skyWarp != null) {
                    eccWarp(referenceGray, grayRef, Video.MOTION_AFFINE, eccCriteria, 0.15)
                } else {
                    null
                }
                if (grayRef !== grays[i]) grayRef.release()

                if (skyWarp != null) {
                    Log.i(TAG, "Frame #$i: ALIGNED ${warpSummary(skyWarp)}")
                    val outSky = Mat()
                    Imgproc.warpAffine(
                        frameMats[i],
                        outSky,
                        skyWarp,
                        reference.size(),
                        Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
                    )
                    val out = if (groundWarp != null) {
                        val outGround = Mat()
                        Imgproc.warpAffine(
                            frameMats[i],
                            outGround,
                            groundWarp,
                            reference.size(),
                            Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
                        )
                        val blended = if (skyMask != null) {
                            blendSkyGroundMasked(outSky, outGround, skyMask)
                        } else {
                            blendSkyGround(outSky, outGround, horizonFraction)
                        }
                        outSky.release()
                        outGround.release()
                        blended
                    } else {
                        outSky
                    }
                    skyWarp.release()
                    groundWarp?.release()
                    aligned.add(out)
                    usedFrames++
                } else {
                    Log.w(TAG, "Frame #$i: DITOLAK — gagal disejajarkan ke acuan #$refIndex.")
                }
            }

            if (usedFrames < 2) {
                error(
                    "Tidak ada frame yang bisa disejajarkan (0 dari ${bitmaps.size} berhasil). " +
                        "Penyebab umum: campuran foto dengan ukuran/rasio berbeda, gambar terlalu gelap, " +
                        "atau blur. Coba pakai foto dari kamera yang sama dan kurangi jumlahnya.",
                )
            }

            // Untuk penyesuaian kecerahan langit, pakai sky mask bila tersedia
            // (gamma hanya menyentuh langit, tanah tetap utuh).
            if (skyMask == null && skyBrightness > 0.001f) {
                skyMask = buildSkyMask(referenceGray)
            }

            onProgress(
                0.55f,
                "Menggabungkan $usedFrames foto (" +
                    when {
                        medianMode -> "median"
                        lightenMode -> "lighten"
                        else -> "kappa-sigma"
                    } + ")...",
            )
            var stacked = when {
                medianMode -> medianStack(aligned, stackingFactors(exposureNormalize, aligned))
                lightenMode -> maxStack(aligned, stackingFactors(exposureNormalize, aligned))
                else -> {
                    val factors = stackingFactors(exposureNormalize, aligned)
                    sigmaClipStack(aligned, factors, kappa, kappaPasses)
                }
            }

            return postProcess(
                stacked, usedFrames, upscale2x, sharpenStrength, vignetteCorrection, vignetteStrength,
                lightPollutionReduction, lprStrength, skyBrightness, enhanceStarColor, starColorStrength,
                saveTiff, autoBrightness, mergePixels, hdr, wbTemperatureK, colorSpace, onProgress, 0.55f,
                skyMask,
            )
        } finally {
            frameMats.forEach { it.release() }
            grays.forEach { it.release() }
            aligned.forEach { if (it !== alignedReference) it.release() }
            darkBitmaps?.forEach { it.recycle() }
            flatBitmaps?.forEach { it.recycle() }
            skyMask?.release()
        }
    }

    fun stackFromUris(
        context: Context,
        uris: List<Uri>,
        darkUris: List<Uri> = emptyList(),
        flatUris: List<Uri> = emptyList(),
        trailsMode: Boolean = false,
        astroMode: Boolean,
        lightenMode: Boolean,
        medianMode: Boolean,
        maxDim: Int,
        upscale2x: Boolean,
        sharpenStrength: Float,
        vignetteCorrection: Boolean,
        vignetteStrength: Float,
        lightPollutionReduction: Boolean,
        lprStrength: Float,
        skyBrightness: Float,
        kappa: Double,
        kappaPasses: Int,
        exposureNormalize: Boolean,
        removeHotPixels: Boolean,
        enhanceStarColor: Boolean,
        starColorStrength: Float,
        freezeGround: Boolean,
        horizonFraction: Float,
        autoSkyMask: Boolean,
        saveTiff: Boolean,
        autoBrightness: Boolean,
        mergePixels: Boolean,
        hdr: Boolean,
        wbTemperatureK: Int = 6500,
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        alignOnly: Boolean = false,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        require(uris.size >= 2) { "Butuh minimal 2 foto untuk stacking." }
        ensureIccLoaded(context)
        // Batas resolusi kerja disesuaikan dengan kapasitas memori perangkat,
        // agar mode Full/Asli tidak langsung OOM (array statistik streaming
        // berukuran O(piksel) dan heap Android terbatas).
        val working = heapSafeMaxDim(maxDim)
        // Mode Median berlaku untuk batch (≤16 frame); di Mode Pro streaming
        // memakai kappa-sigma karena median memerlukan seluruh nilai per piksel.
        val dark = buildDarkMat(context, darkUris, working)
        val flat = buildFlatMat(context, flatUris, working, removeHotPixels)
        return try {
            when {
                alignOnly -> alignOnlyFrames(
                    context = context,
                    uris = uris,
                    maxDim = working,
                    dark = dark,
                    flat = flat,
                    astroMode = astroMode,
                    freezeGround = freezeGround,
                    horizonFraction = horizonFraction,
                    autoSkyMask = autoSkyMask,
                    removeHotPixels = removeHotPixels,
                    onProgress = onProgress,
                )
                trailsMode -> stackTrails(
                    context = context,
                    uris = uris,
                    maxDim = working,
                    dark = dark,
                    flat = flat,
                    exposureNormalize = exposureNormalize,
                    removeHotPixels = removeHotPixels,
                    upscale2x = upscale2x,
                    sharpenStrength = sharpenStrength,
                    vignetteCorrection = vignetteCorrection,
                    vignetteStrength = vignetteStrength,
                    lightPollutionReduction = lightPollutionReduction,
                    lprStrength = lprStrength,
                    skyBrightness = skyBrightness,
                    enhanceStarColor = enhanceStarColor,
                    starColorStrength = starColorStrength,
                    saveTiff = saveTiff,
                    autoBrightness = autoBrightness,
                    mergePixels = mergePixels,
                    hdr = hdr,
                    wbTemperatureK = wbTemperatureK,
                    colorSpace = colorSpace,
                    onProgress = onProgress,
                )
                lightenMode && !medianMode -> stackStreamingLighten(
                    context = context,
                    uris = uris,
                    dark = dark,
                    flat = flat,
                    astroMode = astroMode,
                    maxDim = working,
                    upscale2x = upscale2x,
                    sharpenStrength = sharpenStrength,
                    vignetteCorrection = vignetteCorrection,
                    vignetteStrength = vignetteStrength,
                    lightPollutionReduction = lightPollutionReduction,
                    lprStrength = lprStrength,
                    skyBrightness = skyBrightness,
                    kappa = kappa,
                    kappaPasses = kappaPasses,
                    exposureNormalize = exposureNormalize,
                    removeHotPixels = removeHotPixels,
                    enhanceStarColor = enhanceStarColor,
                    starColorStrength = starColorStrength,
                    freezeGround = freezeGround,
                    horizonFraction = horizonFraction,
                    autoSkyMask = autoSkyMask,
                    saveTiff = saveTiff,
                    autoBrightness = autoBrightness,
                    mergePixels = mergePixels,
                    hdr = hdr,
                    wbTemperatureK = wbTemperatureK,
                    colorSpace = colorSpace,
                    onProgress = onProgress,
                )
                else -> stackStreaming(
                    context = context,
                    uris = uris,
                    dark = dark,
                    flat = flat,
                    astroMode = astroMode,
                    maxDim = working,
                    upscale2x = upscale2x,
                    sharpenStrength = sharpenStrength,
                    vignetteCorrection = vignetteCorrection,
                    vignetteStrength = vignetteStrength,
                    lightPollutionReduction = lightPollutionReduction,
                    lprStrength = lprStrength,
                    skyBrightness = skyBrightness,
                    kappa = kappa,
                    kappaPasses = kappaPasses,
                    exposureNormalize = exposureNormalize,
                    removeHotPixels = removeHotPixels,
                    enhanceStarColor = enhanceStarColor,
                    starColorStrength = starColorStrength,
                    freezeGround = freezeGround,
                    horizonFraction = horizonFraction,
                    autoSkyMask = autoSkyMask,
                    saveTiff = saveTiff,
                    autoBrightness = autoBrightness,
                    mergePixels = mergePixels,
                    hdr = hdr,
                    wbTemperatureK = wbTemperatureK,
                    colorSpace = colorSpace,
                    onProgress = onProgress,
                )
            }
        } finally {
            dark?.release()
            flat?.release()
        }
    }

    private fun stackStreamingLighten(
        context: Context,
        uris: List<Uri>,
        dark: Mat?,
        flat: Mat?,
        astroMode: Boolean,
        maxDim: Int,
        upscale2x: Boolean,
        sharpenStrength: Float,
        vignetteCorrection: Boolean,
        vignetteStrength: Float,
        lightPollutionReduction: Boolean,
        lprStrength: Float,
        skyBrightness: Float,
        kappa: Double,
        kappaPasses: Int,
        exposureNormalize: Boolean,
        removeHotPixels: Boolean,
        enhanceStarColor: Boolean,
        starColorStrength: Float,
        freezeGround: Boolean,
        horizonFraction: Float,
        autoSkyMask: Boolean,
        saveTiff: Boolean,
        autoBrightness: Boolean,
        mergePixels: Boolean,
        hdr: Boolean,
        wbTemperatureK: Int = 6500,
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        val n = uris.size
        val eccCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-4)

        var (reference, referenceGray, refData) =
            loadReferenceFrame(context, uris[0], maxDim, removeHotPixels, dark, flat)
        val width = reference.cols()
        val height = reference.rows()
        val pixelCount = width * height * 3

        onProgress(0.02f, "Frame 1/$n — menyiapkan (lighten)...")
        val maxVal = refData.copyOf()
        val refLum = luminanceOf(refData)
        // refData tidak dipakai lagi setelah frame referensi diproses.
        var usedFrames = 1
        val adjustMask = if (skyBrightness > 0.001f) buildSkyMask(referenceGray) else null

        for (i in 1 until n) {
            onProgress(
                0.02f + 0.45f * (i - 1) / (n - 1),
                "Menggabungkan frame ${i + 1}/$n (lighten)...",
            )
            val aligned = loadAlignedFrame(
                context, uris[i], maxDim, referenceGray, reference.size(), astroMode, eccCriteria,
                removeHotPixels, freezeGround, horizonFraction, autoSkyMask, dark, flat,
            )
            if (aligned != null) {
                val data = FloatArray(pixelCount)
                aligned.get(0, 0, data)
                val factor = if (exposureNormalize) exposureFactorFor(data, refLum) else 1.0
                for (p in 0 until pixelCount) {
                    val v = (data[p] * factor).toFloat()
                    if (v > maxVal[p]) maxVal[p] = v
                }
                usedFrames++
                aligned.release()
            }
        }

        referenceGray.release()
        reference.release()
        refData = FloatArray(0)

        if (usedFrames < 2) {
            error(
                "Tidak ada frame yang bisa disejajarkan (0 dari $n berhasil). " +
                    "Penyebab umum: campuran foto dengan ukuran/rasio berbeda, gambar terlalu gelap, " +
                    "atau blur. Coba pakai foto dari kamera yang sama dan kurangi jumlahnya.",
            )
        }

        val stacked = Mat(height, width, CvType.CV_32FC3)
        stacked.put(0, 0, maxVal)
        val result = postProcess(
            stacked, usedFrames, upscale2x, sharpenStrength, vignetteCorrection, vignetteStrength,
            lightPollutionReduction, lprStrength, skyBrightness, enhanceStarColor, starColorStrength,
            saveTiff, autoBrightness, mergePixels, hdr, wbTemperatureK, colorSpace, onProgress, 0.5f,
            adjustMask,
        )
        adjustMask?.release()
        return result
    }

    private fun stackStreaming(
        context: Context,
        uris: List<Uri>,
        dark: Mat?,
        flat: Mat?,
        astroMode: Boolean,
        maxDim: Int,
        upscale2x: Boolean,
        sharpenStrength: Float,
        vignetteCorrection: Boolean,
        vignetteStrength: Float,
        lightPollutionReduction: Boolean,
        lprStrength: Float,
        skyBrightness: Float,
        kappa: Double,
        kappaPasses: Int,
        exposureNormalize: Boolean,
        removeHotPixels: Boolean,
        enhanceStarColor: Boolean,
        starColorStrength: Float,
        freezeGround: Boolean,
        horizonFraction: Float,
        autoSkyMask: Boolean,
        saveTiff: Boolean,
        autoBrightness: Boolean,
        mergePixels: Boolean,
        hdr: Boolean,
        wbTemperatureK: Int = 6500,
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        val n = uris.size
        val eccCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-4)

        var (reference, referenceGray, refData) =
            loadReferenceFrame(context, uris[0], maxDim, removeHotPixels, dark, flat)
        val width = reference.cols()
        val height = reference.rows()
        val pixelCount = width * height * 3

        onProgress(0.02f, "Frame 1/$n — statistik dasar (pass 1/2)...")
        val mean = refData.copyOf()
        var sumsq = FloatArray(pixelCount)
        val refLum = luminanceOf(refData)
        // refData hanya dibutuhkan di pass 1 (frame 0 masuk lewat mean di atas);
        // di pass 2 frame 0 di-align ulang agar tidak menahan array 200MB+ di heap.
        refData = FloatArray(0)

        var usedFrames = 1
        val adjustMask = if (skyBrightness > 0.001f) buildSkyMask(referenceGray) else null
        for (i in 1 until n) {
            onProgress(
                0.02f + 0.40f * (i - 1) / (n - 1),
                if (astroMode) {
                    "Menjajarkan bintang ${i + 1}/$n (pass 1/2)..."
                } else {
                    "Menyelaraskan frame ${i + 1}/$n (pass 1/2)..."
                },
            )
            val aligned = loadAlignedFrame(
                context, uris[i], maxDim, referenceGray, reference.size(), astroMode, eccCriteria,
                removeHotPixels, freezeGround, horizonFraction, autoSkyMask, dark, flat,
            )
            if (aligned != null) {
                val data = FloatArray(pixelCount)
                aligned.get(0, 0, data)
                val factor = if (exposureNormalize) exposureFactorFor(data, refLum) else 1.0
                usedFrames++
                updateStats(mean, sumsq, data, usedFrames, factor)
                aligned.release()
            }
        }

        if (usedFrames < 2) {
            referenceGray.release()
            reference.release()
            error(
                "Tidak ada frame yang bisa disejajarkan (0 dari $n berhasil). " +
                    "Penyebab umum: campuran foto dengan ukuran/rasio berbeda, gambar terlalu gelap, " +
                    "atau blur. Coba pakai foto dari kamera yang sama dan kurangi jumlahnya.",
            )
        }

        val count = usedFrames.toFloat()

        onProgress(0.45f, "Menggabungkan $usedFrames foto (kappa-sigma streaming, pass 2/2)...")
        val clipSum = FloatArray(pixelCount)
        val clipCount = IntArray(pixelCount)
        // Frame 0 (referensi) ikut di-klip seperti frame lain.
        val first = loadAlignedFrame(
            context, uris[0], maxDim, referenceGray, reference.size(), astroMode, eccCriteria,
            removeHotPixels, freezeGround, horizonFraction, autoSkyMask, dark, flat,
        )
        if (first != null) {
            val data = FloatArray(pixelCount)
            first.get(0, 0, data)
            val factor = if (exposureNormalize) exposureFactorFor(data, refLum) else 1.0
            accumulateClip(clipSum, clipCount, data, mean, sumsq, count, factor, kappa)
            first.release()
        }
        for (i in 1 until n) {
            onProgress(
                0.45f + 0.40f * (i - 1) / (n - 1),
                "Menggabungkan frame ${i + 1}/$n (pass 2/2)...",
            )
            val aligned = loadAlignedFrame(
                context, uris[i], maxDim, referenceGray, reference.size(), astroMode, eccCriteria,
                removeHotPixels, freezeGround, horizonFraction, autoSkyMask, dark, flat,
            )
            if (aligned != null) {
                val data = FloatArray(pixelCount)
                aligned.get(0, 0, data)
                val factor = if (exposureNormalize) exposureFactorFor(data, refLum) else 1.0
                accumulateClip(clipSum, clipCount, data, mean, sumsq, count, factor, kappa)
                aligned.release()
            }
        }

        for (p in 0 until pixelCount) {
            clipSum[p] = if (clipCount[p] > 0) clipSum[p] / clipCount[p] else mean[p]
        }

        sumsq = FloatArray(0)
        referenceGray.release()
        reference.release()

        val stacked = Mat(height, width, CvType.CV_32FC3)
        stacked.put(0, 0, clipSum)
        val result = postProcess(
            stacked, usedFrames, upscale2x, sharpenStrength, vignetteCorrection, vignetteStrength,
            lightPollutionReduction, lprStrength, skyBrightness, enhanceStarColor, starColorStrength,
            saveTiff, autoBrightness, mergePixels, hdr, wbTemperatureK, colorSpace, onProgress, 0.86f,
            adjustMask,
        )
        adjustMask?.release()
        return result
    }

    private class FrameFloat(val width: Int, val height: Int, val data: FloatArray)

    private fun loadFrameFloat(
        context: Context,
        uri: Uri,
        maxDim: Int,
        removeHotPixels: Boolean,
    ): FrameFloat? {
        return try {
            val f = loadFrameMat(context, uri, maxDim, removeHotPixels, null, null) ?: return null
            val width = f.cols()
            val height = f.rows()
            val data = FloatArray(width * height * 3)
            f.get(0, 0, data)
            f.release()
            FrameFloat(width, height, data)
        } catch (t: Throwable) {
            null
        }
    }

    /** Memuat frame sebagai float 0..255. RAW 16-bit dipertahankan (v/257, bukan
     *  >>8) agar bit dinamis langit tidak dibuang; kalibrasi dark/flat diterapkan
     *  SEBELUM warp (konsisten di batch & streaming) supaya interpolasi warp tidak
     *  mengotori koreksi kalibrasi. */
    private fun loadFrameMat(
        context: Context,
        uri: Uri,
        maxDim: Int,
        removeHotPixels: Boolean,
        dark: Mat?,
        flat: Mat?,
    ): Mat? {
        return try {
            var mat: Mat? = null
            if (BitmapLoader.isRaw(uri)) {
                mat = loadRawFloatMat(context, uri, maxDim)
            }
            if (mat == null) {
                val bmp = BitmapLoader.loadBitmap(context, uri, maxDim)
                if (bmp == null) return null
                var m = Mat()
                Utils.bitmapToMat(bmp, m)
                bmp.recycle()
                Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2BGR)
                val f = Mat()
                m.convertTo(f, CvType.CV_32FC3)
                m.release()
                mat = f
            }
            if (dark != null) {
                Core.subtract(mat, dark, mat)
                Core.max(mat, Scalar.all(0.0), mat)
            }
            if (removeHotPixels) {
                val cleaned = removeHotPixels(mat)
                mat.release()
                mat = cleaned
            }
            if (flat != null) {
                Core.divide(mat, flat, mat, 1.0, CvType.CV_32F)
                Core.max(mat, Scalar.all(0.0), mat)
            }
            mat
        } catch (t: Throwable) {
            Log.w(TAG, "Frame gagal dimuat: ${t.message}")
            null
        }
    }

    private fun loadRawFloatMat(context: Context, uri: Uri, maxDim: Int): Mat? {
        val data = BitmapLoader.readBytes(context, uri) ?: return null
        val size = IntArray(2)
        val rgb16 = RawDecoder.decodeRgb16(data, size) ?: return null
        if (size[0] <= 0 || size[1] <= 0) return null
        val width = size[0]
        val height = size[1]
        // Bekerja di Mat CV_16UC3 dulu (hemat memori: resize & rotate 16-bit,
        // konversi ke float baru dilakukan pada resolusi kerja).
        var raw = Mat(height, width, CvType.CV_16UC3)
        val row = ShortArray(width * 3)
        for (r in 0 until height) {
            val base = r * width * 3
            var c = 0
            while (c < width * 3) {
                val b = (rgb16[(base + c) * 2].toInt() and 0xFF) or
                    ((rgb16[(base + c) * 2 + 1].toInt() and 0xFF) shl 8)
                val g = (rgb16[(base + c + 1) * 2].toInt() and 0xFF) or
                    ((rgb16[(base + c + 1) * 2 + 1].toInt() and 0xFF) shl 8)
                val rv = (rgb16[(base + c + 2) * 2].toInt() and 0xFF) or
                    ((rgb16[(base + c + 2) * 2 + 1].toInt() and 0xFF) shl 8)
                row[c] = b.toShort()
                row[c + 1] = g.toShort()
                row[c + 2] = rv.toShort()
                c += 3
            }
            raw.put(r, 0, row)
        }
        // Orientasi EXIF tetap dihormati untuk RAW/DNG.
        val rotation = BitmapLoader.exifRotationDegrees(context, uri)
        if (rotation != 0) raw = rotateMat(raw, rotation)
        val (targetW, targetH) = fitSize(raw.cols(), raw.rows(), maxDim)
        val out = Mat()
        if (targetW != raw.cols() || targetH != raw.rows()) {
            Imgproc.resize(raw, out, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            raw.copyTo(out)
        }
        raw.release()
        val f = Mat()
        // 0..65535 -> 0..255 dengan presisi sub-8-bit: langit ADU < 256 tetap
        // punya nilai, jadi TIFF 16-bit berisi data dinamis nyata.
        out.convertTo(f, CvType.CV_32FC3, 1.0 / 257.0)
        out.release()
        return f
    }

    private fun rotateMat(mat: Mat, degrees: Int): Mat {
        val out = Mat()
        when (degrees) {
            90 -> {
                Core.transpose(mat, out)
                Core.flip(out, out, 1)
            }
            180 -> Core.flip(mat, out, -1)
            270 -> {
                Core.transpose(mat, out)
                Core.flip(out, out, 0)
            }
            else -> return mat
        }
        mat.release()
        return out
    }

    private fun fitSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        if (width <= maxDim && height <= maxDim) return width to height
        val scale = maxDim.toFloat() / max(width, height)
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    private fun buildDarkMat(
        context: Context,
        darkUris: List<Uri>,
        maxDim: Int,
    ): Mat? {
        if (darkUris.isEmpty()) return null
        var sum: FloatArray? = null
        var width = 0
        var height = 0
        var count = 0
        var skipped = 0
        for (uri in darkUris) {
            // Dark frame dibaca apa adanya (tanpa hot-removal) supaya hot pixel
            // ikut ter-ratakan di master dark dan bisa dikurangi dari light.
            val frame = loadFrameFloat(context, uri, maxDim, false) ?: continue
            if (sum == null) {
                width = frame.width
                height = frame.height
                sum = FloatArray(frame.data.size)
            } else if (frame.width != width || frame.height != height) {
                skipped++
                Log.w(TAG, "Dark frame dilewati: ukuran ${frame.width}x${frame.height} berbeda dari ${width}x$height.")
                continue
            }
            val s = sum
            for (p in frame.data.indices) s[p] += frame.data[p]
            count++
        }
        if (count == 0) {
            Log.w(TAG, "Tidak ada dark frame yang valid (dilewati: $skipped).")
            return null
        }
        if (skipped > 0) Log.w(TAG, "$skipped dark frame dilewati karena ukuran berbeda.")
        val s = sum ?: return null
        for (p in s.indices) s[p] /= count
        val dark = Mat(height, width, CvType.CV_32FC3)
        dark.put(0, 0, s)
        return dark
    }

    private fun buildDarkMatFromBitmaps(darkBitmaps: List<Bitmap>): Mat? {
        if (darkBitmaps.isEmpty()) return null
        var sum: FloatArray? = null
        var width = 0
        var height = 0
        var count = 0
        var skipped = 0
        for (bmp in darkBitmaps) {
            try {
                var mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                // Tanpa hot-removal: hot pixel di master dark dipertahankan.
                val f = Mat()
                mat.convertTo(f, CvType.CV_32FC3)
                mat.release()
                val fw = f.cols()
                val fh = f.rows()
                if (sum == null) {
                    width = fw
                    height = fh
                    sum = FloatArray(fw * fh * 3)
                } else if (fw != width || fh != height) {
                    skipped++
                    Log.w(TAG, "Dark frame dilewati: ukuran ${fw}x$fh berbeda dari ${width}x$height.")
                    continue
                }
                val data = FloatArray(width * height * 3)
                f.get(0, 0, data)
                f.release()
                val s = sum
                for (p in data.indices) s[p] += data[p]
                count++
            } catch (t: Throwable) {
                Log.w(TAG, "Dark frame tidak bisa dibaca: ${t.message}")
            }
        }
        if (count == 0) {
            Log.w(TAG, "Tidak ada dark frame yang valid (dilewati: $skipped).")
            return null
        }
        if (skipped > 0) Log.w(TAG, "$skipped dark frame dilewati karena ukuran berbeda.")
        val s = sum ?: return null
        for (p in s.indices) s[p] /= count
        val dark = Mat(height, width, CvType.CV_32FC3)
        dark.put(0, 0, s)
        return dark
    }

    private fun subtractDark(data: FloatArray, darkData: FloatArray?) {
        if (darkData == null) return
        for (p in data.indices) {
            val v = data[p] - darkData[p]
            data[p] = if (v > 0f) v else 0f
        }
    }

    private fun applyFlat(data: FloatArray, flatData: FloatArray?) {
        if (flatData == null) return
        for (p in data.indices) {
            val f = if (flatData[p] > 0.05f) flatData[p] else 0.05f
            data[p] = data[p] / f
        }
    }

    private fun buildFlatMat(
        context: Context,
        flatUris: List<Uri>,
        maxDim: Int,
        removeHotPixels: Boolean,
    ): Mat? {
        if (flatUris.isEmpty()) return null
        var sum: FloatArray? = null
        var width = 0
        var height = 0
        var count = 0
        var skipped = 0
        for (uri in flatUris) {
            val frame = loadFrameFloat(context, uri, maxDim, removeHotPixels) ?: continue
            if (sum == null) {
                width = frame.width
                height = frame.height
                sum = FloatArray(frame.data.size)
            } else if (frame.width != width || frame.height != height) {
                skipped++
                Log.w(TAG, "Flat frame dilewati: ukuran ${frame.width}x${frame.height} berbeda dari ${width}x$height.")
                continue
            }
            val s = sum
            for (p in frame.data.indices) s[p] += frame.data[p]
            count++
        }
        if (count == 0) {
            Log.w(TAG, "Tidak ada flat frame yang valid (dilewati: $skipped).")
            return null
        }
        if (skipped > 0) Log.w(TAG, "$skipped flat frame dilewati karena ukuran berbeda.")
        val s = sum ?: return null
        for (p in s.indices) s[p] /= count
        val flat = Mat(height, width, CvType.CV_32FC3)
        flat.put(0, 0, s)
        return normalizeFlat(flat)
    }

    private fun buildFlatMatFromBitmaps(flatBitmaps: List<Bitmap>, removeHotPixels: Boolean): Mat? {
        if (flatBitmaps.isEmpty()) return null
        var sum: FloatArray? = null
        var width = 0
        var height = 0
        var count = 0
        var skipped = 0
        for (bmp in flatBitmaps) {
            try {
                var mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                if (removeHotPixels) {
                    val cleaned = removeHotPixels(mat)
                    mat.release()
                    mat = cleaned
                }
                val f = Mat()
                mat.convertTo(f, CvType.CV_32FC3)
                mat.release()
                val fw = f.cols()
                val fh = f.rows()
                if (sum == null) {
                    width = fw
                    height = fh
                    sum = FloatArray(fw * fh * 3)
                } else if (fw != width || fh != height) {
                    skipped++
                    Log.w(TAG, "Flat frame dilewati: ukuran ${fw}x$fh berbeda dari ${width}x$height.")
                    continue
                }
                val data = FloatArray(width * height * 3)
                f.get(0, 0, data)
                f.release()
                val s = sum
                for (p in data.indices) s[p] += data[p]
                count++
            } catch (t: Throwable) {
                Log.w(TAG, "Flat frame tidak bisa dibaca: ${t.message}")
            }
        }
        if (count == 0) {
            Log.w(TAG, "Tidak ada flat frame yang valid (dilewati: $skipped).")
            return null
        }
        if (skipped > 0) Log.w(TAG, "$skipped flat frame dilewati karena ukuran berbeda.")
        val s = sum ?: return null
        for (p in s.indices) s[p] /= count
        val flat = Mat(height, width, CvType.CV_32FC3)
        flat.put(0, 0, s)
        return normalizeFlat(flat)
    }

    private fun normalizeFlat(flat: Mat): Mat {
        // Normalisasi: rata-rata luminansi = 1.0 supaya koreksi flat tidak
        // mengubah kecerahan keseluruhan. Nilai sangat rendah dibatasi agar
        // pembagian tidak meledak di sudut gelap vignette.
        val mean = Core.mean(flat)
        val lum = 0.299 * mean.`val`[0] + 0.587 * mean.`val`[1] + 0.114 * mean.`val`[2]
        if (lum > 0.01) {
            Core.divide(flat, Scalar.all(lum), flat, 1.0, CvType.CV_32F)
        }
        Core.max(flat, Scalar.all(0.05), flat)
        return flat
    }

    private fun stackTrails(
        context: Context,
        uris: List<Uri>,
        maxDim: Int,
        dark: Mat?,
        flat: Mat?,
        exposureNormalize: Boolean,
        removeHotPixels: Boolean,
        upscale2x: Boolean,
        sharpenStrength: Float,
        vignetteCorrection: Boolean,
        vignetteStrength: Float,
        lightPollutionReduction: Boolean,
        lprStrength: Float,
        skyBrightness: Float,
        enhanceStarColor: Boolean,
        starColorStrength: Float,
        saveTiff: Boolean,
        autoBrightness: Boolean,
        mergePixels: Boolean,
        hdr: Boolean,
        wbTemperatureK: Int = 6500,
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        val n = uris.size
        val first = loadFrameFloat(context, uris[0], maxDim, removeHotPixels)
            ?: error("Tidak bisa membaca foto pertama.")
        val width = first.width
        val height = first.height
        val darkData = dark?.let { d ->
            FloatArray(width * height * 3).also { d.get(0, 0, it) }
        }
        val flatData = flat?.let { f ->
            FloatArray(width * height * 3).also { f.get(0, 0, it) }
        }
        subtractDark(first.data, darkData)
        applyFlat(first.data, flatData)
        val maxVal = first.data.copyOf()
        val refLum = luminanceOf(first.data)
        var usedFrames = 1

        for (i in 1 until n) {
            onProgress(0.02f + 0.60f * i / n, "Menumpuk jejak bintang ${i + 1}/$n (tanpa alignment)...")
            val frame = loadFrameFloat(context, uris[i], maxDim, removeHotPixels) ?: continue
            if (frame.width != width || frame.height != height) {
                Log.w(TAG, "Frame trails dilewati: ukuran ${frame.width}x${frame.height} berbeda dari ${width}x$height.")
                continue
            }
            val data = frame.data
            subtractDark(data, darkData)
            applyFlat(data, flatData)
            val factor = if (exposureNormalize) exposureFactorFor(data, refLum) else 1.0
            for (p in data.indices) {
                val v = (data[p] * factor).toFloat()
                if (v > maxVal[p]) maxVal[p] = v
            }
            usedFrames++
        }

        if (usedFrames < 2) {
            error("Tidak ada frame yang bisa dipakai untuk star trails. Coba foto lain.")
        }

        val stacked = Mat(height, width, CvType.CV_32FC3)
        stacked.put(0, 0, maxVal)
        return postProcess(
            stacked, usedFrames, upscale2x, sharpenStrength, vignetteCorrection, vignetteStrength,
            lightPollutionReduction, lprStrength, skyBrightness, enhanceStarColor, starColorStrength,
            saveTiff, autoBrightness, mergePixels, hdr, wbTemperatureK, colorSpace, onProgress, 0.65f,
        )
    }

    private fun toneMapHdr(input: Mat): Mat {
        // Komposisi HDR ala Sequator: kompres rentang dinamis pada LUMINANCE
        // (Reinhard: out = x/(1+x)) lalu skala RGB dengan rasio yang sama,
        // agar hue/rasio warna tidak bergeser seperti pemetaan per-kanal.
        val data = FloatArray(input.cols() * input.rows() * 3)
        input.get(0, 0, data)
        for (p in 0 until data.size step 3) {
            val r = data[p + 2].coerceIn(0f, 255f)
            val g = data[p + 1].coerceIn(0f, 255f)
            val b = data[p].coerceIn(0f, 255f)
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val ln = lum / 255f
            val comp = ln / (1f + ln)
            val scale = if (ln > 1e-4f) comp / ln else 1f
            data[p] = (b * scale).coerceIn(0f, 255f)
            data[p + 1] = (g * scale).coerceIn(0f, 255f)
            data[p + 2] = (r * scale).coerceIn(0f, 255f)
        }
        input.put(0, 0, data)
        return input
    }

    private fun alignOnlyFrames(
        context: Context,
        uris: List<Uri>,
        maxDim: Int,
        dark: Mat?,
        flat: Mat?,
        astroMode: Boolean,
        freezeGround: Boolean,
        horizonFraction: Float,
        autoSkyMask: Boolean,
        removeHotPixels: Boolean,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        val n = uris.size
        val eccCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-4)
        val (reference, referenceGray, _) =
            loadReferenceFrame(context, uris[0], maxDim, removeHotPixels, dark, flat)
        val referenceSize = reference.size()
        reference.release()

        var first: Bitmap? = null
        var used = 0
        for (i in 0 until n) {
            onProgress(
                0.02f + 0.90f * i / n,
                "Menyelaraskan frame ${i + 1}/$n (align only)...",
            )
            val aligned = loadAlignedFrame(
                context, uris[i], maxDim, referenceGray, referenceSize, astroMode, eccCriteria,
                removeHotPixels, freezeGround, horizonFraction, autoSkyMask, dark, flat,
            )
            if (aligned != null) {
                val bmp = matToBitmap(aligned)
                aligned.release()
                if (first == null) {
                    // Frame pertama jadi result.bitmap (disimpan StackWorker sebagai hasil utama).
                    first = bmp
                } else {
                    // Frame lain langsung disimpan ke galeri lalu di-recycle. Menahan seluruh
                    // Bitmap di heap rawan OOM bila frame banyak; stream-save memakai memori
                    // konstan berapa pun jumlah frame.
                    ImageSaver.save(context, bmp)
                    bmp.recycle()
                }
                used++
            }
        }
        referenceGray.release()

        if (used < 2) {
            error(
                "Tidak ada frame yang bisa disejajarkan (0 dari $n berhasil). " +
                    "Penyebab umum: campuran foto dengan ukuran/rasio berbeda, gambar terlalu gelap, " +
                    "atau blur. Coba pakai foto dari kamera yang sama dan kurangi jumlahnya.",
            )
        }

        return StackResult(
            bitmap = first ?: error("Tidak ada frame yang tersimpan."),
            usedFrames = used,
            tiffBytes = null,
            tiffWidth = 0,
            tiffHeight = 0,
            alignedSavedFrames = used,
        )
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val c8 = Mat()
        mat.convertTo(c8, CvType.CV_8UC3)
        val bmp = Bitmap.createBitmap(c8.cols(), c8.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(c8, bmp)
        c8.release()
        return bmp
    }

    private fun loadReferenceFrame(
        context: Context,
        uri: Uri,
        maxDim: Int,
        removeHotPixels: Boolean,
        dark: Mat?,
        flat: Mat?,
    ): Triple<Mat, Mat, FloatArray> {
        val reference = loadFrameMat(context, uri, maxDim, removeHotPixels, dark, flat)
            ?: error("Tidak bisa membaca frame acuan.")
        val referenceGray = Mat()
        Imgproc.cvtColor(reference, referenceGray, Imgproc.COLOR_BGR2GRAY)
        val data = FloatArray(reference.cols() * reference.rows() * 3)
        reference.get(0, 0, data)
        return Triple(reference, referenceGray, data)
    }

    private fun loadAlignedFrame(
        context: Context,
        uri: Uri,
        maxDim: Int,
        referenceGray: Mat,
        referenceSize: Size,
        astroMode: Boolean,
        eccCriteria: TermCriteria,
        removeHotPixels: Boolean,
        freezeGround: Boolean,
        horizonFraction: Float,
        autoSkyMask: Boolean,
        dark: Mat?,
        flat: Mat?,
    ): Mat? {
        return try {
            val mat = loadFrameMat(context, uri, maxDim, removeHotPixels, dark, flat) ?: return null
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            val grayRef = sameSizeAsReference(referenceGray, gray)
            val skyWarp = if (astroMode) {
                starWarp(referenceGray, grayRef)
                    ?: eccWarp(referenceGray, grayRef, Video.MOTION_EUCLIDEAN, eccCriteria, 0.15)
            } else {
                eccWarp(referenceGray, grayRef, Video.MOTION_AFFINE, eccCriteria, 0.3)
                    ?: eccWarp(referenceGray, grayRef, Video.MOTION_EUCLIDEAN, eccCriteria, 0.15)
            }
            val groundWarp = if (freezeGround && astroMode && skyWarp != null) {
                eccWarp(referenceGray, grayRef, Video.MOTION_AFFINE, eccCriteria, 0.15)
            } else {
                null
            }
            if (grayRef !== gray) grayRef.release()
            gray.release()
            if (skyWarp == null) {
                mat.release()
                groundWarp?.release()
                return null
            }
            // Mask hanya dibutuhkan bila ada blend langit/tanah (freezeGround);
            // selain itu jangan dibangun agar tidak bocor & tidak buang waktu.
            val skyMask = if (autoSkyMask && astroMode && groundWarp != null) buildSkyMask(referenceGray) else null
            val outSky = Mat()
            Imgproc.warpAffine(
                mat,
                outSky,
                skyWarp,
                referenceSize,
                Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
            )
            val out = if (groundWarp != null) {
                val outGround = Mat()
                Imgproc.warpAffine(
                    mat,
                    outGround,
                    groundWarp,
                    referenceSize,
                    Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
                )
                val blended = if (skyMask != null) {
                    blendSkyGroundMasked(outSky, outGround, skyMask)
                } else {
                    blendSkyGround(outSky, outGround, horizonFraction)
                }
                skyMask?.release()
                outSky.release()
                outGround.release()
                blended
            } else {
                outSky
            }
            skyWarp.release()
            groundWarp?.release()
            mat.release()
            // Kembalikan dalam float agar get(float[]) pada pemanggil aman.
            val outFloat = Mat()
            out.convertTo(outFloat, CvType.CV_32FC3)
            out.release()
            outFloat
        } catch (t: Throwable) {
            Log.w(TAG, "Frame gagal dimuat/disejajarkan: ${t.message}")
            null
        }
    }

    private fun updateStats(mean: FloatArray, sumsq: FloatArray, data: FloatArray, count: Int, factor: Double) {
        for (p in data.indices) {
            val v = (data[p] * factor).toFloat()
            val oldMean = mean[p]
            val delta = v - oldMean
            mean[p] = oldMean + delta / count
            sumsq[p] += delta * (v - mean[p])
        }
    }

    private fun accumulateClip(
        clipSum: FloatArray,
        clipCount: IntArray,
        data: FloatArray,
        mean: FloatArray,
        sumsq: FloatArray,
        count: Float,
        factor: Double,
        kappa: Double,
    ) {
        for (p in data.indices) {
            val v = (data[p] * factor).toFloat()
            val variance = (sumsq[p] / count).coerceAtLeast(0f)
            val sigma = max(8f, sqrt(variance)).toFloat()
            if (abs(v - mean[p]) <= kappa * sigma) {
                clipSum[p] += v
                clipCount[p]++
            }
        }
    }

    private fun luminanceOf(data: FloatArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 2 < data.size) {
            sum += 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]
            i += 3
        }
        val pixels = data.size / 3
        return if (pixels > 0) sum / pixels else 1.0
    }

    private fun exposureFactorFor(data: FloatArray, refLum: Double): Double =
        (refLum / luminanceOf(data).coerceAtLeast(0.001)).coerceIn(0.5, 2.0)

    private fun pickReferenceIndex(grays: List<Mat>, astroMode: Boolean): Int {
        if (grays.size <= 1) return 0
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in grays.indices) {
            val score = if (astroMode) {
                // Astro: jumlah bintang paling penting, tekstur sebagai tie-break.
                findStarPoints(grays[i]).size.toDouble() * 100.0 + textureScore(grays[i])
            } else {
                textureScore(grays[i])
            }
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    private fun textureScore(gray: Mat): Double {
        // Aproksimasi ketajaman: varians intensitas (frame blur -> varians rendah).
        return try {
            val mean = MatOfDouble()
            val stddev = MatOfDouble()
            Core.meanStdDev(gray, mean, stddev)
            val v = stddev.toArray().firstOrNull() ?: 0.0
            mean.release()
            stddev.release()
            v * v
        } catch (t: Throwable) {
            0.0
        }
    }

    private data class WarpStats(
        val score: Double,
        val tx: Double,
        val ty: Double,
        val rotationDeg: Double,
        val scale: Double,
    )

    private fun warpStats(warp: Mat, score: Double = 0.0): WarpStats {
        val a = warp.get(0, 0)[0]
        val b = warp.get(0, 1)[0]
        val c = warp.get(1, 0)[0]
        val d = warp.get(1, 1)[0]
        return WarpStats(
            score = score,
            tx = warp.get(0, 2)[0],
            ty = warp.get(1, 2)[0],
            rotationDeg = Math.toDegrees(atan2(b, a)),
            scale = (hypot(a, b) + hypot(c, d)) / 2.0,
        )
    }

    private fun warpSane(warp: Mat, refW: Int, refH: Int): Boolean {
        val s = warpStats(warp)
        val limit = 0.35 * minOf(refW, refH)
        return abs(s.tx) <= limit && abs(s.ty) <= limit &&
            s.scale in 0.85..1.15 && abs(s.rotationDeg) <= 15.0
    }

    private fun warpSummary(warp: Mat): String {
        val s = warpStats(warp)
        return "(tx=${"%.1f".format(s.tx)}, ty=${"%.1f".format(s.ty)}, rot=${"%.2f".format(s.rotationDeg)}°, scale=${"%.3f".format(s.scale)})"
    }

    private fun sameSizeAsReference(reference: Mat, moving: Mat): Mat {
        if (reference.cols() == moving.cols() && reference.rows() == moving.rows()) return moving
        Log.w(
            TAG,
            "Ukuran frame ${moving.cols()}x${moving.rows()} berbeda dari acuan " +
                "${reference.cols()}x${reference.rows()} — disamakan dulu sebelum disejajarkan.",
        )
        val out = Mat()
        Imgproc.resize(moving, out, reference.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    private fun eccWarp(
        reference: Mat,
        moving: Mat,
        motionType: Int,
        criteria: TermCriteria,
        minScore: Double,
    ): Mat? {
        // OpenCV mensyaratkan warpMatrix ber-tipe CV_32FC1 untuk findTransformECC
        // (CV_64F akan melempar error sehingga ECC selalu gagal).
        val warp = Mat.eye(2, 3, CvType.CV_32F)
        return try {
            val score = Video.findTransformECC(reference, moving, warp, motionType, criteria)
            if (score <= minScore) {
                Log.w(TAG, "ECC ditolak: score $score <= $minScore (confidence rendah).")
                warp.release()
                return null
            }
            if (!warpSane(warp, reference.cols(), reference.rows())) {
                val s = warpStats(warp, score)
                Log.w(
                    TAG,
                    "ECC ditolak: transform tidak masuk akal (tx=${s.tx}, ty=${s.ty}, rot=${s.rotationDeg}, scale=${s.scale}).",
                )
                warp.release()
                return null
            }
            warp
        } catch (t: Throwable) {
            warp.release()
            null
        }
    }

    private fun starWarp(reference: Mat, moving: Mat): Mat? {
        val refStars = findStarPoints(reference)
        val movStars = findStarPoints(moving)
        if (refStars.size < MIN_STARS_COUNT || movStars.size < MIN_STARS_COUNT) {
            Log.w(
                TAG,
                "Star align ditolak: bintang acuan=${refStars.size}, bintang frame=${movStars.size} (min $MIN_STARS_COUNT).",
            )
            return null
        }

        val pairRef = ArrayList<Point>()
        val pairMov = ArrayList<Point>()
        val used = BooleanArray(movStars.size)

        for (refStar in refStars) {
            var bestIndex = -1
            var bestDist = MAX_STAR_MATCH_DIST
            var secondIndex = -1
            var secondDist = MAX_STAR_MATCH_DIST
            for (j in movStars.indices) {
                if (used[j]) continue
                val d = hypot(refStar.x - movStars[j].x, refStar.y - movStars[j].y)
                if (d < bestDist) {
                    secondIndex = bestIndex
                    secondDist = bestDist
                    bestDist = d
                    bestIndex = j
                } else if (d < secondDist) {
                    secondIndex = j
                    secondDist = d
                }
            }
            // Uji rasio tetangga terdekat (Lowe): pasangan ambigu ditolak sehingga
            // rotasi frame beberapa derajat tidak langsung mematahkan pencocokan.
            if (bestIndex >= 0 && (secondIndex < 0 || bestDist < secondDist * STAR_MATCH_RATIO)) {
                used[bestIndex] = true
                pairRef.add(refStar)
                pairMov.add(movStars[bestIndex])
            }
        }

        if (pairRef.size < MIN_STARS_COUNT) return null

        return try {
            val from = MatOfPoint2f(*pairRef.toTypedArray())
            val to = MatOfPoint2f(*pairMov.toTypedArray())
            val inliers = MatOfByte()
            val affine = Calib3d.estimateAffinePartial2D(from, to, inliers, Calib3d.RANSAC, 3.0)
            if (!affine.empty()) {
                val inlierCount = (inliers.toArray().count { it.toInt() != 0 })
                Log.i(
                    TAG,
                    "Star align: pasangan=${pairRef.size}, inliers=$inlierCount " +
                        "(${100.0 * inlierCount / pairRef.size.coerceAtLeast(1)}%).",
                )
                if (!warpSane(affine, reference.cols(), reference.rows())) {
                    val st = warpStats(affine)
                    Log.w(
                        TAG,
                        "Star align ditolak: transform tidak masuk akal (tx=${st.tx}, ty=${st.ty}, rot=${st.rotationDeg}, scale=${st.scale}).",
                    )
                    affine.release()
                    null
                } else {
                    affine
                }
            } else {
                affine.release()
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun findStarPoints(gray: Mat): List<Point> {
        return try {
            val binary = Mat()
            if (gray.depth() == CvType.CV_32F) {
                // Deteksi langsung di float: bintang redup (ADU rendah) tidak
                // hilang saat dikonversi ke 8-bit. Ambang adaptif = mean lokal + 8.
                val mean = Mat()
                Imgproc.blur(gray, mean, Size(51.0, 51.0))
                Core.add(mean, Scalar.all(8.0), mean)
                Core.compare(gray, mean, binary, Core.CMP_GT)
                mean.release()
            } else {
                // Ambang ADAPTIF per-lokal (bukan 50% dari max global): bintang
                // redup di bawah separuh nilai maks ikut terdeteksi.
                Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 51, 8.0)
            }
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val points = ArrayList<Point>()
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area in 2.0..400.0) {
                    val moments = Imgproc.moments(contour)
                    if (moments.m00 > 0.0) {
                        points.add(Point(moments.m10 / moments.m00, moments.m01 / moments.m00))
                    }
                }
                contour.release()
            }
            binary.release()
            hierarchy.release()
            points
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun maxStack(frames: List<Mat>, factors: DoubleArray): Mat {
        val width = frames[0].cols()
        val height = frames[0].rows()
        val size = width * height * 3
        val maxVal = FloatArray(size)
        for (f in frames.indices) {
            val frame = frames[f]
            // Frame berformat CV_8UC3; baca via byte[] lalu konversi ke float
            // (get(float[]) hanya mendukung Mat ber-depth CV_32F).
            val dataBytes = ByteArray(size)
            frame.get(0, 0, dataBytes)
            val factor = factors[f]
            for (p in 0 until size) {
                val v = ((dataBytes[p].toInt() and 0xFF) * factor).toFloat()
                if (v > maxVal[p]) maxVal[p] = v
            }
            frame.release()
        }
        val result = Mat(height, width, CvType.CV_32FC3)
        result.put(0, 0, maxVal)
        return result
    }

    private fun medianStack(frames: List<Mat>, factors: DoubleArray): Mat {
        val width = frames[0].cols()
        val height = frames[0].rows()
        val n = frames.size
        val bytes = ArrayList<ByteArray>(n)
        for (frame in frames) {
            val data = ByteArray(width * height * 3)
            frame.get(0, 0, data)
            bytes.add(data)
            frame.release()
        }
        val values = IntArray(n)
        val output = FloatArray(width * height * 3)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = (row * width + col) * 3
                for (channel in 0..2) {
                    for (f in 0 until n) {
                        val raw = bytes[f][base + channel].toInt() and 0xFF
                        values[f] = (raw * factors[f]).roundToInt()
                    }
                    output[base + channel] = medianOf(values, n).toFloat()
                }
            }
        }

        val result = Mat(height, width, CvType.CV_32FC3)
        result.put(0, 0, output)
        return result
    }

    private fun stackingFactors(exposureNormalize: Boolean, frames: List<Mat>): DoubleArray {
        if (!exposureNormalize) return DoubleArray(frames.size) { 1.0 }
        return exposureFactors(frames)
    }

    private fun exposureFactors(frames: List<Mat>): DoubleArray {
        if (frames.size < 2) return DoubleArray(frames.size) { 1.0 }
        val means = frames.map { luminance(Core.mean(it)) }
        val reference = means[0]
        return DoubleArray(frames.size) { index ->
            if (index == 0) 1.0 else (reference / means[index]).coerceIn(0.5, 2.0)
        }
    }

    private fun luminance(scalar: Scalar): Double {
        val v = scalar.`val`
        return 0.299 * v[0] + 0.587 * v[1] + 0.114 * v[2]
    }

    private fun sigmaClipStack(frames: List<Mat>, factors: DoubleArray, kappa: Double, kappaPasses: Int): Mat {
        val width = frames[0].cols()
        val height = frames[0].rows()
        val n = frames.size
        val bytes = ArrayList<ByteArray>(n)
        for (frame in frames) {
            val data = ByteArray(width * height * 3)
            frame.get(0, 0, data)
            bytes.add(data)
            frame.release()
        }
        val values = IntArray(n)
        val output = FloatArray(width * height * 3)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = (row * width + col) * 3
                for (channel in 0..2) {
                    for (f in 0 until n) {
                        val raw = bytes[f][base + channel].toInt() and 0xFF
                        values[f] = (raw * factors[f]).roundToInt()
                    }
                    output[base + channel] = kappaSigmaMean(values, n, kappa, kappaPasses)
                }
            }
        }

        val result = Mat(height, width, CvType.CV_32FC3)
        result.put(0, 0, output)
        return result
    }

    private fun kappaSigmaMean(values: IntArray, n: Int, kappa: Double, kappaPasses: Int): Float {
        if (n <= 3) {
            // Sedikit frame: jangan klip agresif — median saja (tahan outlier).
            return medianOf(values, n).toFloat()
        }

        // 4-7 frame: satu pass konservatif (kappa sedikit dilonggarkan).
        val effectiveKappa = if (n in 4..7) kappa * 1.25 else kappa
        val effectivePasses = if (n in 4..7) minOf(kappaPasses, 1) else kappaPasses

        val accepted = BooleanArray(n) { true }
        var center = medianOf(values, n)

        repeat(effectivePasses) {
            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            for (i in 0 until n) {
                if (accepted[i]) {
                    val v = values[i].toDouble()
                    sum += v
                    sumSq += v * v
                    count++
                }
            }
            if (count == 0) return@repeat
            val mean = sum / count
            val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
            // Sigma relatif terhadap rentang data aktual: lantai 1.5% rentang
            // (bukan asumsi buta 0..255) agar tidak klip berlebihan.
            val dataRange = (values.maxOrNull()!! - values.minOrNull()!!).toDouble().coerceAtLeast(1.0)
            // Floor konsisten dengan jalur streaming (sama-sama punya lantai 8).
            val sigma = max(max(8.0, 0.015 * dataRange), sqrt(variance))
            var changed = false
            for (i in 0 until n) {
                if (accepted[i] && abs(values[i] - mean) > effectiveKappa * sigma) {
                    accepted[i] = false
                    changed = true
                }
            }
            center = mean
            if (!changed) return@repeat
        }

        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            if (accepted[i]) {
                sum += values[i]
                count++
            }
        }
        return if (count == 0) center.toFloat() else (sum / count).toFloat()
    }

    private fun medianOf(values: IntArray, n: Int): Double {
        val sorted = values.copyOfRange(0, n).sortedArray()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
    }

    private fun meanOf(values: IntArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += values[i]
        return (sum / n).toFloat()
    }

    private fun lightPollutionReduce(floatImage: Mat, strength: Float): Mat {
        val width = floatImage.cols()
        val height = floatImage.rows()
        val smallW = (width / 64.0).coerceAtLeast(8.0)
        val smallH = (height / 64.0).coerceAtLeast(8.0)
        val small = Mat()
        Imgproc.resize(floatImage, small, Size(smallW, smallH), 0.0, 0.0, Imgproc.INTER_AREA)

        // Model latar = blur dari erosi grid kecil. Latar ini SUDAH mengandung
        // pedestal (glow global), jadi cukup kurangi satu kali — mengurangi lagi
        // pedestal secara terpisah akan menggandakan pengurangan (double-subtract).
        val kernelSize = (smallW / 4.0).coerceIn(3.0, 16.0)
        val background = Mat()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(kernelSize, kernelSize),
        )
        Imgproc.erode(small, background, kernel)
        kernel.release()
        Imgproc.GaussianBlur(background, background, Size(0.0, 0.0), max(1.0, smallW / 8.0))

        val backgroundFull = Mat()
        Imgproc.resize(background, backgroundFull, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        background.release()

        val reduced = Mat()
        Core.subtract(floatImage, backgroundFull, reduced)
        Core.max(reduced, Scalar.all(0.0), reduced)
        backgroundFull.release()

        val blend = strength.toDouble()
        val result = Mat()
        Core.addWeighted(reduced, blend, floatImage, 1.0 - blend, 0.0, result, CvType.CV_32F)
        reduced.release()
        return result
    }

    private fun vignetteCorrect(floatImage: Mat, strength: Float): Mat {
        val maxDim = max(floatImage.cols(), floatImage.rows())
        val sigma = (maxDim / 8.0).coerceAtLeast(8.0)
        val background = Mat()
        Imgproc.GaussianBlur(floatImage, background, Size(0.0, 0.0), sigma)
        Core.add(background, Scalar(1.0, 1.0, 1.0, 1.0), background)

        val backgroundMean = Core.mean(background)
        val scale = (backgroundMean.`val`[0] + backgroundMean.`val`[1] + backgroundMean.`val`[2]) / 3.0

        val flat = Mat()
        Core.divide(floatImage, background, flat, scale, CvType.CV_32F)
        background.release()

        val corrected = Mat()
        val blend = strength.toDouble()
        Core.addWeighted(floatImage, 1.0 - blend, flat, blend, 0.0, corrected, CvType.CV_32F)
        flat.release()
        return corrected
    }

    private fun removeHotPixels(mat: Mat): Mat {
        // Deteksi spike terisolasi: piksel jauh lebih terang dari SEMUA tetangganya
        // (maks 8-tetangga) pada luminansi, bukan dari median kotak 3x3 yang ikut
        // menghitung dirinya sendiri. Inti bintang 2-3 px punya tetangga terang,
        // jadi tetap utuh. Mask dihitung dari grayscale agar aman untuk Mat 8U/32F
        // multi-kanal (threshold OpenCV hanya mendukung single-channel).
        val single = if (mat.channels() == 1) mat else {
            val g = Mat()
            Imgproc.cvtColor(mat, g, Imgproc.COLOR_BGR2GRAY)
            g
        }
        val neighborMax = Mat()
        val kernel = Mat(3, 3, CvType.CV_8U, Scalar(1.0))
        kernel.put(1, 1, 0.0) // pusat dikecualikan agar hanya tetangga yang dihitung
        Imgproc.dilate(single, neighborMax, kernel)
        kernel.release()
        // Ambang ADAPTIF: spike harus menonjol dari tetangga terdekatnya
        // (maks 8-tetangga), bukan angka mutlak. Di langit gelap floor 12
        // (skala 0..255) cukup; di area terang menyesuaikan 50% nilai tetangga.
        val thr = Mat()
        Core.multiply(neighborMax, Scalar.all(0.5), thr)
        Core.add(thr, Scalar.all(12.0), thr)
        val diff = Mat()
        Core.subtract(single, neighborMax, diff)
        neighborMax.release()
        val mask = Mat()
        Core.compare(diff, thr, mask, Core.CMP_GT)
        thr.release()
        val blurred = Mat()
        Imgproc.medianBlur(mat, blurred, 3)
        val result = mat.clone()
        blurred.copyTo(result, mask)
        blurred.release()
        diff.release()
        mask.release()
        if (single !== mat) single.release()
        return result
    }

    private fun blendSkyGround(outSky: Mat, outGround: Mat, horizonFraction: Float): Mat {
        val height = outSky.rows()
        val width = outSky.cols()
        val horizonRow = ((1.0 - horizonFraction.coerceIn(0.05f, 0.95f)) * height).roundToInt()
        val ramp = (height * 0.05).roundToInt().coerceAtLeast(1)
        val skyF = Mat()
        outSky.convertTo(skyF, CvType.CV_32FC3)
        val groundF = Mat()
        outGround.convertTo(groundF, CvType.CV_32FC3)
        val result = Mat.zeros(height, width, CvType.CV_32FC3)
        val skyRow = FloatArray(width * 3)
        val groundRow = FloatArray(width * 3)
        val outRow = FloatArray(width * 3)
        for (row in 0 until height) {
            val alpha = when {
                row < horizonRow - ramp -> 1.0
                row > horizonRow + ramp -> 0.0
                else -> {
                    val t = (row - (horizonRow - ramp)).toDouble() / (2.0 * ramp)
                    1.0 - t.coerceIn(0.0, 1.0)
                }
            }
            skyF.get(row, 0, skyRow)
            groundF.get(row, 0, groundRow)
            for (p in 0 until width * 3) {
                outRow[p] = (skyRow[p] * alpha + groundRow[p] * (1.0 - alpha)).toFloat()
            }
            result.put(row, 0, outRow)
        }
        // Hasil dikembalikan 8U agar konsisten dengan frame lain di batch
        // (max/median/sigma membaca via get(byte[]), hanya valid untuk CV_8U).
        val result8 = Mat()
        result.convertTo(result8, CvType.CV_8UC3)
        result.release()
        skyF.release()
        groundF.release()
        return result8
    }

    private fun blendSkyGroundMasked(outSky: Mat, outGround: Mat, mask: Mat): Mat {
        // Blend per-piksel memakai sky mask: hasil = sky*mask + tanah*(1-mask).
        // Mask 1 = langit (pakai alignment bintang), 0 = tanah (alignment affine).
        val skyF = Mat()
        outSky.convertTo(skyF, CvType.CV_32FC3)
        val groundF = Mat()
        outGround.convertTo(groundF, CvType.CV_32FC3)
        val m3 = Mat()
        Imgproc.cvtColor(mask, m3, Imgproc.COLOR_GRAY2BGR)
        m3.convertTo(m3, CvType.CV_32FC3)
        val inv = Mat()
        Core.multiply(m3, Scalar.all(-1.0), inv)
        Core.add(inv, Scalar.all(1.0), inv)
        Core.multiply(groundF, inv, groundF)
        Core.multiply(skyF, m3, skyF)
        val result = Mat()
        Core.add(skyF, groundF, result)
        skyF.release()
        groundF.release()
        m3.release()
        inv.release()
        val result8 = Mat()
        result.convertTo(result8, CvType.CV_8UC3)
        result.release()
        return result8
    }

    private fun buildSkyMask(gray: Mat): Mat? {
        return try {
            val gray8 = if (gray.depth() == CvType.CV_8U) gray else {
                val g = Mat()
                gray.convertTo(g, CvType.CV_8U)
                g
            }
            try {
                buildSkyMask8(gray8)
            } finally {
                if (gray8 !== gray) gray8.release()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun buildSkyMask8(gray: Mat): Mat? {
        val h = gray.rows()
        val w = gray.cols()
            val smallH = (64.0 * h / w).coerceIn(16.0, 128.0).roundToInt()
            val small = Mat()
            Imgproc.resize(gray, small, Size(64.0, smallH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val rows = small.rows()
            val cols = small.cols()
            val data = ByteArray(rows * cols)
            small.get(0, 0, data)
            small.release()
            // Tekstur per baris = rata-rata |gradien horizontal|. Langit halus
            // (kecuali titik bintang kecil), foreground (tanah/pohon) kasar.
            val rowTexture = FloatArray(rows)
            for (r in 0 until rows) {
                var sum = 0f
                for (c in 1 until cols) {
                    val d = (data[r * cols + c].toInt() and 0xFF) - (data[r * cols + c - 1].toInt() and 0xFF)
                    sum += abs(d)
                }
                rowTexture[r] = sum / (cols - 1)
            }
            val maxTex = rowTexture.maxOrNull() ?: return null
            val th = maxOf(12f, maxTex * 0.35f)
            var horizon = -1
            var run = 0
            for (r in 0 until rows) {
                if (rowTexture[r] > th) {
                    run++
                } else {
                    run = 0
                }
                if (run >= 2) {
                    horizon = r
                    break
                }
            }
            if (horizon <= rows * 0.04 || horizon >= rows * 0.96) return null
            val rampRows = (rows * 0.10).roundToInt().coerceAtLeast(2)
            val mask = Mat.zeros(h, w, CvType.CV_32FC1)
            val rowVals = FloatArray(w)
            for (r in 0 until h) {
                val rr = (r.toDouble() / h * rows).toInt().coerceIn(0, rows - 1)
                val alpha = when {
                    rr < horizon - rampRows -> 1.0
                    rr > horizon + rampRows -> 0.0
                    else -> {
                        val t = (rr - (horizon - rampRows)).toDouble() / (2.0 * rampRows)
                        1.0 - t.coerceIn(0.0, 1.0)
                    }
                }
                java.util.Arrays.fill(rowVals, alpha.toFloat())
                mask.put(r, 0, rowVals)
            }
            return mask
    }

    private fun enhanceStarColors(floatImage: Mat, strength: Float) {
        if (strength <= 0.001f) return
        val data = FloatArray(floatImage.cols() * floatImage.rows() * 3)
        floatImage.get(0, 0, data)
        for (p in data.indices step 3) {
            val b = data[p]
            val g = data[p + 1]
            val r = data[p + 2]
            val maxC = maxOf(b, g, r)
            val minC = minOf(b, g, r)
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            // Kandidat bintang/nebula = piksel terang; latar gelap nyaris tak berubah.
            val starWeight = (lum / 255.0).coerceIn(0.0, 1.0)
            val factor = 1.0 + strength * starWeight
            val center = (maxC + minC) / 2.0
            data[p] = (center + (b - center) * factor).toFloat()
            data[p + 1] = (center + (g - center) * factor).toFloat()
            data[p + 2] = (center + (r - center) * factor).toFloat()
        }
        floatImage.put(0, 0, data)
    }

    private fun autoBrightness(floatImage: Mat) {
        val data = FloatArray(floatImage.cols() * floatImage.rows() * 3)
        floatImage.get(0, 0, data)
        var sum = 0.0
        for (i in data.indices) sum += data[i]
        val mean = sum / data.size
        // Hanya mencerahkan bila hasil rata-rata terlalu gelap (langit deep-sky dll).
        if (mean in 1.0..60.0) {
            val target = 100.0
            val exponent = (ln(target / 255.0) / ln(mean / 255.0)).coerceIn(0.45, 1.6)
            for (i in data.indices) {
                val v = data[i].coerceIn(0f, 255f) / 255f
                data[i] = 255f * v.toDouble().pow(exponent).toFloat()
            }
            floatImage.put(0, 0, data)
        }
    }

    private fun postProcess(
        stacked0: Mat,
        usedFrames: Int,
        upscale2x: Boolean,
        sharpenStrength: Float,
        vignetteCorrection: Boolean,
        vignetteStrength: Float,
        lightPollutionReduction: Boolean,
        lprStrength: Float,
        skyBrightness: Float,
        enhanceStarColor: Boolean,
        starColorStrength: Float,
        saveTiff: Boolean,
        autoBrightness: Boolean,
        mergePixels: Boolean,
        hdr: Boolean,
        wbTemperatureK: Int = 6500,
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        onProgress: (Float, String) -> Unit,
        progressBase: Float,
        skyMask: Mat? = null,
    ): StackResult {
        val span = (0.99f - progressBase).coerceAtLeast(0.01f)
        var stacked = stacked0

        if (hdr) {
            onProgress(progressBase + 0.10f * span, "Komposisi HDR (tone mapping)...")
            stacked = toneMapHdr(stacked)
        }

        if (wbTemperatureK != 6500) {
            onProgress(progressBase + 0.20f * span, "Menyesuaikan white balance ($wbTemperatureK K)...")
            applyWhiteBalance(stacked, wbTemperatureK)
        }

        if (lightPollutionReduction && lprStrength > 0.001f) {
            onProgress(progressBase + 0.30f * span, "Mengurangi polusi cahaya langit...")
            stacked = lightPollutionReduce(stacked, lprStrength)
        }

        if (vignetteCorrection && vignetteStrength > 0.001f) {
            onProgress(progressBase + 0.42f * span, "Menyamakan cahaya latar (gradien/vignette)...")
            stacked = vignetteCorrect(stacked, vignetteStrength)
        }

        if (skyBrightness > 0.001f) {
            onProgress(progressBase + 0.55f * span, "Menyesuaikan pencahayaan langit...")
            adjustSkyBrightness(stacked, skyBrightness, skyMask)
        }

        if (enhanceStarColor && starColorStrength > 0.001f) {
            onProgress(progressBase + 0.62f * span, "Memperkuat warna bintang...")
            enhanceStarColors(stacked, starColorStrength)
        }

        if (autoBrightness) {
            onProgress(progressBase + 0.63f * span, "Menyesuaikan kecerahan otomatis...")
            autoBrightness(stacked)
        }

        stacked = convertColorSpace(stacked, colorSpace)

        var tiffBytes: ByteArray? = null
        val tiffWidth = stacked.cols()
        val tiffHeight = stacked.rows()
        if (saveTiff) {
            tiffBytes = encode16BitRgb(stacked, colorSpace)
        }

        var merged = Mat()
        stacked.convertTo(merged, CvType.CV_8UC3)
        stacked.release()

        if (mergePixels) {
            onProgress(progressBase + 0.66f * span, "Menggabungkan piksel 2×2 (binning)...")
            val small = Mat()
            Imgproc.resize(
                merged,
                small,
                Size(merged.cols() / 2.0, merged.rows() / 2.0),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            merged.release()
            merged = small
        }

        // Upscale 2x hanya untuk resolusi kerja sedang; dilewati saat binning 2x
        // aktif (saling meniadakan = ukuran sama, detail justru berkurang) dan
        // pada Full/Asli yang sudah sangat besar (memori jebol tanpa detail nyata).
        if (upscale2x && !mergePixels && merged.cols() <= 3072 && merged.rows() <= 3072) {
            onProgress(progressBase + 0.70f * span, "Memperbesar detail (2x)...")
            val big = Mat()
            Imgproc.resize(
                merged,
                big,
                Size(merged.cols() * 2.0, merged.rows() * 2.0),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC,
            )
            merged.release()
            merged = big
        }

        if (sharpenStrength > 0.001f) {
            onProgress(progressBase + 0.85f * span, "Menajamkan tepi...")
            val blur = Mat()
            Imgproc.GaussianBlur(merged, blur, Size(0.0, 0.0), 1.0)
            val sharp = Mat()
            val amount = 1.0 + sharpenStrength
            Core.addWeighted(merged, amount, blur, 1.0 - amount, 0.0, sharp)
            blur.release()
            merged.release()
            merged = sharp
        }

        val outBitmap = Bitmap.createBitmap(merged.cols(), merged.rows(), Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(merged, merged, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(merged, outBitmap)
        merged.release()

        return StackResult(outBitmap, usedFrames, tiffBytes, tiffWidth, tiffHeight)
    }

    private fun applyWhiteBalance(floatImage: Mat, temperatureK: Int) {
        if (temperatureK == 6500) return
        // BGR float 0..255. Konvensi seperti slider Temp Lightroom: nilai K adalah skala
        // kontrol, bukan suhu fisik cahaya. K naik = lebih hangat (R naik, B turun),
        // K turun = lebih dingin (B naik, R turun).
        val amount = ((temperatureK - 6500) / 3500f).coerceIn(-1f, 1f) * 0.45f
        val rGain = 1f + amount
        val bGain = 1f - amount
        val data = FloatArray(floatImage.cols() * floatImage.rows() * 3)
        floatImage.get(0, 0, data)
        var i = 0
        while (i < data.size) {
            data[i] = (data[i] * bGain).coerceIn(0f, 255f)
            data[i + 2] = (data[i + 2] * rGain).coerceIn(0f, 255f)
            i += 3
        }
        floatImage.put(0, 0, data)
    }

    private fun convertColorSpace(floatImage: Mat, colorSpace: OutputColorSpace): Mat {
        if (colorSpace == OutputColorSpace.SRGB) return floatImage
        val matrix = when (colorSpace) {
            OutputColorSpace.ADOBE_RGB -> floatArrayOf(
                1.39853f, -0.30544f, -0.09308f,
                -0.06368f, 1.12694f, -0.06327f,
                -0.02458f, -0.05446f, 1.07904f,
            )
            OutputColorSpace.DISPLAY_P3 -> floatArrayOf(
                0.82246f, 0.17754f, 0.00000f,
                0.03319f, 0.96681f, 0.00000f,
                0.01708f, 0.07240f, 0.91052f,
            )
            else -> return floatImage
        }
        val data = FloatArray(floatImage.cols() * floatImage.rows() * 3)
        floatImage.get(0, 0, data)
        // Konversi colorimetric yang benar: linearisasi sRGB -> matriks ->
        // re-encode TRC target (Adobe RGB gamma 2.19921875, Display P3 kurva sRGB).
        val n = data.size
        val linear = FloatArray(n)
        for (i in 0 until n) {
            val v = data[i].coerceIn(0f, 255f) / 255f
            linear[i] = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        }
        for (p in 0 until n step 3) {
            val r = linear[p + 2]
            val g = linear[p + 1]
            val b = linear[p]
            val rl = matrix[0] * r + matrix[1] * g + matrix[2] * b
            val gl = matrix[3] * r + matrix[4] * g + matrix[5] * b
            val bl = matrix[6] * r + matrix[7] * g + matrix[8] * b
            linear[p] = bl
            linear[p + 1] = gl
            linear[p + 2] = rl
        }
        val adobe = colorSpace == OutputColorSpace.ADOBE_RGB
        for (i in 0 until n) {
            val v = linear[i].coerceIn(0f, 1f)
            val e = if (adobe) {
                if (v <= 0f) 0f else v.pow(1f / 2.19921875f)
            } else {
                if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
            }
            data[i] = e.coerceIn(0f, 1f) * 255f
        }
        floatImage.put(0, 0, data)
        return floatImage
    }

    /**
     * Estimasi puncak memori jalur batch (bitmap ARGB + Mat frame + array
     * sigma-clip + float output) untuk memutuskan apakah aman memakai batch.
     */
    fun batchFitsMemory(frameCount: Int, maxDim: Int): Boolean {
        val pixels = maxDim.toLong() * maxDim
        val estimate = frameCount * 10L * pixels + 12L * pixels
        return estimate <= (Runtime.getRuntime().maxMemory() * 0.6).toLong()
    }

    private fun heapSafeMaxDim(requested: Int): Int {
        val maxHeap = Runtime.getRuntime().maxMemory()
        // Estimasi heap Java per piksel untuk streaming kappa-sigma:
        // mean + sumsq + clipSum + data transien (4× float) + clipCount (int).
        val bytesPerPixel = 52
        val budget = (maxHeap * 0.55).toLong()
        val safe = sqrt(budget.toDouble() / bytesPerPixel).toInt()
        return requested.coerceAtMost(maxOf(1400, (safe / 64) * 64))
    }

    private fun adjustSkyBrightness(floatImage: Mat, amount: Float, skyMask: Mat?) {
        val gamma = 1.0 + 2.0 * amount
        val exponent = 1.0 / gamma
        val data = FloatArray(floatImage.cols() * floatImage.rows() * 3)
        floatImage.get(0, 0, data)
        val maskData = if (skyMask != null &&
            skyMask.cols() == floatImage.cols() && skyMask.rows() == floatImage.rows()
        ) {
            FloatArray(skyMask.cols() * skyMask.rows()).also { skyMask.get(0, 0, it) }
        } else {
            null
        }
        for (i in data.indices) {
            // Tanpa mask: gamma global. Dengan mask: hanya piksel langit (mask>0)
            // yang digammakan, tanah dipertahankan.
            if (maskData != null && maskData[i / 3] <= 0.001f) continue
            val v = data[i].coerceIn(0f, 255f) / 255f
            data[i] = 255f * v.toDouble().pow(exponent).toFloat()
        }
        floatImage.put(0, 0, data)
    }

    private fun encode16BitRgb(floatImage: Mat, colorSpace: OutputColorSpace): ByteArray {
        val width = floatImage.cols()
        val height = floatImage.rows()
        val pixelCount = width * height
        val data = FloatArray(pixelCount * 3)
        floatImage.get(0, 0, data)
        val icc = iccCache?.get(colorSpace)?.takeIf { it.isNotEmpty() }
        return TiffEncoder.encodeRgb16(width, height, data, icc)
    }
}
