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
import com.stackscan.BuildConfig
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
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Moments
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    private const val MIN_STARS_COUNT = 6
    // Jarak cari diperlebar + uji rasio tetangga terdekat agar rotasi frame
    // (hingga beberapa derajat di tepi) tetap bisa dicocokkan, bukan langsung jatuh ke ECC.
    private const val MAX_STAR_MATCH_DIST = 100.0
    private const val STAR_MATCH_RATIO = 0.75
    // Refinement & kontrol kualitas alignment bintang: residual inlier (px) yang
    // tidak bisa dihilangkan oleh affine RANSAC membuat bintang tampak memanjang
    // saat di-stack (lighten/kappa-sigma mengawetkan jejak sub-piksel tsb).
    private const val STAR_REFINE_RESIDUAL_PX = 0.6
    private const val MAX_STAR_ALIGN_RESIDUAL_PX = 1.0
    // Deteksi trail bintang: frame dengan elongasi jauh di atas median set dibuang
    // sebelum align, dan anchor alignment hanya memakai bintang bulat (centroid
    // stabil). Bila hampir semua frame memanjang, tidak ada yang dibuang — hanya
    // peringatan — agar stacking tidak kosong.
    private const val ROUND_STAR_ELONG_MAX = 2.0
    private const val TRAIL_SKIP_ELONG_MIN = 3.0
    private const val TRAIL_SKIP_OUTLIER_FACTOR = 1.6
    private const val TRAIL_WARN_ELONG = 2.5
    private const val TRAIL_STREAM_SKIP_ELONG = 5.0
    private const val MIN_FRAMES_AFTER_TRAIL_SKIP = 3
    private val ECC_REFINE_CRITERIA = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 1e-4)
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


            // NOTE: Background extraction is NOT applied here (before star detection).
            // It is applied per-frame inside loadAlignedFrame() AFTER warping,
            // which is the correct Sequator-equivalent behavior.
            // Extracting background before star detection would remove faint stars
            // and corrupt alignment accuracy.

            // Deteksi trail bintang (astro): frame dengan elongasi jauh di atas
            // median set dibuang sebelum align, dan frame acuan dipilih dari sisa
            // set agar bintang yang memanjang tidak menodai transform.
            val trailScores = if (astroMode) grays.map { frameTrailScore(it) } else emptyList()
            val trailSkips = if (astroMode) trailSkipSet(trailScores) else emptySet()
            val refIndex = pickReferenceIndex(grays, astroMode, trailSkips, trailScores)
            if (trailSkips.isNotEmpty()) {
                Log.w(TAG, "Trail detect: ${trailSkips.size} frame dibuang (elongasi > ambang) sebelum stacking.")
            }
            val sortedTrail = trailScores.sorted()
            val medianTrail = if (sortedTrail.isEmpty()) 1.0 else {
                if (sortedTrail.size % 2 == 1) sortedTrail[sortedTrail.size / 2]
                else (sortedTrail[sortedTrail.size / 2 - 1] + sortedTrail[sortedTrail.size / 2]) / 2.0
            }
            if (astroMode && medianTrail > TRAIL_WARN_ELONG) {
                Log.w(
                    TAG,
                    "PERINGATAN TRAIL: elongasi bintang median ${"%.1f".format(medianTrail)}x — SEMUA frame memanjang. " +
                        "Hasil stack akan ikut memanjang. Gunakan shutter \u2264 2 dtk dan ISO lebih tinggi (bintang bulat) " +
                        "agar StackScan bisa menghasilkan bintang yang tajam.",
                )
            }
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
                if (trailSkips.isNotEmpty() && i in trailSkips) {
                    Log.w(TAG, "Frame #$i dibuang sebelum align: elongasi trail ${"%.1f".format(trailScores[i])}x > ambang.")
                    processed++
                    continue
                }
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
            val trailScore = if (astroMode) frameTrailScore(gray) else 1.0
            if (astroMode && trailScore > TRAIL_STREAM_SKIP_ELONG) {
                Log.w(
                    TAG,
                    "Frame trail ekstrem (elongasi ${"%.1f".format(trailScore)}x) dibuang — " +
                        "melebihi batas $TRAIL_STREAM_SKIP_ELONG (frame goyang/rusak).",
                )
                gray.release()
                mat.release()
                return null
            } else if (astroMode && trailScore > TRAIL_WARN_ELONG) {
                Log.w(
                    TAG,
                    "PERINGATAN TRAIL: frame memanjang ${"%.1f".format(trailScore)}x — hasil stack bisa ikut " +
                        "memanjang. Gunakan shutter \u2264 2 dtk / ISO lebih tinggi agar bintang bulat.",
                )
            }
            var grayRef = sameSizeAsReference(referenceGray, gray)
            // NOTE: Background extraction is NOT applied here (before star detection).
            // It is applied AFTER warping in the alignment step, which is the correct
            // Sequator-equivalent behavior.
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
            val sigma = max(2f, sqrt(variance)).toFloat()
            if (abs(v - mean[p]) <= kappa * sigma) {
                clipSum[p] += v
                clipCount[p]++
            }
        }
    }

    /**
     * Robust luminance using background region statistics (BAGIAN 12).
     * Uses median of lower 50th percentile to avoid bright stars and
     * light pollution gradient biasing the measurement.
     */
    private fun luminanceOf(data: FloatArray): Double {
        val n = data.size / 3
        if (n <= 0) return 1.0
        // Sample luminance for every 4th pixel (performance on Android)
        val sampled = mutableListOf<Float>()
        var i = 0
        while (i + 2 < data.size) {
            sampled.add((0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]).toFloat())
            i += 12 // every 4th pixel (3 channels × 4 = 12 stride)
        }
        if (sampled.isEmpty()) return 1.0
        sampled.sort()
        // Use median of lower half (background region, avoiding bright sources)
        val halfSize = sampled.size / 2
        val lowerHalf = sampled.subList(0, halfSize.coerceAtLeast(1))
        return lowerHalf.average().coerceAtLeast(0.001)
    }

    private fun exposureFactorFor(data: FloatArray, refLum: Double): Double =
        (refLum / luminanceOf(data).coerceAtLeast(0.001)).coerceIn(0.5, 2.0)

    private fun pickReferenceIndex(
        grays: List<Mat>,
        astroMode: Boolean,
        excluded: Set<Int> = emptySet(),
        trailScores: List<Double>? = null,
    ): Int {
        if (grays.size <= 1) return 0
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        // Pass 0: pilih dari frame yang tidak dibuang trail. Pass 1: fallback
        // (tak mungkin terjadi normalnya) bila semua frame tereksklusi.
        for (pass in 0..1) {
            for (i in grays.indices) {
                if (pass == 0 && i in excluded) continue
                val base = if (astroMode) {
                    // Astro: star count + roundness bonus + SNR + sharpness.
                    val blobs = findStarBlobs(grays[i])
                    val starCount = blobs.size.toDouble()
                    val roundStars = blobs.count { it.classification == StarType.STAR_POINT }.toDouble()
                    val roundRatio = if (starCount > 0) roundStars / starCount else 0.0
                    val avgSNR = if (blobs.isNotEmpty()) blobs.map { it.peakIntensity / (it.meanIntensity + 1.0) }.average() else 0.0
                    starCount * 50.0 +
                        roundRatio * 200.0 +
                        roundStars * 80.0 +
                        avgSNR * 30.0 +
                        textureScore(grays[i])
                } else {
                    textureScore(grays[i])
                }
                // Hukuman elongasi: frame acuan yang memanjang menularkan trail-nya
                // ke seluruh hasil stack, jadi preferensikan frame berbintang bulat.
                val trail = trailScores?.getOrNull(i) ?: 1.0
                val penalty = if (astroMode) max(0.0, trail - 1.2) * 300.0 else 0.0
                val score = base - penalty
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best >= 0) return best
        }
        return 0
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
        val refStars = findStarBlobs(reference)
        val movStars = findStarBlobs(moving)
        if (refStars.size < MIN_STARS_COUNT || movStars.size < MIN_STARS_COUNT) {
            Log.w(
                TAG,
                "Star align ditolak: bintang acuan=${refStars.size}, bintang frame=${movStars.size} (min $MIN_STARS_COUNT).",
            )
            return null
        }

        // Anchor hanya bintang bulat: centroidnya stabil, sedangkan inti saturasi
        // yang memanjang punya centroid bias di sepanjang sumbu trail sehingga
        // menarik transform ke arah yang salah. Fallback ke semua blob bila bintang
        // bulatnya kurang dari minimum (mis. semua frame memang trail).
        val refRound = refStars.filter { it.elongation <= ROUND_STAR_ELONG_MAX }
        val movRound = movStars.filter { it.elongation <= ROUND_STAR_ELONG_MAX }
        val roundAnchors = refRound.size >= MIN_STARS_COUNT && movRound.size >= MIN_STARS_COUNT
        val refAnchors = if (roundAnchors) refRound else refStars
        val movAnchors = if (roundAnchors) movRound else movStars
        if (!roundAnchors) {
            Log.w(
                TAG,
                "Star align: bintang bulat < $MIN_STARS_COUNT (acuan=${refRound.size}, frame=${movRound.size}); " +
                    "pakai anchor campuran (hasil bisa kurang presisi di bintang memanjang).",
            )
        }

        // Bootstrap: korelasi fase peta bintang (256×256) untuk estimasi
        // pergeseran global kasar. Mempersempit jendela pencarian NN dari
        // MAX_STAR_MATCH_DIST menjadi ~30px, drastis mengurangi false match
        // di medan bintang padat.
        var coarseShiftX: Double
        var coarseShiftY: Double
        try {
            val mapSize = 256
            val refMap = Mat.zeros(mapSize, mapSize, CvType.CV_32F)
            val movMap = Mat.zeros(mapSize, mapSize, CvType.CV_32F)
            val scaleX = mapSize.toDouble() / reference.cols()
            val scaleY = mapSize.toDouble() / reference.rows()
            for (s in refAnchors.take(200)) {
                val px = (s.x * scaleX).toInt().coerceIn(0, mapSize - 1)
                val py = (s.y * scaleY).toInt().coerceIn(0, mapSize - 1)
                refMap.put(py, px, 1.0)
            }
            for (s in movAnchors.take(200)) {
                val px = (s.x * scaleX).toInt().coerceIn(0, mapSize - 1)
                val py = (s.y * scaleY).toInt().coerceIn(0, mapSize - 1)
                movMap.put(py, px, 1.0)
            }
            val phaseResult = Imgproc.phaseCorrelate(refMap, movMap)
            coarseShiftX = phaseResult.x * (1.0 / scaleX)
            coarseShiftY = phaseResult.y * (1.0 / scaleY)
            refMap.release(); movMap.release()
            Log.i(TAG, "Phase bootstrap: shift=(%.1f, %.1f) px".format(coarseShiftX, coarseShiftY))
        } catch (_: Throwable) {
            coarseShiftX = 0.0
            coarseShiftY = 0.0
        }

        // Jendela pencarian NN: bootstrap sempit bila pergeseran kecil,
        // fallback lebar bila bootstrap tidak tersedia atau pergeseran besar.
        val nnWindow = if (abs(coarseShiftX) < 100.0 && abs(coarseShiftY) < 100.0) {
            max(30.0, hypot(coarseShiftX, coarseShiftY) + 20.0)
        } else {
            MAX_STAR_MATCH_DIST
        }

        val pairRef = ArrayList<Point>()
        val pairMov = ArrayList<Point>()
        val used = BooleanArray(movAnchors.size)

        for (refStar in refAnchors) {
            // Offset: cari di sekitar posisi refStar - coarseShift.
            val targetX = refStar.x - coarseShiftX
            val targetY = refStar.y - coarseShiftY
            var bestIndex = -1
            var bestDist = nnWindow
            var secondIndex = -1
            var secondDist = nnWindow
            for (j in movAnchors.indices) {
                if (used[j]) continue
                val d = hypot(targetX - movAnchors[j].x, targetY - movAnchors[j].y)
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
                pairRef.add(Point(refStar.x, refStar.y))
                pairMov.add(Point(movAnchors[bestIndex].x, movAnchors[bestIndex].y))
            }
        }

        if (pairRef.size < MIN_STARS_COUNT) return null

        return try {
            val from = MatOfPoint2f(*pairRef.toTypedArray())
            val to = MatOfPoint2f(*pairMov.toTypedArray())
            val inliers = MatOfByte()
            val affine = Calib3d.estimateAffinePartial2D(from, to, inliers, Calib3d.RANSAC, 3.0)
            if (affine.empty()) {
                affine.release()
                null
            } else {
                val inlierFlags = inliers.toArray()
                val inlierCount = inlierFlags.count { it.toInt() != 0 }
                if (!warpSane(affine, reference.cols(), reference.rows())) {
                    val st = warpStats(affine)
                    Log.w(
                        TAG,
                        "Star align ditolak: transform tidak masuk akal (tx=${st.tx}, ty=${st.ty}, rot=${st.rotationDeg}, scale=${st.scale}).",
                    )
                    affine.release()
                    null
                } else {
                    val residual = starAlignResidualPx(affine, pairRef, pairMov, inlierFlags)
                    // Bila residual inlier masih di atas ambang, perhalus sub-piksel
                    // dengan ECC yang di-seed dari warp bintang (residual < ~0,3px).
                    val refined = if (residual != null && residual > STAR_REFINE_RESIDUAL_PX) {
                        refineStarWarp(reference, moving, affine)
                    } else {
                        null
                    }
                    val finalWarp: Mat
                    val finalResidual: Double?
                    var useRefined = false
                    if (refined != null && residual != null) {
                        val refinedResidual = starAlignResidualPx(refined, pairRef, pairMov, inlierFlags)
                        if (refinedResidual == null || refinedResidual <= residual + 0.05) {
                            // Refinement diterima bila residual tidak memburuk.
                            finalWarp = refined
                            finalResidual = refinedResidual ?: residual
                            useRefined = true
                        } else {
                            finalWarp = affine
                            finalResidual = residual
                        }
                    } else {
                        finalWarp = affine
                        finalResidual = residual
                    }
                    if (!useRefined) refined?.release()
                    if (useRefined) affine.release()

                    if (finalResidual != null && finalResidual > MAX_STAR_ALIGN_RESIDUAL_PX) {
                        Log.w(
                            TAG,
                            "Star align ditolak: residual bintang ${"%.2f".format(finalResidual)}px > " +
                                "$MAX_STAR_ALIGN_RESIDUAL_PX px (frame tidak presisi, dilewati).",
                        )
                        finalWarp.release()
                        null
                    } else {
                        Log.i(
                            TAG,
                            "Star align: pasangan=${pairRef.size}, inliers=$inlierCount " +
                                "(${100.0 * inlierCount / pairRef.size.coerceAtLeast(1)}%), " +
                                "residual=${"%.2f".format(finalResidual ?: 0.0)}px" +
                                (if (useRefined) " (diperhalus ECC)" else "") +
                                (if (roundAnchors) " (anchor bulat)" else " (anchor campuran)") + ".",
                        )
                        finalWarp
                    }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Median jarak (px) antara posisi bintang terprediksi oleh warp affine dan posisi
     * bintang yang benar-benar terdeteksi, dihitung hanya untuk inlier RANSAC.
     * Nilai ini = sisa ketidaksejajaran yang tidak mampu dimodelkan affine RANSAC
     * (noise deteksi, aberasi/distorsi lensa); sumber utama bintang memanjang.
     */
    private fun starAlignResidualPx(
        warp: Mat,
        pairRef: List<Point>,
        pairMov: List<Point>,
        inlierFlags: ByteArray,
    ): Double? {
        val a00 = warp.get(0, 0)[0]
        val a01 = warp.get(0, 1)[0]
        val a02 = warp.get(0, 2)[0]
        val a10 = warp.get(1, 0)[0]
        val a11 = warp.get(1, 1)[0]
        val a12 = warp.get(1, 2)[0]
        val n = minOf(pairRef.size, pairMov.size)
        val errors = DoubleArray(n)
        var count = 0
        for (i in 0 until n) {
            if (i >= inlierFlags.size || inlierFlags[i].toInt() == 0) continue
            val ex = a00 * pairRef[i].x + a01 * pairRef[i].y + a02 - pairMov[i].x
            val ey = a10 * pairRef[i].x + a11 * pairRef[i].y + a12 - pairMov[i].y
            errors[count++] = hypot(ex, ey)
        }
        if (count == 0) return null
        errors.sort(0, count)
        return if (count % 2 == 1) {
            errors[count / 2]
        } else {
            (errors[count / 2 - 1] + errors[count / 2]) / 2.0
        }
    }

    /**
     * Refinement ECC (MOTION_AFFINE) yang di-seed dari warp bintang: memperbaiki
     * sisa ketidaksejajaran sub-piksel sebelum frame di-warp & di-stack.
     */
    private fun refineStarWarp(reference: Mat, moving: Mat, seed: Mat): Mat? {
        val warp = Mat()
        seed.convertTo(warp, CvType.CV_32F)
        return try {
            val score = Video.findTransformECC(reference, moving, warp, Video.MOTION_AFFINE, ECC_REFINE_CRITERIA)
            if (!warpSane(warp, reference.cols(), reference.rows())) {
                Log.w(TAG, "ECC refine ditolak: transform tidak masuk akal setelah refinement.")
                warp.release()
                null
            } else {
                Log.i(TAG, "ECC refine: score=${"%.4f".format(score)}, warp=${warpSummary(warp)}.")
                warp
            }
        } catch (t: Throwable) {
            warp.release()
            null
        }
    }

    private enum class StarType { STAR_POINT, ELONGATED_STAR, TRAIL, NOISE }

    private data class StarBlob(
        val x: Double,
        val y: Double,
        val area: Double,
        val elongation: Double,
        val peakIntensity: Double = 0.0,
        val meanIntensity: Double = 0.0,
        val majorAxis: Double = 0.0,
        val minorAxis: Double = 0.0,
        val orientation: Double = 0.0,
        val roundness: Double = 0.0,
        val classification: StarType = StarType.STAR_POINT,
    )

    private const val ELONGATION_STAR_THRESHOLD = 1.8
    private const val ELONGATION_TRAIL_THRESHOLD = 3.5
    private const val MIN_AREA_FOR_STAR = 3.0
    private const val MAX_AREA_FOR_NOISE = 2.0

    private fun classifyStar(elongation: Double, area: Double, peakIntensity: Double): StarType {
        if (area < MIN_AREA_FOR_STAR || peakIntensity < 5.0) return StarType.NOISE
        if (elongation <= ELONGATION_STAR_THRESHOLD) return StarType.STAR_POINT
        if (elongation <= ELONGATION_TRAIL_THRESHOLD) return StarType.ELONGATED_STAR
        return StarType.TRAIL
    }

    private fun findStarBlobs(gray: Mat): List<StarBlob> {
        return try {
            val w = gray.cols()
            val h = gray.rows()
            // Konversi ke float untuk presisi sub-piksel.
            val f32 = Mat()
            if (gray.depth() == CvType.CV_32F) {
                gray.copyTo(f32)
            } else {
                gray.convertTo(f32, CvType.CV_32F)
            }

            // 1) Background estimasi: resize kecil → kembali (mean filter kasar).
            val small = Mat()
            Imgproc.resize(f32, small, Size(64.0, 64.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val bg = Mat()
            Imgproc.resize(small, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            small.release()

            // 2) Residual = f32 - bg.
            val resid = Mat()
            Core.subtract(f32, bg, resid)

            // 3) Noise σ robust via MAD di residual yang di-downscale (128×128).
            val residSmall = Mat()
            Imgproc.resize(resid, residSmall, Size(128.0, 128.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val flat = residSmall.reshape(1, 1) // 1 × 16384
            val sorted = Mat()
            Core.sort(flat, sorted, Core.SORT_EVERY_ROW or Core.SORT_ASCENDING)
            val n = sorted.cols()
            val median = sorted.get(0, n / 2)[0]
            val ad = Mat()
            Core.absdiff(flat, Scalar.all(median), ad)
            val sortedAd = Mat()
            Core.sort(ad, sortedAd, Core.SORT_EVERY_ROW or Core.SORT_ASCENDING)
            val mad = sortedAd.get(0, n / 2)[0]
            val sigma128 = 1.4826 * mad
            // Scale σ ke resolusi penuh: noise turun dengan sqrt(pengurangan piksel).
            val scaleFactor = sqrt((w.toDouble() * h.toDouble()) / (128.0 * 128.0))
            val sigmaFull = sigma128 * scaleFactor
            val kDetect = 3.5
            val floorPx = 3.0
            residSmall.release(); flat.release(); sorted.release(); ad.release(); sortedAd.release()

            // 4) Threshold map: bg + max(k*σ, floor).
            val thrVal = max(kDetect * sigmaFull, floorPx)
            val thr = Mat()
            Core.add(bg, Scalar.all(thrVal), thr)

            // 5) Local maxima: dilate 3×3 lalu compare.
            val dilated = Mat()
            Imgproc.dilate(f32, dilated, Mat.ones(3, 3, CvType.CV_32F))
            val peakMask = Mat()
            Core.compare(f32, dilated, peakMask, Core.CMP_GE) // peak == dilated
            val aboveThr = Mat()
            Core.compare(f32, thr, aboveThr, Core.CMP_GT)
            val combined = Mat()
            Core.bitwise_and(peakMask, aboveThr, combined)
            dilated.release(); aboveThr.release()

            // 6) Dapatkan koordinat piksel peak.
            val points = MatOfPoint()
            Core.findNonZero(combined, points)
            combined.release()

            val blobs = ArrayList<StarBlob>()
            val r = 3
            val pts = points.toArray()
            for (p in pts) {
                val x0 = p.x
                val y0 = p.y
                val xa = max(0.0, x0 - r).toInt()
                val xb = min(w.toDouble(), x0 + r + 1).toInt()
                val ya = max(0.0, y0 - r).toInt()
                val yb = min(h.toDouble(), y0 + r + 1).toInt()
                val patch = Mat(f32, org.opencv.core.Rect(xa, ya, xb - xa, yb - ya))
                val bgPatch = Mat(bg, org.opencv.core.Rect(xa, ya, xb - xa, yb - ya))
                val diff = Mat()
                Core.subtract(patch, bgPatch, diff)
                // Weight = max(diff, 0)² — bintang di atas background.
                val wgt = Mat()
                Core.max(diff, Mat.zeros(diff.size(), CvType.CV_32F), diff)
                Core.multiply(diff, diff, wgt, 1.0, CvType.CV_32F)
                val s = Core.sumElems(wgt).`val`[0].toFloat().toDouble()
                if (s <= 0.0) { diff.release(); wgt.release(); continue }
                // Compute peak and mean BEFORE releasing diff
                val peakVal = Core.minMaxLoc(diff).maxVal
                val sumDiff = Core.sumElems(diff).`val`[0]
                diff.release()

                // Weighted centroid.
                val yyArr = Mat()
                val xxArr = Mat()
                // Buat grid koordinat [ya..yb) × [xa..xb).
                val rows = yb - ya
                val cols = xb - xa
                val yyData = FloatArray(rows * cols)
                val xxData = FloatArray(rows * cols)
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        yyData[row * cols + col] = (ya + row).toFloat()
                        xxData[row * cols + col] = (xa + col).toFloat()
                    }
                }
                val yy = Mat(rows, cols, CvType.CV_32F)
                val xx = Mat(rows, cols, CvType.CV_32F)
                yy.put(0, 0, yyData)
                xx.put(0, 0, xxData)
                val cxMat = Mat()
                val cyMat = Mat()
                Core.multiply(xx, wgt, cxMat, 1.0, CvType.CV_32F)
                Core.multiply(yy, wgt, cyMat, 1.0, CvType.CV_32F)
                val cx = Core.sumElems(cxMat).`val`[0] / s
                val cy = Core.sumElems(cyMat).`val`[0] / s
                // Elongation dari momen orde-2 berbobot (SEBELUM wgt di-release).
                var mu20 = 0.0; var mu02 = 0.0; var mu11 = 0.0
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val weight = wgt.get(row, col)[0].toDouble()
                        val dx = (ya + row) - cy
                        val dx2 = (xa + col) - cx
                        mu20 += weight * dx2 * dx2
                        mu02 += weight * dx * dx
                        mu11 += weight * dx2 * dx
                    }
                }
                mu20 /= s; mu02 /= s; mu11 /= s
                val trace = mu20 + mu02
                val disc = sqrt(((mu20 - mu02) / 2.0).pow(2) + mu11 * mu11)
                val lamMax = trace / 2.0 + disc
                val lamMin = max(trace / 2.0 - disc, 1e-6)
                val elongation = sqrt(lamMax / lamMin)

                xx.release(); yy.release(); xxArr.release(); yyArr.release()
                cxMat.release(); cyMat.release(); wgt.release()

                val peak = peakVal.toDouble()
                val meanVal = if (s > 0) sumDiff / s else 0.0
                val major = sqrt(lamMax)
                val minor = sqrt(lamMin)
                val orient = Math.toDegrees(atan2(2.0 * mu11, mu20 - mu02))
                val roundness = if (major > 1e-6) minor / major else 1.0
                val classification = classifyStar(elongation, s, peak)
                blobs.add(StarBlob(cx, cy, s, elongation, peak, meanVal, major, minor, orient, roundness, classification))
            }
            points.release()
            f32.release(); bg.release(); resid.release(); thr.release(); peakMask.release()
            blobs
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun findStarPoints(gray: Mat): List<Point> =
        findStarBlobs(gray).map { Point(it.x, it.y) }

    /**
     * Rasio sumbu utama (>= 1.0) sebuah blob dari momen orde-2; 1.0 = bulat,
     * makin besar = makin memanjang.
     */
    private fun blobElongation(moments: Moments): Double {
        val mu20 = moments.mu20 / moments.m00
        val mu02 = moments.mu02 / moments.m00
        val mu11 = moments.mu11 / moments.m00
        val trace = mu20 + mu02
        val disc = sqrt(((mu20 - mu02) / 2.0).pow(2) + mu11 * mu11)
        val lamMax = trace / 2.0 + disc
        val lamMin = max(trace / 2.0 - disc, 1e-6)
        return sqrt(lamMax / lamMin)
    }

    /**
     * Skor trail sebuah frame: elongasi maksimum blob terang (ambang 35% dari
     * puncak kecerahan, saringan ukuran agar bulan/langit terang tidak terhitung).
     * Bintang bulat -> ~1,0-1,8; trail panjang -> 3+.
     */
    private fun frameTrailScore(gray: Mat): Double {
        return try {
            if (gray.depth() != CvType.CV_8U) return 1.0
            val maxVal = Core.minMaxLoc(gray).maxVal
            if (maxVal < 64.0) return 1.0
            val binary = Mat()
            Imgproc.threshold(gray, binary, maxVal * 0.35, 255.0, Imgproc.THRESH_BINARY)
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val maxStarArea = gray.cols() * gray.rows() * 0.0015
            var score = 1.0
            for (contour in contours) {
                val moments = Imgproc.moments(contour)
                if (moments.m00 in 8.0..maxStarArea) {
                    score = max(score, blobElongation(moments))
                }
                contour.release()
            }
            binary.release()
            hierarchy.release()
            score
        } catch (t: Throwable) {
            1.0
        }
    }

    /**
     * Frame dengan trail jauh lebih parah daripada median set dibuang. Bila hampir
     * semua frame memanjang (mis. semua memakai mode malam), tidak ada yang dibuang
     * agar stacking tetap jalan; peringatan ditulis oleh pemanggil.
     */
    private fun trailSkipSet(scores: List<Double>): Set<Int> {
        if (scores.size < MIN_FRAMES_AFTER_TRAIL_SKIP) return emptySet()
        val sorted = scores.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val threshold = max(TRAIL_SKIP_ELONG_MIN, median * TRAIL_SKIP_OUTLIER_FACTOR)
        val skips = scores.indices.filter { scores[it] > threshold }.toSet()
        return if (scores.size - skips.size < MIN_FRAMES_AFTER_TRAIL_SKIP) emptySet() else skips
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
            // Floor konsisten dengan jalur streaming (sama-sama punya lantai 2).
            val sigma = max(max(2.0, 0.015 * dataRange), sqrt(variance))
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
        percentileStretch(floatImage)
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

        // ===== BAGIAN 13: POST-PROCESSING STAGES =====
        // Stage 1: STACKED_LINEAR (already done above: LPR, vignette, sky brightness)

        // Stage 2: BACKGROUND_CORRECTED -- per-channel gradient removal + neutralization
        // Only apply if LPR was NOT already applied (avoid double subtraction)
        if (!lightPollutionReduction || lprStrength <= 0.001f) {
            onProgress(progressBase + 0.64f * span, "Menghapus gradien residual (per-channel)...")
            removeGradientPostStack(stacked, 0.5f)
        }
        // Background neutralization: conservative, optional (skip for astro)
        if (skyMask == null) {
            onProgress(progressBase + 0.65f * span, "Menetralkan warna latar...")
            neutralizeBackgroundChannels(stacked, 0.3f)
        }

        // Stage 3: STRETCHED -- color space conversion, brightness stretch
        // (autoBrightness already applied above as percentileStretch)

        // Stage 4: FINAL_ENHANCED -- color space, upscale, sharpen
        // (handled below)

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


    // =====================================================================
    // Sequator-equivalent pipeline: polynomial background extraction,
    // percentile stretch, gradient removal, background neutralization
    // =====================================================================

    /**
     * Polynomial sky background extraction (Sequator-equivalent).
     * Fits a 2D quadratic surface to background pixels via least-squares,
     * then up-samples the smooth model to full resolution. This removes
     * light pollution gradients BEFORE star detection, drastically improving
     * alignment accuracy.
     */
    private fun extractSkyBackground(input: Mat, sampleStep: Int = 16): Mat? {
        return try {
            val w = input.cols()
            val h = input.rows()
            if (w < 32 || h < 32) return null

            val sw = (w / sampleStep).coerceAtLeast(4)
            val sh = (h / sampleStep).coerceAtLeast(4)
            val small = Mat()
            if (input.depth() == CvType.CV_32F) {
                Imgproc.resize(input, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                val f32 = Mat()
                input.convertTo(f32, CvType.CV_32F)
                Imgproc.resize(f32, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                f32.release()
            }

            val cellW = sw / 4
            val cellH = sh / 4
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            val zs = ArrayList<Double>()
            for (cy in 0 until 4) {
                for (cx in 0 until 4) {
                    val x0 = cx * cellW
                    val y0 = cy * cellH
                    val x1 = minOf((cx + 1) * cellW, sw)
                    val y1 = minOf((cy + 1) * cellH, sh)
                    if (x1 <= x0 || y1 <= y0) continue
                    val cell = Mat(small, org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0))
                    val sorted = Mat()
                    Core.sort(cell.reshape(1, 1), sorted, Core.SORT_EVERY_ROW or Core.SORT_ASCENDING)
                    val n = sorted.cols()
                    val median = if (n > 0) sorted.get(0, n / 2)[0] else 0.0
                    sorted.release()
                    xs.add((x0 + x1) / 2.0)
                    ys.add((y0 + y1) / 2.0)
                    zs.add(median)
                }
            }
            small.release()

            if (xs.size < 6) return null

            val xNorm = DoubleArray(xs.size) { xs[it] / sw }
            val yNorm = DoubleArray(xs.size) { ys[it] / sh }
            val zArr = DoubleArray(xs.size) { zs[it] }

            val nPts = xs.size
            val nCoeffs = 6
            val ATA = Array(nCoeffs) { DoubleArray(nCoeffs) }
            val ATb = DoubleArray(nCoeffs)
            for (i in 0 until nPts) {
                val x = xNorm[i]
                val y = yNorm[i]
                val basis = doubleArrayOf(1.0, x, y, x * x, x * y, y * y)
                for (r in 0 until nCoeffs) {
                    ATb[r] += basis[r] * zArr[i]
                    for (c in r until nCoeffs) {
                        ATA[r][c] += basis[r] * basis[c]
                    }
                }
            }
            for (r in 0 until nCoeffs) {
                for (c in 0 until r) {
                    ATA[r][c] = ATA[c][r]
                }
            }

            val aug = Array(nCoeffs) { r -> DoubleArray(nCoeffs + 1) { c -> if (c < nCoeffs) ATA[r][c] else ATb[r] } }
            for (col in 0 until nCoeffs) {
                var maxRow = col
                for (row in col + 1 until nCoeffs) {
                    if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
                }
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
                val pivot = aug[col][col]
                if (abs(pivot) < 1e-12) return null
                for (c in col until nCoeffs + 1) aug[col][c] /= pivot
                for (row in 0 until nCoeffs) {
                    if (row == col) continue
                    val factor = aug[row][col]
                    for (c in col until nCoeffs + 1) aug[row][c] -= factor * aug[col][c]
                }
            }
            val coeffs = DoubleArray(nCoeffs) { aug[it][nCoeffs] }

            val modelSw = (w / 4).coerceAtLeast(4)
            val modelSh = (h / 4).coerceAtLeast(4)
            val modelData = FloatArray(modelSw * modelSh)
            for (my in 0 until modelSh) {
                val ny = my.toDouble() / modelSh
                for (mx in 0 until modelSw) {
                    val nx = mx.toDouble() / modelSw
                    modelData[my * modelSw + mx] = (coeffs[0] + coeffs[1] * nx + coeffs[2] * ny +
                        coeffs[3] * nx * nx + coeffs[4] * nx * ny + coeffs[5] * ny * ny).toFloat()
                }
            }
            val modelMat = Mat(modelSh, modelSw, CvType.CV_32F)
            modelMat.put(0, 0, modelData)

            val bg = Mat()
            Imgproc.resize(modelMat, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            modelMat.release()
            bg
        } catch (t: Throwable) {
            Log.w(TAG, "extractSkyBackground gagal: ${t.message}")
            null
        }
    }

    /**
     * Subtract sky background model from a BGR float image.
     */
    private fun subtractSkyBackgroundFromBgr(mat: Mat, bg: Mat) {
        val bg3 = Mat()
        Imgproc.cvtColor(bg, bg3, Imgproc.COLOR_GRAY2BGR)
        Core.subtract(mat, bg3, mat)
        Core.max(mat, Scalar.all(0.0), mat)
        Core.min(mat, Scalar.all(255.0), mat)
        bg3.release()
    }

    /**
     * BAGIAN 10: Extract sky background PER CHANNEL (R, G, B separately).
     * Returns a BGR Mat with separate background models for each channel.
     * This prevents color shift / green cast that grayscale background removal causes.
     */
    private fun extractSkyBackgroundPerChannel(mat: Mat, sampleStep: Int = 16): Mat? {
        try {
            val channels = ArrayList<Mat>()
            Core.split(mat, channels)
            val bgChannels = ArrayList<Mat>()
            for (ch in channels) {
                val bg = extractSkyBackground(ch, sampleStep)
                if (bg != null) {
                    bgChannels.add(bg)
                } else {
                    // Fallback: use zeros if channel extraction fails
                    bgChannels.add(Mat.zeros(ch.size(), ch.type()))
                }
            }
            val result = Mat()
            Core.merge(bgChannels, result)
            channels.forEach { it.release() }
            bgChannels.forEach { it.release() }
            return result
        } catch (t: Throwable) {
            Log.w(TAG, "extractSkyBackgroundPerChannel gagal: ${t.message}")
            return null
        }
    }

    /**
     * BAGIAN 10 + 11: Subtract per-channel sky background with strength control.
     * strength: 0.0 = no subtraction, 1.0 = full subtraction.
     * Uses mask to protect stars/bright objects from being subtracted.
     */
    private fun subtractSkyBackgroundPerChannel(
        mat: Mat,
        bgModel: Mat,
        strength: Float = 1.0f,
    ) {
        if (strength <= 0.001f) return
        try {
            // Create star mask: protect bright pixels (stars, bright objects)
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            val starMask = Mat()
            Imgproc.threshold(gray, starMask, 60.0, 255.0, Imgproc.THRESH_BINARY)
            gray.release()
            // Dilate star mask to protect halo
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.dilate(starMask, starMask, kernel)
            kernel.release()
            // Invert: 0 = star region (don't subtract), 255 = background (subtract)
            val bgMask = Mat()
            Core.bitwise_not(starMask, bgMask)
            starMask.release()

            // Subtract per-channel with strength
            val subtracted = Mat()
            Core.subtract(mat, bgModel, subtracted)
            Core.multiply(subtracted, Scalar.all(strength.toDouble()), subtracted)
            Core.add(mat, subtracted, mat, bgMask, CvType.CV_32F)
            subtracted.release()
            bgMask.release()
            Core.max(mat, Scalar.all(0.0), mat)
            Core.min(mat, Scalar.all(255.0), mat)
        } catch (t: Throwable) {
            Log.w(TAG, "subtractSkyBackgroundPerChannel gagal: ${t.message}")
        }
    }

    /**
     * Percentile-based asymmetric histogram stretch (Sequator-equivalent).
     * Maps [lowPct, highPct] percentiles to [0, 255] with sigmoid midtone boost.
     */
    private fun percentileStretch(floatImage: Mat, lowPct: Double = 0.5, highPct: Double = 99.5) {
        val width = floatImage.cols()
        val height = floatImage.rows()
        val totalPixels = width * height
        val data = FloatArray(totalPixels * 3)
        floatImage.get(0, 0, data)

        val lumHist = IntArray(256)
        var i = 0
        while (i < data.size) {
            val r = data[i + 2].coerceIn(0f, 255f)
            val g = data[i + 1].coerceIn(0f, 255f)
            val b = data[i].coerceIn(0f, 255f)
            val lum = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            lumHist[lum]++
            i += 3
        }

        val lowCount = (totalPixels * lowPct / 100.0).toInt()
        val highCount = (totalPixels * highPct / 100.0).toInt()
        var cumSum = 0
        var blackPoint = 0
        var whitePoint = 255
        for (v in 0..255) {
            cumSum += lumHist[v]
            if (cumSum >= lowCount && blackPoint == 0) blackPoint = v
            if (cumSum >= highCount && whitePoint == 255) whitePoint = v
        }
        if (whitePoint <= blackPoint) whitePoint = blackPoint + 1

        Log.i(TAG, "Percentile stretch: black=$blackPoint, white=$whitePoint (${lowPct}%-${highPct}%)")

        val range = (whitePoint - blackPoint).toDouble()
        i = 0
        while (i < data.size) {
            for (c in 0..2) {
                val v = data[i + c].coerceIn(0f, 255f)
                var stretched = ((v - blackPoint) / range * 255.0).coerceIn(0.0, 255.0)
                val norm = stretched / 255.0
                val boosted = 255.0 / (1.0 + Math.exp(-4.0 * (norm - 0.5)))
                data[i + c] = (0.9 * stretched + 0.1 * boosted).toFloat()
            }
            i += 3
        }
        floatImage.put(0, 0, data)
    }

    /**
     * Second-pass polynomial gradient removal on stacked image.
     */
    /**
     * BAGIAN 11: Post-stack gradient removal using per-channel background model.
     * strength: 0.0 = no removal, 1.0 = full removal.
     * Default conservative (0.5) to avoid over-subtraction.
     */
    private fun removeGradientPostStack(floatImage: Mat, strength: Float = 0.5f) {
        val bgModel = extractSkyBackgroundPerChannel(floatImage, sampleStep = 8)
        if (bgModel != null) {
            subtractSkyBackgroundPerChannel(floatImage, bgModel, strength)
            bgModel.release()
        }
    }

    /**
     * Background neutralization: subtract per-channel background offset
     * so the average background is neutral gray (prevents color cast).
     */
    private fun neutralizeBackgroundChannels(floatImage: Mat, strength: Float = 1.0f) {
        val width = floatImage.cols()
        val height = floatImage.rows()
        val totalPixels = width * height
        val data = FloatArray(totalPixels * 3)
        floatImage.get(0, 0, data)

        val channelSorted = Array(3) { IntArray(totalPixels) }
        for (c in 0..2) {
            for (p in 0 until totalPixels) {
                channelSorted[c][p] = data[p * 3 + c].roundToInt().coerceIn(0, 255)
            }
            channelSorted[c].sort()
        }
        val bgIdx = (totalPixels * 0.02).toInt().coerceIn(0, totalPixels - 1)
        val bgB = channelSorted[0][bgIdx].toDouble()
        val bgG = channelSorted[1][bgIdx].toDouble()
        val bgR = channelSorted[2][bgIdx].toDouble()
        val bgAvg = (bgB + bgG + bgR) / 3.0

        val offsets = doubleArrayOf(bgB - bgAvg, bgG - bgAvg, bgR - bgAvg)
        var i = 0
        while (i < data.size) {
            for (c in 0..2) {
                val original = data[i + c]
                val corrected = (original - (offsets[c] * strength).toFloat()).coerceIn(0f, 255f)
                data[i + c] = original + (corrected - original) * strength
            }
            i += 3
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

    // =====================================================================
    // DEBUG / DIAGNOSTIC PIPELINE
    // Only active when called from debug builds or internal test harness.
    // =====================================================================

    /**
     * RAW_STACK_TEST: Minimal pipeline for diagnosing stacking issues.
     * INPUT → decode → alignment → MEDIAN STACK → output.
     * All enhancements/calibrations disabled.
     */
    fun rawStackTest(
        context: Context,
        bitmaps: List<Bitmap>,
        onProgress: (Float, String) -> Unit,
    ): StackResult {
        require(bitmaps.size >= 2) { "Butuh minimal 2 foto untuk raw stack test." }
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "rawStackTest called in release build — falling back to normal stack")
            return stack(context, bitmaps, astroMode = true, lightenMode = false, medianMode = true,
                upscale2x = false, sharpenStrength = 0f, vignetteCorrection = false, vignetteStrength = 0f,
                lightPollutionReduction = false, lprStrength = 0f, skyBrightness = 0f,
                kappa = 2.0, kappaPasses = 1, exposureNormalize = false, removeHotPixels = false,
                enhanceStarColor = false, starColorStrength = 0f, freezeGround = false,
                horizonFraction = 0.5f, autoSkyMask = false, saveTiff = false, autoBrightness = false,
                mergePixels = false, hdr = false, onProgress = onProgress)
        }
        ensureIccLoaded(context)

        val frameMats = ArrayList<Mat>(bitmaps.size)
        val grays = ArrayList<Mat>(bitmaps.size)
        val aligned = ArrayList<Mat>(bitmaps.size)
        var alignedReference: Mat? = null

        try {
            // STEP 1: Decode only — no dark, no flat, no hot pixel removal
            bitmaps.forEach { bmp ->
                var mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                frameMats.add(mat)
                val gray = Mat()
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
                grays.add(gray)
            }
            Log.i(TAG, "RAW_STACK_TEST: ${frameMats.size} frames decoded.")

            // STEP 2: Reference selection — pick frame with most stars
            val refIndex = pickReferenceIndex(grays, astroMode = true, excluded = emptySet(), trailScores = null)
            val reference = frameMats[refIndex]
            val referenceGray = grays[refIndex]
            aligned.add(reference)
            alignedReference = reference
            Log.i(TAG, "RAW_STACK_TEST: Reference frame #$refIndex selected.")

            // STEP 3: Alignment — star-based only, no ECC fallback
            val eccCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-4)
            var usedFrames = 1
            var totalStarsDetected = 0
            var totalInliers = 0
            var totalRejected = 0

            for (i in frameMats.indices) {
                if (i == refIndex) continue
                onProgress(0.1f + 0.6f * usedFrames / (frameMats.size - 1),
                    "Aligning frame ${usedFrames + 1}/${frameMats.size} (raw test)...")

                val grayRef = sameSizeAsReference(referenceGray, grays[i])
                val skyWarp = starWarp(referenceGray, grayRef)
                    ?: eccWarp(referenceGray, grayRef, Video.MOTION_EUCLIDEAN, eccCriteria, 0.15)
                if (grayRef !== grays[i]) grayRef.release()

                if (skyWarp != null) {
                    val outSky = Mat()
                    Imgproc.warpAffine(frameMats[i], outSky, skyWarp, reference.size(),
                        Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP)
                    skyWarp.release()
                    val outFloat = Mat()
                    outSky.convertTo(outFloat, CvType.CV_32FC3)
                    outSky.release()
                    aligned.add(outFloat)
                    usedFrames++

                    // Log alignment metrics
                    val refStars = findStarBlobs(referenceGray)
                    val movStars = findStarBlobs(grays[i])
                    totalStarsDetected += refStars.size + movStars.size
                    Log.i(TAG, "RAW_STACK_TEST frame #$i: ALIGNED (refStars=${refStars.size}, movStars=${movStars.size})")
                } else {
                    totalRejected++
                    Log.w(TAG, "RAW_STACK_TEST frame #$i: REJECTED (alignment failed)")
                }
            }

            if (usedFrames < 2) {
                error("Raw stack test: insufficient aligned frames ($usedFrames)")
            }

            Log.i(TAG, "RAW_STACK_TEST: $usedFrames frames aligned, $totalRejected rejected.")
            Log.i(TAG, "RAW_STACK_TEST: Total stars detected: $totalStarsDetected")

            // STEP 4: Median stack — no kappa-sigma, no exposure normalization
            onProgress(0.75f, "Median stacking ${usedFrames} frames (raw test)...")
            val stacked = medianStack(aligned, DoubleArray(aligned.size) { 1.0 })

            // STEP 5: Output — no post-processing, no enhancement
            onProgress(0.95f, "Saving raw stack result...")
            val outBitmap = Bitmap.createBitmap(stacked.cols(), stacked.rows(), Bitmap.Config.ARGB_8888)
            val out8 = Mat()
            stacked.convertTo(out8, CvType.CV_8UC3)
            stacked.release()
            Imgproc.cvtColor(out8, out8, Imgproc.COLOR_BGR2RGBA)
            Utils.matToBitmap(out8, outBitmap)
            out8.release()

            return StackResult(outBitmap, usedFrames, null, 0, 0)
        } finally {
            frameMats.forEach { it.release() }
            grays.forEach { it.release() }
            aligned.forEach { if (it !== alignedReference) it.release() }
        }
    }

    /**
     * Detailed alignment diagnostics for a single frame pair.
     * Logs: star count, inlier ratio, transform matrix, residual, elongation.
     */
    fun diagnoseAlignment(
        reference: Mat,
        moving: Mat,
        frameIndex: Int,
    ): AlignmentDiagnostic {
        val refGray = Mat()
        Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY)
        val movGray = Mat()
        Imgproc.cvtColor(moving, movGray, Imgproc.COLOR_BGR2GRAY)

        val refStars = findStarBlobs(refGray)
        val movStars = findStarBlobs(movGray)
        refGray.release()
        movGray.release()

        val refElongations = refStars.map { it.elongation }
        val movElongations = movStars.map { it.elongation }
        val avgRefElong = if (refElongations.isNotEmpty()) refElongations.average() else 0.0
        val avgMovElong = if (movElongations.isNotEmpty()) movElongations.average() else 0.0
        val refRoundCount = refStars.count { it.elongation <= ROUND_STAR_ELONG_MAX }
        val movRoundCount = movStars.count { it.elongation <= ROUND_STAR_ELONG_MAX }

        // Try star-based alignment
        val warp = starWarp(reference, moving)
        val warpValid = warp != null && !warp.empty()

        var tx = 0.0; var ty = 0.0; var rotation = 0.0; var scale = 1.0
        var residual = 0.0; var inlierCount = 0; var matchCount = 0
        var inlierRatio = 0.0

        if (warpValid) {
            val ws = warpStats(warp!!)
            tx = ws.tx; ty = ws.ty; rotation = ws.rotationDeg; scale = ws.scale

            // Count matches and inliers
            val refAnchors = refStars.filter { it.elongation <= ROUND_STAR_ELONG_MAX }.ifEmpty { refStars }
            val movAnchors = movStars.filter { it.elongation <= ROUND_STAR_ELONG_MAX }.ifEmpty { movStars }

            val pairRef = ArrayList<Point>()
            val pairMov = ArrayList<Point>()
            val used = BooleanArray(movAnchors.size)

            for (rs in refAnchors) {
                var bestIdx = -1; var bestDist = MAX_STAR_MATCH_DIST
                var secondIdx = -1; var secondDist = MAX_STAR_MATCH_DIST
                for (j in movAnchors.indices) {
                    if (used[j]) continue
                    val d = hypot(rs.x - movAnchors[j].x, rs.y - movAnchors[j].y)
                    if (d < bestDist) { secondIdx = bestIdx; secondDist = bestDist; bestDist = d; bestIdx = j }
                    else if (d < secondDist) { secondIdx = j; secondDist = d }
                }
                if (bestIdx >= 0 && (secondIdx < 0 || bestDist < secondDist * STAR_MATCH_RATIO)) {
                    used[bestIdx] = true
                    pairRef.add(Point(rs.x, rs.y))
                    pairMov.add(Point(movAnchors[bestIdx].x, movAnchors[bestIdx].y))
                }
            }

            matchCount = pairRef.size
            if (matchCount >= MIN_STARS_COUNT) {
                val from = MatOfPoint2f(*pairRef.toTypedArray())
                val to = MatOfPoint2f(*pairMov.toTypedArray())
                val inliers = MatOfByte()
                Calib3d.estimateAffinePartial2D(from, to, inliers, Calib3d.RANSAC, 3.0)
                val inlierFlags = inliers.toArray()
                inlierCount = inlierFlags.count { it.toInt() != 0 }
                inlierRatio = if (matchCount > 0) inlierCount.toDouble() / matchCount else 0.0
                residual = starAlignResidualPx(warp, pairRef, pairMov, inlierFlags) ?: 0.0
                from.release(); to.release(); inliers.release()
            }
            warp.release()
        }

        val confidence = when {
            !warpValid -> 0.0
            inlierRatio < 0.5 -> inlierRatio * 0.5
            residual > MAX_STAR_ALIGN_RESIDUAL_PX -> 0.3
            else -> (inlierRatio * 0.6 + (1.0 - (residual / MAX_STAR_ALIGN_RESIDUAL_PX).coerceIn(0.0, 1.0)) * 0.4)
        }

        Log.i(TAG, """
            |FRAME $frameIndex ALIGNMENT DIAGNOSTIC
            |  refStars=${refStars.size} (round=$refRoundCount, avgElong=${"%.2f".format(avgRefElong)})
            |  movStars=${movStars.size} (round=$movRoundCount, avgElong=${"%.2f".format(avgMovElong)})
            |  matches=$matchCount, inliers=$inlierCount (${"%.1f".format(inlierRatio * 100)}%)
            |  translation=(${"%.1f".format(tx)}, ${"%.1f".format(ty)})
            |  rotation=${"%.3f".format(rotation)}°, scale=${"%.4f".format(scale)}
            |  residual=${"%.3f".format(residual)}px
            |  confidence=${"%.3f".format(confidence)}
            |  warpValid=$warpValid
        """.trimMargin())

        return AlignmentDiagnostic(
            frameIndex = frameIndex,
            refStarCount = refStars.size,
            movStarCount = movStars.size,
            refRoundCount = refRoundCount,
            movRoundCount = movRoundCount,
            avgRefElongation = avgRefElong,
            avgMovElongation = avgMovElong,
            matchCount = matchCount,
            inlierCount = inlierCount,
            inlierRatio = inlierRatio,
            translationX = tx,
            translationY = ty,
            rotationDeg = rotation,
            scale = scale,
            residualPx = residual,
            confidence = confidence,
            warpValid = warpValid,
        )
    }

    data class AlignmentDiagnostic(
        val frameIndex: Int,
        val refStarCount: Int,
        val movStarCount: Int,
        val refRoundCount: Int,
        val movRoundCount: Int,
        val avgRefElongation: Double,
        val avgMovElongation: Double,
        val matchCount: Int,
        val inlierCount: Int,
        val inlierRatio: Double,
        val translationX: Double,
        val translationY: Double,
        val rotationDeg: Double,
        val scale: Double,
        val residualPx: Double,
        val confidence: Double,
        val warpValid: Boolean,
    )

    /**
     * Generate debug artifact images for visual inspection.
     * Only call from debug builds — writes to app-internal storage.
     */
    fun generateDebugArtifacts(
        context: Context,
        reference: Mat,
        alignedFrames: List<Pair<Int, Mat>>,
        stackedMedian: Mat,
        stackedKappaSigma: Mat?,
        backgroundModel: Mat? = null,
        backgroundCorrected: Mat? = null,
        finalImage: Mat? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val dir = context.getExternalFilesDir(null) ?: return
        val debugDir = java.io.File(dir, "debug")
        debugDir.mkdirs()

        fun saveDebug(mat: Mat, name: String) {
            try {
                val out = java.io.File(debugDir, "$name.png")
                val eight = Mat()
                mat.convertTo(eight, CvType.CV_8UC3)
                Imgcodecs.imwrite(out.absolutePath, eight)
                eight.release()
                Log.i(TAG, "Debug artifact saved: $name (${out.length()} bytes)")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to save debug artifact $name: ${t.message}")
            }
        }

        fun saveDebugChannel(mat: Mat, channel: Int, name: String) {
            try {
                val channels = ArrayList<Mat>()
                Core.split(mat, channels)
                val out = java.io.File(debugDir, "$name.png")
                Imgcodecs.imwrite(out.absolutePath, channels[channel])
                channels.forEach { it.release() }
                Log.i(TAG, "Debug artifact saved: $name (${out.length()} bytes)")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to save debug artifact $name: ${t.message}")
            }
        }

        // 1. Reference frame
        saveDebug(reference, "debug_01_reference")

        // 2. Detected stars overlay on reference
        val refGray = Mat()
        Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY)
        val refStars = findStarBlobs(refGray)
        refGray.release()
        val starOverlay = reference.clone()
        for (star in refStars) {
            val color = if (star.elongation <= ROUND_STAR_ELONG_MAX) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0)
            Imgproc.circle(starOverlay, Point(star.x, star.y), 4, color, 1)
        }
        saveDebug(starOverlay, "debug_02_stars_reference")
        starOverlay.release()

        // 3. Rejected stars overlay (stars with elongation > threshold)
        val rejectedOverlay = reference.clone()
        val rejectedStars = refStars.filter { it.elongation > ROUND_STAR_ELONG_MAX }
        for (star in rejectedStars) {
            Imgproc.circle(rejectedOverlay, Point(star.x, star.y), 4, Scalar(0.0, 0.0, 255.0), 1)
        }
        saveDebug(rejectedOverlay, "debug_02b_stars_rejected_reference")
        rejectedOverlay.release()

        // 4. Aligned frames with star overlay
        for ((idx, frame) in alignedFrames) {
            val movGray = Mat()
            Imgproc.cvtColor(frame, movGray, Imgproc.COLOR_BGR2GRAY)
            val movStars = findStarBlobs(movGray)
            movGray.release()
            val overlay = frame.clone()
            for (star in movStars) {
                val color = if (star.elongation <= ROUND_STAR_ELONG_MAX) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0)
                Imgproc.circle(overlay, Point(star.x, star.y), 4, color, 1)
            }
            saveDebug(overlay, "debug_03_aligned_frame_%02d".format(idx))
            overlay.release()
        }

        // 5. Median stack
        saveDebug(stackedMedian, "debug_04_median")

        // 6. Kappa-sigma stack (if available)
        if (stackedKappaSigma != null) {
            saveDebug(stackedKappaSigma, "debug_05_kappa_sigma")
        }

        // 7. Background model (per-channel R, G, B)
        if (backgroundModel != null) {
            saveDebugChannel(backgroundModel, 2, "debug_06_background_model_R")
            saveDebugChannel(backgroundModel, 1, "debug_07_background_model_G")
            saveDebugChannel(backgroundModel, 0, "debug_08_background_model_B")
        }

        // 8. Background corrected image
        if (backgroundCorrected != null) {
            saveDebug(backgroundCorrected, "debug_09_background_corrected")
        }

        // 9. Final image
        if (finalImage != null) {
            saveDebug(finalImage, "debug_10_final")
        }

        Log.i(TAG, "Debug artifacts generated in: ${debugDir.absolutePath}")
    }

}
