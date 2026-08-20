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

package com.stackscan.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stackscan.MainActivity
import com.stackscan.R
import com.stackscan.processing.BitmapLoader
import com.stackscan.processing.ImageSaver
import com.stackscan.processing.ImageStacker
import com.stackscan.processing.OutputColorSpace
import com.stackscan.ui.StackAlgorithm
import com.stackscan.ui.StackMode
import com.stackscan.ui.StackQuality
import com.stackscan.ui.StackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Menjalankan stacking di latar belakang lewat WorkManager, lengkap dengan
 * notifikasi progres. Aplikasi boleh ditutup/dimatikan layarnya — pekerjaan
 * tetap dijalankan sistem dan hasilnya muncul saat dibuka kembali.
 */
class StackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriStrings = inputData.getStringArray(KEY_URIS).orEmpty()
        if (uriStrings.size < 2) {
            return Result.failure(workDataOf(KEY_ERROR to "Butuh minimal 2 foto untuk stacking."))
        }
        val uris = uriStrings.map { Uri.parse(it) }
        val s = decodeSettings(inputData)
        val startedAt = System.currentTimeMillis()

        safeForeground(0f, "Menyiapkan gambar...")
        return try {
            var lastUpdate = 0L
            val onProgress = { fraction: Float, label: String ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 250L) {
                    lastUpdate = now
                    val data = workDataOf(KEY_PROGRESS to fraction, KEY_PROGRESS_LABEL to label)
                    setProgressAsync(data)
                    updateProgressNotification(fraction, label)
                }
            }

            val effectiveSky = s.skyBrightness + if (s.darkScene) 0.35f else 0f
            val effectiveLpr = if (s.darkScene) maxOf(s.lprStrength, 0.75f) else s.lprStrength
            val effectiveExpNorm = s.exposureNormalize || s.darkScene

            val result = if (uris.size > ImageStacker.MAX_FRAMES ||
                s.quality == StackQuality.FULL ||
                s.trailsMode ||
                s.algorithm == StackAlgorithm.ALIGN ||
                uris.any { BitmapLoader.isRaw(it) } ||
                !ImageStacker.batchFitsMemory(uris.size, s.quality.workingSize)
            ) {
                withContext(Dispatchers.Default) {
                    ImageStacker.stackFromUris(
                        context = applicationContext,
                        uris = uris,
                        darkUris = s.darkUris,
                        flatUris = s.flatUris,
                        trailsMode = s.trailsMode,
                        astroMode = s.mode == StackMode.ASTRO,
                        lightenMode = s.algorithm == StackAlgorithm.LIGHTEN,
                        medianMode = s.algorithm == StackAlgorithm.MEDIAN,
                        maxDim = s.quality.workingSize,
                        upscale2x = s.upscale,
                        sharpenStrength = s.sharpenStrength,
                        vignetteCorrection = s.vignetteCorrection,
                        vignetteStrength = s.vignetteStrength,
                        lightPollutionReduction = s.lightPollutionReduction,
                        lprStrength = effectiveLpr,
                        skyBrightness = effectiveSky,
                        kappa = s.kappa.toDouble(),
                        kappaPasses = s.kappaPasses,
                        exposureNormalize = effectiveExpNorm,
                        removeHotPixels = s.removeHotPixels,
                        enhanceStarColor = s.enhanceStarColor,
                        starColorStrength = s.starColorStrength,
                        freezeGround = s.freezeGround,
                        horizonFraction = s.horizonFraction,
                        autoSkyMask = s.autoSkyMask,
                        saveTiff = s.saveTiff,
                        autoBrightness = s.autoBrightness,
                        mergePixels = s.mergePixels,
                        hdr = s.hdr,
                        wbTemperatureK = s.wbTemperatureK,
                        colorSpace = s.colorSpace,
                        alignOnly = s.algorithm == StackAlgorithm.ALIGN,
                        onProgress = onProgress,
                    )
                }
            } else {
                val bitmaps = withContext(Dispatchers.Default) {
                    BitmapLoader.loadBitmaps(applicationContext, uris, s.quality.workingSize)
                }
                val darkBitmaps = if (s.darkUris.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        BitmapLoader.loadBitmaps(applicationContext, s.darkUris, s.quality.workingSize)
                    }
                } else {
                    emptyList()
                }
                val flatBitmaps = if (s.flatUris.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        BitmapLoader.loadBitmaps(applicationContext, s.flatUris, s.quality.workingSize)
                    }
                } else {
                    emptyList()
                }
                val stacked = withContext(Dispatchers.Default) {
                    ImageStacker.stack(
                        context = applicationContext,
                        bitmaps = bitmaps,
                        darkBitmaps = darkBitmaps,
                        flatBitmaps = flatBitmaps,
                        astroMode = s.mode == StackMode.ASTRO,
                        lightenMode = s.algorithm == StackAlgorithm.LIGHTEN,
                        medianMode = s.algorithm == StackAlgorithm.MEDIAN,
                        upscale2x = s.upscale,
                        sharpenStrength = s.sharpenStrength,
                        vignetteCorrection = s.vignetteCorrection,
                        vignetteStrength = s.vignetteStrength,
                        lightPollutionReduction = s.lightPollutionReduction,
                        lprStrength = effectiveLpr,
                        skyBrightness = effectiveSky,
                        kappa = s.kappa.toDouble(),
                        kappaPasses = s.kappaPasses,
                        exposureNormalize = effectiveExpNorm,
                        removeHotPixels = s.removeHotPixels,
                        enhanceStarColor = s.enhanceStarColor,
                        starColorStrength = s.starColorStrength,
                        freezeGround = s.freezeGround,
                        horizonFraction = s.horizonFraction,
                        autoSkyMask = s.autoSkyMask,
                        saveTiff = s.saveTiff,
                        autoBrightness = s.autoBrightness,
                        mergePixels = s.mergePixels,
                        hdr = s.hdr,
                        wbTemperatureK = s.wbTemperatureK,
                        colorSpace = s.colorSpace,
                        onProgress = onProgress,
                    )
                }
                bitmaps.forEach { it.recycle() }
                darkBitmaps.forEach { it.recycle() }
                flatBitmaps.forEach { it.recycle() }
                stacked
            }

            val uri = withContext(Dispatchers.IO) { ImageSaver.save(applicationContext, result.bitmap) }
            // Align-only: frame disejajarkan sudah disimpan satu-per-satu di ImageStacker
            // (stream-save + recycle, anti-OOM). result.bitmap di sini = frame pertama.
            val alignedCount = result.alignedSavedFrames
            val tiffUri = result.tiffBytes?.let { tiff ->
                withContext(Dispatchers.IO) {
                    ImageSaver.saveTiff(applicationContext, tiff, result.tiffWidth, result.tiffHeight)
                }
            }
            val sizeBytes = withContext(Dispatchers.IO) {
                applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    it.length
                } ?: 0L
            }

            val output = workDataOf(
                KEY_RESULT_URI to uri.toString(),
                KEY_TIFF_URI to tiffUri?.toString(),
                KEY_ALIGNED_COUNT to alignedCount,
                KEY_USED_FRAMES to result.usedFrames,
                KEY_RESULT_WIDTH to result.bitmap.width,
                KEY_RESULT_HEIGHT to result.bitmap.height,
                KEY_RESULT_SIZE to sizeBytes,
                KEY_PROCESSING_MILLIS to (System.currentTimeMillis() - startedAt),
            )
            postDoneNotification(true, output)
            Result.success(output)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Pekerjaan dibatalkan user: jangan laporkan sukses/palsukan error.
            throw e
        } catch (e: OutOfMemoryError) {
            val output = workDataOf(
                KEY_ERROR to "Memori tidak cukup. Gunakan kualitas Cepat/HD atau kurangi jumlah foto.",
            )
            postDoneNotification(false, output)
            Result.failure(output)
        } catch (e: Exception) {
            val output = workDataOf(KEY_ERROR to (e.message ?: "Terjadi kesalahan saat memproses."))
            postDoneNotification(false, output)
            Result.failure(output)
        }
    }

    private fun decodeSettings(input: Data): StackJobSettings = StackJobSettings(
        mode = enumByName(input.getString(KEY_MODE), StackMode.entries, StackMode.GENERAL),
        algorithm = enumByName(input.getString(KEY_ALGORITHM), StackAlgorithm.entries, StackAlgorithm.REAL),
        quality = enumByName(input.getString(KEY_QUALITY), StackQuality.entries, StackQuality.HD),
        trailsMode = input.getBoolean(KEY_TRAILS, false),
        darkUris = (input.getStringArray(KEY_DARK_URIS).orEmpty()).map { Uri.parse(it) },
        flatUris = (input.getStringArray(KEY_FLAT_URIS).orEmpty()).map { Uri.parse(it) },
        upscale = input.getBoolean(KEY_UPSCALE, true),
        sharpenStrength = input.getFloat(KEY_SHARPEN, 0.6f),
        vignetteCorrection = input.getBoolean(KEY_VIGNETTE, true),
        vignetteStrength = input.getFloat(KEY_VIGNETTE_STR, 0.35f),
        lightPollutionReduction = input.getBoolean(KEY_LPR, true),
        lprStrength = input.getFloat(KEY_LPR_STR, 0.6f),
        skyBrightness = input.getFloat(KEY_SKY, 0f),
        saveTiff = input.getBoolean(KEY_TIFF, true),
        autoBrightness = input.getBoolean(KEY_AUTO_BRIGHT, false),
        mergePixels = input.getBoolean(KEY_MERGE, false),
        hdr = input.getBoolean(KEY_HDR, false),
        wbTemperatureK = input.getInt(KEY_WB_TEMP, 6500),
        colorSpace = enumByName(input.getString(KEY_COLOR_SPACE), OutputColorSpace.entries, OutputColorSpace.SRGB),
        kappa = input.getFloat(KEY_KAPPA, 2.0f),
        kappaPasses = input.getInt(KEY_KAPPA_PASSES, 3),
        exposureNormalize = input.getBoolean(KEY_EXP_NORM, true),
        removeHotPixels = input.getBoolean(KEY_HOT, true),
        enhanceStarColor = input.getBoolean(KEY_STAR_COLOR, false),
        starColorStrength = input.getFloat(KEY_STAR_COLOR_STR, 0.5f),
        freezeGround = input.getBoolean(KEY_FREEZE, false),
        horizonFraction = input.getFloat(KEY_HORIZON, 0.5f),
        autoSkyMask = input.getBoolean(KEY_AUTO_SKY_MASK, true),
        darkScene = input.getBoolean(KEY_DARK, false),
    )

    private suspend fun safeForeground(progress: Float, label: String) {
        try {
            setForeground(createForegroundInfo(progress, label))
            foregroundActive = true
        } catch (t: Throwable) {
            // OS/perangkat tidak mengizinkan FGS saat ini; progres tetap lewat setProgress.
        }
    }

    private fun updateProgressNotification(progress: Float, label: String) {
        // Perbarui notifikasi foreground yang sudah berjalan tanpa API setForegroundAsync
        // (yang bisa membuat worker gagal bila FGS tidak diizinkan di latar belakang).
        if (!foregroundActive) return
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_PROGRESS_ID, buildNotification(progress, label))
    }

    private fun createForegroundInfo(progress: Float, label: String): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_PROGRESS_ID,
            buildNotification(progress, label),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private fun buildNotification(progress: Float, label: String): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("StackScan — Menggabungkan Foto")
            .setContentText(label)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .build()
    }

    private fun postDoneNotification(success: Boolean, output: Data) {
        ensureChannel()
        val text = if (success) {
            val frames = output.getInt(KEY_USED_FRAMES, 0)
            val aligned = output.getInt(KEY_ALIGNED_COUNT, 0)
            if (aligned > 0) {
                "Selesai — $aligned frame terselaraskan & disimpan. Ketuk untuk melihat hasil."
            } else {
                "Selesai — $frames foto digabungkan. Ketuk untuk melihat hasil."
            }
        } else {
            output.getString(KEY_ERROR) ?: "Gagal memproses foto."
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            1,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) "StackScan — Selesai" else "StackScan — Gagal")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_DONE_ID, notification)
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Proses Stacking",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private data class StackJobSettings(
        val mode: StackMode,
        val algorithm: StackAlgorithm,
        val quality: StackQuality,
        val trailsMode: Boolean,
        val darkUris: List<Uri>,
        val flatUris: List<Uri>,
        val upscale: Boolean,
        val sharpenStrength: Float,
        val vignetteCorrection: Boolean,
        val vignetteStrength: Float,
        val lightPollutionReduction: Boolean,
        val lprStrength: Float,
        val skyBrightness: Float,
        val saveTiff: Boolean,
        val autoBrightness: Boolean,
        val mergePixels: Boolean,
        val hdr: Boolean,
        val wbTemperatureK: Int,
        val colorSpace: OutputColorSpace,
        val kappa: Float,
        val kappaPasses: Int,
        val exposureNormalize: Boolean,
        val removeHotPixels: Boolean,
        val enhanceStarColor: Boolean,
        val starColorStrength: Float,
        val freezeGround: Boolean,
        val horizonFraction: Float,
        val autoSkyMask: Boolean,
        val darkScene: Boolean,
    )

    private fun <T : Enum<T>> enumByName(name: String?, entries: List<T>, default: T): T =
        name?.let { n -> entries.firstOrNull { it.name == n } } ?: default

    private var foregroundActive = false

    companion object {
        const val UNIQUE_NAME = "stack_job"
        private const val CHANNEL_ID = "stackscan_progress"
        private const val NOTIFICATION_PROGRESS_ID = 1
        private const val NOTIFICATION_DONE_ID = 2

        const val KEY_URIS = "uris"
        const val KEY_DARK_URIS = "dark_uris"
        const val KEY_FLAT_URIS = "flat_uris"
        const val KEY_TRAILS = "trails"
        const val KEY_MODE = "mode"
        const val KEY_ALGORITHM = "algorithm"
        const val KEY_QUALITY = "quality"
        const val KEY_UPSCALE = "upscale"
        const val KEY_SHARPEN = "sharpen"
        const val KEY_VIGNETTE = "vignette"
        const val KEY_VIGNETTE_STR = "vignette_str"
        const val KEY_LPR = "lpr"
        const val KEY_LPR_STR = "lpr_str"
        const val KEY_SKY = "sky"
        const val KEY_TIFF = "tiff"
        const val KEY_AUTO_BRIGHT = "auto_bright"
        const val KEY_MERGE = "merge_pixels"
        const val KEY_HDR = "hdr"
        const val KEY_ALIGNED_COUNT = "aligned_count"
        const val KEY_KAPPA = "kappa"
        const val KEY_KAPPA_PASSES = "kappa_passes"
        const val KEY_EXP_NORM = "exp_norm"
        const val KEY_HOT = "hot"
        const val KEY_STAR_COLOR = "star_color"
        const val KEY_STAR_COLOR_STR = "star_color_str"
        const val KEY_FREEZE = "freeze"
        const val KEY_HORIZON = "horizon"
        const val KEY_AUTO_SKY_MASK = "auto_sky_mask"
        const val KEY_DARK = "dark"
        const val KEY_WB_TEMP = "wb_temp"
        const val KEY_COLOR_SPACE = "color_space"

        const val KEY_PROGRESS = "progress"
        const val KEY_PROGRESS_LABEL = "label"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_TIFF_URI = "tiff_uri"
        const val KEY_USED_FRAMES = "used_frames"
        const val KEY_RESULT_WIDTH = "result_width"
        const val KEY_RESULT_HEIGHT = "result_height"
        const val KEY_RESULT_SIZE = "result_size"
        const val KEY_PROCESSING_MILLIS = "millis"
        const val KEY_ERROR = "error"

        fun createRequest(state: StackUiState): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_URIS to state.selectedUris.map { it.toString() }.toTypedArray(),
                KEY_DARK_URIS to state.darkFrameUris.map { it.toString() }.toTypedArray(),
                KEY_FLAT_URIS to state.flatFrameUris.map { it.toString() }.toTypedArray(),
                KEY_TRAILS to (state.algorithm == StackAlgorithm.TRAILS),
                KEY_MODE to state.mode.name,
                KEY_ALGORITHM to state.algorithm.name,
                KEY_QUALITY to state.quality.name,
                KEY_UPSCALE to state.upscale,
                KEY_SHARPEN to state.sharpenStrength,
                KEY_VIGNETTE to state.vignetteCorrection,
                KEY_VIGNETTE_STR to state.vignetteStrength,
                KEY_LPR to state.lightPollutionReduction,
                KEY_LPR_STR to state.lprStrength,
                KEY_SKY to state.skyBrightness,
                KEY_TIFF to state.saveTiff,
                KEY_AUTO_BRIGHT to state.autoBrightness,
                KEY_MERGE to state.mergePixels,
                KEY_HDR to state.hdr,
                KEY_WB_TEMP to state.wbTemperatureK,
                KEY_COLOR_SPACE to state.colorSpace.name,
                KEY_KAPPA to state.kappa,
                KEY_KAPPA_PASSES to state.kappaPasses,
                KEY_EXP_NORM to state.exposureNormalize,
                KEY_HOT to state.removeHotPixels,
                KEY_STAR_COLOR to state.enhanceStarColor,
                KEY_STAR_COLOR_STR to state.starColorStrength,
                KEY_FREEZE to state.freezeGround,
                KEY_HORIZON to state.horizonFraction,
                KEY_AUTO_SKY_MASK to state.autoSkyMask,
                KEY_DARK to state.darkScene,
            )
            return OneTimeWorkRequestBuilder<StackWorker>()
                .setInputData(data)
                .build()
        }
    }
}
