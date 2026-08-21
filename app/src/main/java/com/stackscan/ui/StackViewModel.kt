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

package com.stackscan.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stackscan.processing.BitmapLoader
import com.stackscan.processing.ImageStacker
import com.stackscan.processing.OutputColorSpace
import com.stackscan.work.StackWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class StackMode(val label: String, val description: String) {
    GENERAL("Umum / Makro", "Cocok untuk pemandangan, teks, serangga, objek diam."),
    ASTRO("Bintang (Astro)", "Align berbasis bintang, tahan rotasi antar frame."),
}

enum class StackPreset(val label: String, val description: String) {
    GENERAL("Umum / Makro", "Objek statis sehari-hari: serangga, teks, pemandangan."),
    ASTRO("Astro (Bintang)", "Langit malam: align bintang, kappa-sigma noise reject, auto brightness — ala Sequator Ensemble."),
    DEEP_SKY("Gelap (Deep-sky)", "Langit gelap: angkat objek samar, noise rejection agresif, LPR kuat."),
    SEQUATOR("Sequator", "Lighten (Ensemble) + auto brightness — hasil mentah ala Sequator PC, tanpa koreksi tambahan."),
    MANUAL("Manual", "Semua setelan dibuka untuk diracik sendiri."),
    CUSTOM("Kustom", "Preset simpanan buatan sendiri dari setelan favorit Anda."),
}

enum class StackQuality(val label: String, val description: String, val workingSize: Int) {
    FAST("Cepat", "Hasil ±1MP, cepat & hemat memori.", 1024),
    HD("HD", "Hasil ±2MP, lebih detail.", 2048),
    FULL("Full/Asli", "Resolusi tinggi hingga 4096px, disesuaikan otomatis dengan memori perangkat — paling tajam saat di-zoom; upscale 2x otomatis mati.", 4096),
}

enum class StackAlgorithm(val label: String, val description: String) {
    REAL("Real", "Kappa-sigma: rata-rata piksel konsisten, buang outlier — hasil paling bersih."),
    LIGHTEN("Lighten", "Ambil piksel paling terang tiap posisi — bintang redup & trail bintang lebih tegas (ala Sequator)."),
    MEDIAN("Median", "Nilai tengah tiap piksel — paling tahan outlier ekstrem. Berlaku untuk ≤16 frame; Mode Pro memakai kappa-sigma."),
    TRAILS("Trails", "Star trails: tanpa alignment, piksel paling terang ditumpuk — bintang membentuk jejak melengkung."),
    ALIGN("Align only", "Tanpa menggabungkan: semua foto disejajarkan ke frame acuan lalu disimpan apa adanya — untuk diproses lanjut di aplikasi lain (ala Sequator)."),
}

data class StackUiState(
    val selectedUris: List<Uri> = emptyList(),
    val darkFrameUris: List<Uri> = emptyList(),
    val flatFrameUris: List<Uri> = emptyList(),
    val mode: StackMode = StackMode.GENERAL,
    val algorithm: StackAlgorithm = StackAlgorithm.REAL,
    val quality: StackQuality = StackQuality.HD,
    val upscale: Boolean = true,
    val sharpenStrength: Float = 0.6f,
    val vignetteCorrection: Boolean = true,
    val vignetteStrength: Float = 0.35f,
    val lightPollutionReduction: Boolean = true,
    val lprStrength: Float = 0.6f,
    val skyBrightness: Float = 0f,
    val saveTiff: Boolean = true,
    val autoBrightness: Boolean = false,
    val mergePixels: Boolean = false,
    val hdr: Boolean = false,
    val wbTemperatureK: Int = 6500,
    val colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
    val settingsOpen: Boolean = false,
    val preset: StackPreset = StackPreset.GENERAL,
    val customPresets: List<CustomPreset> = emptyList(),
    val customPresetId: String? = null,
    val kappa: Float = 2.0f,
    val kappaPasses: Int = 3,
    val exposureNormalize: Boolean = true,
    val removeHotPixels: Boolean = true,
    val enhanceStarColor: Boolean = false,
    val starColorStrength: Float = 0.5f,
    val freezeGround: Boolean = false,
    val horizonFraction: Float = 0.5f,
    val autoSkyMask: Boolean = true,
    val darkScene: Boolean = false,
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val resultUri: Uri? = null,
    val usedFrames: Int = 0,
    val resultWidth: Int = 0,
    val resultHeight: Int = 0,
    val resultSizeBytes: Long = 0L,
    val processingMillis: Long = 0L,
    val tiffUri: Uri? = null,
    val history: List<StackHistoryEntry> = emptyList(),
    val error: String? = null,
    val warning: String? = null,
)

data class CustomPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val mode: StackMode,
    val algorithm: StackAlgorithm,
    val quality: StackQuality,
    val upscale: Boolean,
    val sharpenStrength: Float,
    val vignetteCorrection: Boolean,
    val vignetteStrength: Float,
    val lightPollutionReduction: Boolean,
    val lprStrength: Float,
    val skyBrightness: Float,
    val saveTiff: Boolean,
    val kappa: Float,
    val kappaPasses: Int,
    val exposureNormalize: Boolean,
    val removeHotPixels: Boolean,
    val enhanceStarColor: Boolean,
    val starColorStrength: Float,
    val freezeGround: Boolean,
    val horizonFraction: Float,
    val darkScene: Boolean,
    val autoBrightness: Boolean,
    val mergePixels: Boolean,
    val wbTemperatureK: Int = 6500,
    val colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
) {
    fun applyTo(state: StackUiState): StackUiState = state.copy(
        mode = mode,
        algorithm = algorithm,
        quality = quality,
        upscale = upscale,
        sharpenStrength = sharpenStrength,
        vignetteCorrection = vignetteCorrection,
        vignetteStrength = vignetteStrength,
        lightPollutionReduction = lightPollutionReduction,
        lprStrength = lprStrength,
        skyBrightness = skyBrightness,
        saveTiff = saveTiff,
        kappa = kappa,
        kappaPasses = kappaPasses,
        exposureNormalize = exposureNormalize,
        removeHotPixels = removeHotPixels,
        enhanceStarColor = enhanceStarColor,
        starColorStrength = starColorStrength,
        freezeGround = freezeGround,
        horizonFraction = horizonFraction,
        darkScene = darkScene,
        autoBrightness = autoBrightness,
        mergePixels = mergePixels,
        wbTemperatureK = wbTemperatureK,
        colorSpace = colorSpace,
    )
}

data class StackHistoryEntry(
    val uri: String,
    val tiffUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val usedFrames: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val processingMillis: Long = 0L,
    val algorithm: String = "",
    val mode: String = "",
)

class StackViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(StackUiState())
    val ui: StateFlow<StackUiState> = _ui.asStateFlow()

    private fun prefs() = getApplication<Application>().getSharedPreferences(
        "stackscan_settings",
        Context.MODE_PRIVATE,
    )

    init {
        val p = prefs()
        _ui.update { state ->
            val preset = prefs().getString(KEY_PRESET, null)?.let { name ->
                StackPreset.entries.firstOrNull { it.name == name }
            } ?: if (p.getBoolean(KEY_AUTO_MODE_LEGACY, true)) {
                StackPreset.GENERAL
            } else {
                // Migrasi dari versi lama: user yang memakai Mode Manual diarahkan ke Preset Manual.
                StackPreset.MANUAL
            }
            var s = state.copy(
                preset = preset,
                customPresets = jsonToCustomPresets(p.getString(KEY_CUSTOM_PRESETS, null)),
                history = jsonToHistory(p.getString(KEY_HISTORY, null)),
                customPresetId = p.getString(KEY_CUSTOM_PRESET_ID, null),
                mode = enumValue(KEY_MODE, StackMode.entries, StackMode.GENERAL),
                algorithm = enumValue(KEY_ALGORITHM, StackAlgorithm.entries, StackAlgorithm.REAL),
                quality = enumValue(KEY_QUALITY, StackQuality.entries, StackQuality.HD),
                upscale = p.getBoolean(KEY_UPSCALE, true),
                sharpenStrength = p.getFloat(KEY_SHARPEN, 0.6f),
                vignetteCorrection = p.getBoolean(KEY_VIGNETTE, true),
                vignetteStrength = p.getFloat(KEY_VIGNETTE_STR, 0.35f),
                lightPollutionReduction = p.getBoolean(KEY_LPR, true),
                lprStrength = p.getFloat(KEY_LPR_STR, 0.6f),
                skyBrightness = p.getFloat(KEY_SKY, 0f),
                saveTiff = p.getBoolean(KEY_TIFF, true),
                autoBrightness = p.getBoolean(KEY_AUTO_BRIGHT, false),
                mergePixels = p.getBoolean(KEY_MERGE, false),
                hdr = p.getBoolean(KEY_HDR, false),
                wbTemperatureK = p.getInt(KEY_WB_TEMP, 6500),
                colorSpace = enumValue(KEY_COLOR_SPACE, OutputColorSpace.entries, OutputColorSpace.SRGB),
                kappa = p.getFloat(KEY_KAPPA, 2.0f),
                kappaPasses = p.getInt(KEY_KAPPA_PASSES, 3),
                exposureNormalize = p.getBoolean(KEY_EXP_NORM, true),
                removeHotPixels = p.getBoolean(KEY_HOT, true),
                enhanceStarColor = p.getBoolean(KEY_STAR_COLOR, false),
                starColorStrength = p.getFloat(KEY_STAR_COLOR_STR, 0.5f),
                freezeGround = p.getBoolean(KEY_FREEZE, false),
                horizonFraction = p.getFloat(KEY_HORIZON, 0.5f),
                autoSkyMask = p.getBoolean(KEY_AUTO_SKY_MASK, true),
                darkScene = p.getBoolean(KEY_DARK, false),
            )
            s = if (preset == StackPreset.MANUAL) restoreManual(s) else applyPreset(s, preset)
            s
        }
        // Sambungkan kembali ke pekerjaan stacking yang mungkin masih berjalan
        // dari proses aplikasi sebelumnya (aplikasi dibuka lagi setelah ditutup).
        observeWork(getApplication())
    }

    private fun <T : Enum<T>> enumValue(key: String, entries: List<T>, default: T): T =
        prefs().getString(key, null)?.let { name ->
            entries.firstOrNull { it.name == name }
        } ?: default

    private fun <T : Enum<T>> enumByName(name: String?, entries: List<T>, default: T): T =
        entries.firstOrNull { it.name == name } ?: default

    private fun customPresetsToJson(list: List<CustomPreset>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("createdAt", c.createdAt)
                    .put("mode", c.mode.name)
                    .put("algorithm", c.algorithm.name)
                    .put("quality", c.quality.name)
                    .put("upscale", c.upscale)
                    .put("sharpenStrength", c.sharpenStrength.toDouble())
                    .put("vignetteCorrection", c.vignetteCorrection)
                    .put("vignetteStrength", c.vignetteStrength.toDouble())
                    .put("lightPollutionReduction", c.lightPollutionReduction)
                    .put("lprStrength", c.lprStrength.toDouble())
                    .put("skyBrightness", c.skyBrightness.toDouble())
                    .put("saveTiff", c.saveTiff)
                    .put("kappa", c.kappa.toDouble())
                    .put("kappaPasses", c.kappaPasses)
                    .put("exposureNormalize", c.exposureNormalize)
                    .put("removeHotPixels", c.removeHotPixels)
                    .put("enhanceStarColor", c.enhanceStarColor)
                    .put("starColorStrength", c.starColorStrength.toDouble())
                    .put("freezeGround", c.freezeGround)
                    .put("horizonFraction", c.horizonFraction.toDouble())
                    .put("darkScene", c.darkScene)
                    .put("autoBrightness", c.autoBrightness)
                    .put("mergePixels", c.mergePixels)
                    .put("wbTemperatureK", c.wbTemperatureK)
                    .put("colorSpace", c.colorSpace.name)
            )
        }
        return arr.toString()
    }

    private fun jsonToCustomPresets(json: String?): List<CustomPreset> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                CustomPreset(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Preset"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    mode = enumByName(o.optString("mode"), StackMode.entries, StackMode.GENERAL),
                    algorithm = enumByName(o.optString("algorithm"), StackAlgorithm.entries, StackAlgorithm.REAL),
                    quality = enumByName(o.optString("quality"), StackQuality.entries, StackQuality.HD),
                    upscale = o.optBoolean("upscale", true),
                    sharpenStrength = o.optDouble("sharpenStrength", 0.6).toFloat(),
                    vignetteCorrection = o.optBoolean("vignetteCorrection", true),
                    vignetteStrength = o.optDouble("vignetteStrength", 0.35).toFloat(),
                    lightPollutionReduction = o.optBoolean("lightPollutionReduction", true),
                    lprStrength = o.optDouble("lprStrength", 0.6).toFloat(),
                    skyBrightness = o.optDouble("skyBrightness", 0.0).toFloat(),
                    saveTiff = o.optBoolean("saveTiff", true),
                    kappa = o.optDouble("kappa", 2.0).toFloat(),
                    kappaPasses = o.optInt("kappaPasses", 3),
                    exposureNormalize = o.optBoolean("exposureNormalize", true),
                    removeHotPixels = o.optBoolean("removeHotPixels", true),
                    enhanceStarColor = o.optBoolean("enhanceStarColor", false),
                    starColorStrength = o.optDouble("starColorStrength", 0.5).toFloat(),
                    freezeGround = o.optBoolean("freezeGround", false),
                    horizonFraction = o.optDouble("horizonFraction", 0.5).toFloat(),
                    darkScene = o.optBoolean("darkScene", false),
                    autoBrightness = o.optBoolean("autoBrightness", false),
                    mergePixels = o.optBoolean("mergePixels", false),
                    wbTemperatureK = o.optInt("wbTemperatureK", 6500),
                    colorSpace = enumByName(o.optString("colorSpace"), OutputColorSpace.entries, OutputColorSpace.SRGB),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun historyToJson(list: List<StackHistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("uri", h.uri)
                    .put("tiffUri", h.tiffUri ?: JSONObject.NULL)
                    .put("timestamp", h.timestamp)
                    .put("usedFrames", h.usedFrames)
                    .put("width", h.width)
                    .put("height", h.height)
                    .put("sizeBytes", h.sizeBytes)
                    .put("processingMillis", h.processingMillis)
                    .put("algorithm", h.algorithm)
                    .put("mode", h.mode)
            )
        }
        return arr.toString()
    }

    private fun jsonToHistory(json: String?): List<StackHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val uri = o.optString("uri")
                if (uri.isBlank()) return@mapNotNull null
                StackHistoryEntry(
                    uri = uri,
                    tiffUri = o.optString("tiffUri").takeIf { it.isNotBlank() && it != "null" },
                    timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                    usedFrames = o.optInt("usedFrames", 0),
                    width = o.optInt("width", 0),
                    height = o.optInt("height", 0),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    processingMillis = o.optLong("processingMillis", 0L),
                    algorithm = o.optString("algorithm", ""),
                    mode = o.optString("mode", ""),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(next: StackUiState) {
        val e = prefs().edit()
        e.putString(KEY_PRESET, next.preset.name)
        e.putString(KEY_CUSTOM_PRESETS, customPresetsToJson(next.customPresets))
        e.putString(KEY_HISTORY, historyToJson(next.history))
        e.putString(KEY_CUSTOM_PRESET_ID, next.customPresetId)
        if (next.preset == StackPreset.MANUAL) {
            // Setelan stacking manual disimpan hanya saat Preset Manual aktif
            e.putBoolean(KEY_DARK, next.darkScene)
            e.putString(KEY_MODE, next.mode.name)
            e.putString(KEY_ALGORITHM, next.algorithm.name)
            e.putString(KEY_QUALITY, next.quality.name)
            e.putBoolean(KEY_UPSCALE, next.upscale)
            e.putFloat(KEY_SHARPEN, next.sharpenStrength)
            e.putBoolean(KEY_VIGNETTE, next.vignetteCorrection)
            e.putFloat(KEY_VIGNETTE_STR, next.vignetteStrength)
            e.putBoolean(KEY_LPR, next.lightPollutionReduction)
            e.putFloat(KEY_LPR_STR, next.lprStrength)
            e.putFloat(KEY_SKY, next.skyBrightness)
            e.putBoolean(KEY_TIFF, next.saveTiff)
            e.putBoolean(KEY_AUTO_BRIGHT, next.autoBrightness)
            e.putBoolean(KEY_MERGE, next.mergePixels)
            e.putBoolean(KEY_HDR, next.hdr)
            e.putInt(KEY_WB_TEMP, next.wbTemperatureK)
            e.putString(KEY_COLOR_SPACE, next.colorSpace.name)
            e.putFloat(KEY_KAPPA, next.kappa)
            e.putInt(KEY_KAPPA_PASSES, next.kappaPasses)
            e.putBoolean(KEY_EXP_NORM, next.exposureNormalize)
            e.putBoolean(KEY_HOT, next.removeHotPixels)
            e.putBoolean(KEY_STAR_COLOR, next.enhanceStarColor)
            e.putFloat(KEY_STAR_COLOR_STR, next.starColorStrength)
            e.putBoolean(KEY_FREEZE, next.freezeGround)
            e.putFloat(KEY_HORIZON, next.horizonFraction)
            e.putBoolean(KEY_AUTO_SKY_MASK, next.autoSkyMask)
        }
        e.apply()
    }

    private fun restoreManual(state: StackUiState): StackUiState {
        val p = prefs()
        return state.copy(
            mode = enumValue(KEY_MODE, StackMode.entries, StackMode.GENERAL),
            algorithm = enumValue(KEY_ALGORITHM, StackAlgorithm.entries, StackAlgorithm.REAL),
            quality = enumValue(KEY_QUALITY, StackQuality.entries, StackQuality.HD),
            upscale = p.getBoolean(KEY_UPSCALE, true),
            sharpenStrength = p.getFloat(KEY_SHARPEN, 0.6f),
            vignetteCorrection = p.getBoolean(KEY_VIGNETTE, true),
            vignetteStrength = p.getFloat(KEY_VIGNETTE_STR, 0.35f),
            lightPollutionReduction = p.getBoolean(KEY_LPR, true),
            lprStrength = p.getFloat(KEY_LPR_STR, 0.6f),
            skyBrightness = p.getFloat(KEY_SKY, 0f),
            saveTiff = p.getBoolean(KEY_TIFF, true),
            autoBrightness = p.getBoolean(KEY_AUTO_BRIGHT, false),
            mergePixels = p.getBoolean(KEY_MERGE, false),
            hdr = p.getBoolean(KEY_HDR, false),
            wbTemperatureK = p.getInt(KEY_WB_TEMP, 6500),
            colorSpace = enumValue(KEY_COLOR_SPACE, OutputColorSpace.entries, OutputColorSpace.SRGB),
            kappa = p.getFloat(KEY_KAPPA, 2.0f),
            kappaPasses = p.getInt(KEY_KAPPA_PASSES, 3),
            exposureNormalize = p.getBoolean(KEY_EXP_NORM, true),
            removeHotPixels = p.getBoolean(KEY_HOT, true),
            enhanceStarColor = p.getBoolean(KEY_STAR_COLOR, false),
            starColorStrength = p.getFloat(KEY_STAR_COLOR_STR, 0.5f),
            freezeGround = p.getBoolean(KEY_FREEZE, false),
            horizonFraction = p.getFloat(KEY_HORIZON, 0.5f),
            autoSkyMask = p.getBoolean(KEY_AUTO_SKY_MASK, true),
            darkScene = p.getBoolean(KEY_DARK, false),
        )
    }

    private fun applyPreset(state: StackUiState, preset: StackPreset): StackUiState = when (preset) {
        StackPreset.GENERAL -> state.copy(
            mode = StackMode.GENERAL,
            algorithm = StackAlgorithm.REAL,
            quality = StackQuality.HD,
            upscale = false,
            sharpenStrength = 0f,
            vignetteCorrection = true,
            vignetteStrength = 0.4f,
            lightPollutionReduction = true,
            lprStrength = 0.5f,
            skyBrightness = 0f,
            kappa = 2.0f,
            kappaPasses = 3,
            exposureNormalize = true,
            removeHotPixels = true,
            enhanceStarColor = false,
            starColorStrength = 0.5f,
            freezeGround = false,
            horizonFraction = 0.5f,
            darkScene = false,
            autoBrightness = true,
            mergePixels = false,
            hdr = false,
            wbTemperatureK = 6500,
            colorSpace = OutputColorSpace.SRGB,
            autoSkyMask = false,
            saveTiff = true,
        )
        StackPreset.ASTRO -> state.copy(
            mode = StackMode.ASTRO,
            algorithm = StackAlgorithm.REAL,
            quality = StackQuality.HD,
            upscale = false,
            sharpenStrength = 0f,
            vignetteCorrection = true,
            vignetteStrength = 0.3f,
            lightPollutionReduction = true,
            lprStrength = 0.4f,
            skyBrightness = 0f,
            kappa = 2.0f,
            kappaPasses = 3,
            exposureNormalize = true,
            removeHotPixels = true,
            enhanceStarColor = false,
            starColorStrength = 0.5f,
            freezeGround = false,
            horizonFraction = 0.5f,
            darkScene = false,
            autoBrightness = true,
            mergePixels = false,
            hdr = false,
            wbTemperatureK = 6500,
            colorSpace = OutputColorSpace.SRGB,
            autoSkyMask = true,
            saveTiff = true,
        )
        StackPreset.DEEP_SKY -> state.copy(
            mode = StackMode.ASTRO,
            algorithm = StackAlgorithm.REAL,
            quality = StackQuality.HD,
            upscale = false,
            sharpenStrength = 0f,
            vignetteCorrection = true,
            vignetteStrength = 0.25f,
            lightPollutionReduction = true,
            lprStrength = 0.6f,
            skyBrightness = 0.15f,
            kappa = 2.5f,
            kappaPasses = 4,
            exposureNormalize = true,
            removeHotPixels = true,
            enhanceStarColor = false,
            starColorStrength = 0.4f,
            freezeGround = false,
            horizonFraction = 0.5f,
            darkScene = false,
            autoBrightness = true,
            mergePixels = false,
            hdr = false,
            wbTemperatureK = 6500,
            colorSpace = OutputColorSpace.SRGB,
            autoSkyMask = true,
            saveTiff = true,
        )
        StackPreset.SEQUATOR -> state.copy(
            mode = StackMode.ASTRO,
            algorithm = StackAlgorithm.LIGHTEN,
            quality = StackQuality.HD,
            upscale = false,
            sharpenStrength = 0f,
            vignetteCorrection = false,
            vignetteStrength = 0.15f,
            lightPollutionReduction = false,
            lprStrength = 0.3f,
            skyBrightness = 0f,
            kappa = 2.0f,
            kappaPasses = 3,
            exposureNormalize = false,
            removeHotPixels = false,
            enhanceStarColor = false,
            starColorStrength = 0.5f,
            freezeGround = false,
            horizonFraction = 0.5f,
            darkScene = false,
            autoBrightness = true,
            mergePixels = false,
            hdr = false,
            wbTemperatureK = 6500,
            colorSpace = OutputColorSpace.SRGB,
            autoSkyMask = true,
            saveTiff = true,
        )
        StackPreset.MANUAL -> state
        StackPreset.CUSTOM -> {
            val custom = state.customPresets.firstOrNull { it.id == state.customPresetId }
                ?: state.customPresets.firstOrNull()
            if (custom == null) {
                state.copy(
                    preset = StackPreset.GENERAL,
                    customPresetId = null,
                ).let { applyPreset(it, StackPreset.GENERAL) }
            } else {
                custom.applyTo(state.copy(preset = StackPreset.CUSTOM, customPresetId = custom.id))
            }
        }
    }

    private fun updateState(block: (StackUiState) -> StackUiState) {
        _ui.update { state ->
            val next = block(state)
            persist(next)
            next
        }
    }

    fun onImagesPicked(context: Context, uris: List<Uri>) {
        val supported = uris.filter { isSupportedMime(context, it) }
        if (supported.isEmpty()) {
            _ui.update {
                it.copy(error = "Format gambar tidak didukung. Gunakan JPG/PNG/HEIC/RAW.")
            }
            return
        }
        _ui.update { state ->
            val combined = (state.selectedUris + supported).take(ImageStacker.MAX_PICKER_IMAGES)
            val skipped = state.selectedUris.size + supported.size - combined.size
            state.copy(
                selectedUris = combined,
                resultUri = null,
                tiffUri = null,
                error = if (combined.size < 2) "Butuh minimal 2 foto yang didukung." else null,
                warning = if (skipped > 0) {
                    "Dilewati $skipped foto (maksimal ${ImageStacker.MAX_PICKER_IMAGES})."
                } else {
                    null
                },
            )
        }
    }

    fun onDarkFramesPicked(context: Context, uris: List<Uri>) {
        val supported = uris.filter { isSupportedMime(context, it) }
        _ui.update { state ->
            val combined = (state.darkFrameUris + supported).take(MAX_DARK_FRAMES)
            state.copy(
                darkFrameUris = combined,
                error = if (combined.size < 1) "Tidak ada foto gelap yang didukung." else null,
            )
        }
    }

    fun clearDarkFrames() = _ui.update { it.copy(darkFrameUris = emptyList()) }

    fun onFlatFramesPicked(context: Context, uris: List<Uri>) {
        val supported = uris.filter { isSupportedMime(context, it) }
        _ui.update { state ->
            val combined = (state.flatFrameUris + supported).take(MAX_FLAT_FRAMES)
            state.copy(
                flatFrameUris = combined,
                error = if (combined.size < 1) "Tidak ada foto flat yang didukung." else null,
            )
        }
    }

    fun clearFlatFrames() = _ui.update { it.copy(flatFrameUris = emptyList()) }

    fun removePhoto(index: Int) {
        _ui.update { state ->
            if (index !in state.selectedUris.indices) return@update state
            state.copy(
                selectedUris = state.selectedUris.filterIndexed { i, _ -> i != index },
                resultUri = null,
                tiffUri = null,
            )
        }
    }

    fun clearPhotos() {
        _ui.update {
            it.copy(
                selectedUris = emptyList(),
                darkFrameUris = emptyList(),
                flatFrameUris = emptyList(),
                resultUri = null,
                tiffUri = null,
                error = null,
                warning = null,
            )
        }
    }

    fun onModeChange(mode: StackMode) = updateState { it.copy(mode = mode) }

    fun onQualityChange(quality: StackQuality) = updateState {
        // Full/Asli sudah memakai hampir seluruh memori untuk pemrosesan;
        // upscale 2x otomatis dimatikan agar tidak OOM (hasil 2x tanpa detail nyata).
        if (quality == StackQuality.FULL) {
            it.copy(quality = quality, upscale = false)
        } else {
            it.copy(quality = quality)
        }
    }

    fun onAlgorithmChange(algorithm: StackAlgorithm) = updateState { it.copy(algorithm = algorithm) }

    fun onUpscaleChange(enabled: Boolean) = updateState { it.copy(upscale = enabled) }

    fun onSharpenStrengthChange(strength: Float) = updateState { it.copy(sharpenStrength = strength) }

    fun onVignetteCorrectionChange(enabled: Boolean) = updateState { it.copy(vignetteCorrection = enabled) }

    fun onVignetteStrengthChange(strength: Float) = updateState { it.copy(vignetteStrength = strength) }

    fun onSaveTiffChange(enabled: Boolean) = updateState { it.copy(saveTiff = enabled) }

    fun onHdrChange(enabled: Boolean) = updateState { it.copy(hdr = enabled) }

    fun onWbTemperatureChange(kelvin: Int) = updateState { it.copy(wbTemperatureK = kelvin) }

    fun onColorSpaceChange(colorSpace: OutputColorSpace) = updateState { it.copy(colorSpace = colorSpace) }

    fun onAutoBrightnessChange(enabled: Boolean) = updateState { it.copy(autoBrightness = enabled) }

    fun onMergePixelsChange(enabled: Boolean) = updateState { it.copy(mergePixels = enabled) }

    fun resetAlgorithmSettings() = updateState {
        it.copy(
            mode = StackMode.GENERAL,
            algorithm = StackAlgorithm.REAL,
            quality = StackQuality.HD,
            kappa = 2.0f,
            kappaPasses = 3,
            exposureNormalize = true,
            removeHotPixels = true,
        )
    }

    fun resetCorrectionSettings() = updateState {
        it.copy(
            darkScene = false,
            enhanceStarColor = false,
            starColorStrength = 0.5f,
            freezeGround = false,
            horizonFraction = 0.5f,
            autoSkyMask = true,
            wbTemperatureK = 6500,
            lightPollutionReduction = false,
            lprStrength = 0.4f,
            vignetteCorrection = false,
            vignetteStrength = 0.15f,
            skyBrightness = 0f,
        )
    }

    fun resetOutputSettings() = updateState {
        it.copy(
            saveTiff = true,
            upscale = false,
            sharpenStrength = 0f,
            autoBrightness = false,
            mergePixels = false,
            hdr = false,
            colorSpace = OutputColorSpace.SRGB,
        )
    }

    fun onLprChange(enabled: Boolean) = updateState { it.copy(lightPollutionReduction = enabled) }

    fun onLprStrengthChange(strength: Float) = updateState { it.copy(lprStrength = strength) }

    fun onSkyBrightnessChange(level: Float) = updateState { it.copy(skyBrightness = level) }


    fun openSettings() = _ui.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _ui.update { it.copy(settingsOpen = false) }

    fun onPresetChange(preset: StackPreset) {
        updateState { state ->
            when (preset) {
                StackPreset.MANUAL -> {
                    // Kembalikan setelan manual terakhir user tanpa memulai dari nol.
                    restoreManual(state).copy(preset = StackPreset.MANUAL)
                }
                StackPreset.CUSTOM -> {
                    // Terapkan preset kustom yang terakhir dipakai (atau yang pertama).
                    val custom = state.customPresets.firstOrNull { it.id == state.customPresetId }
                        ?: state.customPresets.firstOrNull()
                    if (custom == null) {
                        applyPreset(state, StackPreset.GENERAL).copy(preset = StackPreset.GENERAL)
                    } else {
                        custom.applyTo(state.copy(preset = StackPreset.CUSTOM, customPresetId = custom.id))
                    }
                }
                else -> applyPreset(state, preset).copy(preset = preset)
            }
        }
    }

    fun saveCurrentAsPreset(name: String) = updateState { state ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@updateState state
        val custom = CustomPreset(
            name = trimmed,
            mode = state.mode,
            algorithm = state.algorithm,
            quality = state.quality,
            upscale = state.upscale,
            sharpenStrength = state.sharpenStrength,
            vignetteCorrection = state.vignetteCorrection,
            vignetteStrength = state.vignetteStrength,
            lightPollutionReduction = state.lightPollutionReduction,
            lprStrength = state.lprStrength,
            skyBrightness = state.skyBrightness,
            saveTiff = state.saveTiff,
            kappa = state.kappa,
            kappaPasses = state.kappaPasses,
            exposureNormalize = state.exposureNormalize,
            removeHotPixels = state.removeHotPixels,
            enhanceStarColor = state.enhanceStarColor,
            starColorStrength = state.starColorStrength,
            freezeGround = state.freezeGround,
            horizonFraction = state.horizonFraction,
            darkScene = state.darkScene,
            autoBrightness = state.autoBrightness,
            mergePixels = state.mergePixels,
            wbTemperatureK = state.wbTemperatureK,
            colorSpace = state.colorSpace,
        )
        state.copy(customPresets = (state.customPresets + custom).take(MAX_CUSTOM_PRESETS))
    }

    fun deleteCustomPreset(id: String) = updateState { state ->
        val remaining = state.customPresets.filterNot { it.id == id }
        if (state.preset == StackPreset.CUSTOM && state.customPresetId == id) {
            // Preset aktif dihapus -> kembali ke preset Umum.
            val base = state.copy(customPresets = remaining, customPresetId = null, preset = StackPreset.GENERAL)
            applyPreset(base, StackPreset.GENERAL)
        } else {
            state.copy(
                customPresets = remaining,
                customPresetId = if (state.customPresetId == id) null else state.customPresetId,
            )
        }
    }

    fun applyCustomPreset(id: String) = updateState { state ->
        val custom = state.customPresets.firstOrNull { it.id == id } ?: return@updateState state
        custom.applyTo(state.copy(preset = StackPreset.CUSTOM, customPresetId = custom.id))
    }

    fun onKappaChange(kappa: Float) = updateState { it.copy(kappa = kappa) }

    fun onKappaPassesChange(passes: Int) = updateState { it.copy(kappaPasses = passes) }

    fun onExposureNormalizeChange(enabled: Boolean) = updateState { it.copy(exposureNormalize = enabled) }

    fun onRemoveHotPixelsChange(enabled: Boolean) = updateState { it.copy(removeHotPixels = enabled) }

    fun onEnhanceStarColorChange(enabled: Boolean) = updateState { it.copy(enhanceStarColor = enabled) }

    fun onStarColorStrengthChange(strength: Float) = updateState { it.copy(starColorStrength = strength) }

    fun onFreezeGroundChange(enabled: Boolean) = updateState { it.copy(freezeGround = enabled) }

    fun onHorizonFractionChange(fraction: Float) = updateState { it.copy(horizonFraction = fraction) }

    fun onAutoSkyMaskChange(enabled: Boolean) = updateState { it.copy(autoSkyMask = enabled) }

    fun onDarkSceneChange(enabled: Boolean) = updateState { it.copy(darkScene = enabled) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun clearHistory() = updateState { it.copy(history = emptyList()) }

    fun removeHistoryEntry(index: Int) = updateState { st ->
        st.copy(history = st.history.filterIndexed { i, _ -> i != index })
    }

    fun startStacking(context: Context) {
        val current = _ui.value
        if (current.selectedUris.size < 2 || current.isProcessing) return
        _ui.update {
            it.copy(
                isProcessing = true,
                progress = 0f,
                progressLabel = "Menyiapkan pekerjaan di latar belakang...",
                resultUri = null,
                tiffUri = null,
                error = null,
            )
        }
        val app = context.applicationContext
        WorkManager.getInstance(app).enqueueUniqueWork(
            StackWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            StackWorker.createRequest(current),
        )
        observeWork(app)
    }

    fun cancelStacking(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(StackWorker.UNIQUE_NAME)
        _ui.update { it.copy(isProcessing = false, progressLabel = "Dibatalkan.") }
    }

    private var workObserver: Job? = null

    private fun observeWork(context: Context) {
        workObserver?.cancel()
        workObserver = viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(StackWorker.UNIQUE_NAME)
                .collect { infos ->
                    val active = infos.lastOrNull {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    val terminal = infos.lastOrNull { it.state.isFinished }
                    val info = active ?: terminal ?: return@collect
                    applyWorkInfo(info)
                }
        }
    }

    private fun applyWorkInfo(info: WorkInfo) {
        val progressData = info.progress
        val fraction = progressData.getFloat(StackWorker.KEY_PROGRESS, 0f)
        val label = progressData.getString(StackWorker.KEY_PROGRESS_LABEL)
        when (info.state) {
            WorkInfo.State.ENQUEUED -> updateState {
                it.copy(isProcessing = true, progress = 0f, progressLabel = "Menunggu giliran...")
            }
            WorkInfo.State.RUNNING -> updateState {
                it.copy(
                    isProcessing = true,
                    progress = fraction,
                    progressLabel = label ?: "Memproses di latar belakang...",
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                val out = info.outputData
                val uri = out.getString(StackWorker.KEY_RESULT_URI)?.let { Uri.parse(it) }
                val tiff = out.getString(StackWorker.KEY_TIFF_URI)?.let { Uri.parse(it) }
                updateState { st ->
                    val uriStr = out.getString(StackWorker.KEY_RESULT_URI)
                    val withHistory = if (uriStr != null) {
                        val entry = StackHistoryEntry(
                            uri = uriStr,
                            tiffUri = out.getString(StackWorker.KEY_TIFF_URI),
                            timestamp = System.currentTimeMillis(),
                            usedFrames = out.getInt(StackWorker.KEY_USED_FRAMES, 0),
                            width = out.getInt(StackWorker.KEY_RESULT_WIDTH, 0),
                            height = out.getInt(StackWorker.KEY_RESULT_HEIGHT, 0),
                            sizeBytes = out.getLong(StackWorker.KEY_RESULT_SIZE, 0L),
                            processingMillis = out.getLong(StackWorker.KEY_PROCESSING_MILLIS, 0L),
                            algorithm = st.algorithm.label,
                            mode = st.mode.label,
                        )
                        st.copy(history = (listOf(entry) + st.history).take(MAX_HISTORY))
                    } else {
                        st
                    }
                    withHistory.copy(
                        isProcessing = false,
                        progress = 1f,
                        progressLabel = "Selesai!",
                        resultUri = uri,
                        tiffUri = tiff,
                        usedFrames = out.getInt(StackWorker.KEY_USED_FRAMES, 0),
                        resultWidth = out.getInt(StackWorker.KEY_RESULT_WIDTH, 0),
                        resultHeight = out.getInt(StackWorker.KEY_RESULT_HEIGHT, 0),
                        resultSizeBytes = out.getLong(StackWorker.KEY_RESULT_SIZE, 0L),
                        processingMillis = out.getLong(StackWorker.KEY_PROCESSING_MILLIS, 0L),
                        error = null,
                    )
                }
                workObserver?.cancel()
            }
            WorkInfo.State.FAILED -> {
                val err = info.outputData.getString(StackWorker.KEY_ERROR)
                    ?: "Terjadi kesalahan saat memproses."
                updateState { it.copy(isProcessing = false, progressLabel = "", error = err) }
                workObserver?.cancel()
            }
            WorkInfo.State.CANCELLED -> {
                updateState { it.copy(isProcessing = false, progressLabel = "Dibatalkan.") }
                workObserver?.cancel()
            }
            else -> Unit
        }
    }

    private fun isSupportedMime(context: Context, uri: Uri): Boolean {
        if (BitmapLoader.isRaw(uri)) return true
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: return false
        return mime in SUPPORTED_MIMES
    }

    companion object {
        private const val KEY_PRESET = "preset"
        private const val KEY_AUTO_MODE_LEGACY = "auto_mode"
        private const val MAX_DARK_FRAMES = 12
        private const val MAX_FLAT_FRAMES = 12
        private const val KEY_MODE = "mode"
        private const val KEY_ALGORITHM = "algorithm"
        private const val KEY_QUALITY = "quality"
        private const val KEY_UPSCALE = "upscale"
        private const val KEY_SHARPEN = "sharpen"
        private const val KEY_VIGNETTE = "vignette"
        private const val KEY_VIGNETTE_STR = "vignette_str"
        private const val KEY_LPR = "lpr"
        private const val KEY_LPR_STR = "lpr_str"
        private const val KEY_SKY = "sky"
        private const val KEY_TIFF = "tiff"
        private const val KEY_AUTO_BRIGHT = "auto_bright"
        private const val KEY_MERGE = "merge_pixels"
        private const val KEY_HDR = "hdr"
        private const val KEY_WB_TEMP = "wb_temp"
        private const val KEY_COLOR_SPACE = "color_space"
        private const val KEY_KAPPA = "kappa"
        private const val KEY_KAPPA_PASSES = "kappa_passes"
        private const val KEY_EXP_NORM = "exp_norm"
        private const val KEY_HOT = "hot_pixels"
        private const val KEY_STAR_COLOR = "star_color"
        private const val KEY_STAR_COLOR_STR = "star_color_str"
        private const val KEY_FREEZE = "freeze"
        private const val KEY_HORIZON = "horizon"
        private const val KEY_AUTO_SKY_MASK = "auto_sky_mask"
        private const val KEY_DARK = "dark_scene"
        private const val KEY_CUSTOM_PRESETS = "custom_presets"
        private const val KEY_CUSTOM_PRESET_ID = "custom_preset_id"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 50
        private const val MAX_CUSTOM_PRESETS = 20
        private val SUPPORTED_MIMES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
            "image/avif",
            "image/bmp",
            "image/x-adobe-dng",
        )
    }
}
