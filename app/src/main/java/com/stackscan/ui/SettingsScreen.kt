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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.stackscan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stackscan.R
import com.stackscan.processing.OutputColorSpace

private data class SettingInfo(val title: String, val description: String)

private val LocalShowInfo = staticCompositionLocalOf<(SettingInfo) -> Unit> { {} }

private enum class SettingsTab(val label: String) {
    ALGORITHM("Algoritma"),
    CORRECTION("Koreksi"),
    OUTPUT("Output"),
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    state: StackUiState,
    onPresetChange: (StackPreset) -> Unit,
    onApplyCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onModeChange: (StackMode) -> Unit,
    onAlgorithmChange: (StackAlgorithm) -> Unit,
    onQualityChange: (StackQuality) -> Unit,
    onUpscaleChange: (Boolean) -> Unit,
    onSharpenChange: (Float) -> Unit,
    onVignetteCorrectionChange: (Boolean) -> Unit,
    onVignetteStrengthChange: (Float) -> Unit,
    onLprChange: (Boolean) -> Unit,
    onLprStrengthChange: (Float) -> Unit,
    onSkyBrightnessChange: (Float) -> Unit,
    onSaveTiffChange: (Boolean) -> Unit,
    onAutoBrightnessChange: (Boolean) -> Unit,
    onMergePixelsChange: (Boolean) -> Unit,
    onHdrChange: (Boolean) -> Unit,
    onResetAlgorithm: () -> Unit,
    onResetCorrection: () -> Unit,
    onResetOutput: () -> Unit,
    onKappaChange: (Float) -> Unit,
    onKappaPassesChange: (Int) -> Unit,
    onExposureNormalizeChange: (Boolean) -> Unit,
    onRemoveHotPixelsChange: (Boolean) -> Unit,
    onEnhanceStarColorChange: (Boolean) -> Unit,
    onStarColorStrengthChange: (Float) -> Unit,
    onFreezeGroundChange: (Boolean) -> Unit,
    onHorizonFractionChange: (Float) -> Unit,
    onAutoSkyMaskChange: (Boolean) -> Unit,
    onDarkSceneChange: (Boolean) -> Unit,
    onWbTemperatureChange: (Int) -> Unit,
    onColorSpaceChange: (OutputColorSpace) -> Unit,
) {
    var infoDialog by remember { mutableStateOf<SettingInfo?>(null) }
    var tab by remember { mutableStateOf(SettingsTab.ALGORITHM) }
    val manual = state.preset == StackPreset.MANUAL || state.preset == StackPreset.CUSTOM

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Setelan", fontWeight = FontWeight.Bold)
                        Text(
                            "Preset, algoritma, koreksi & output",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalShowInfo provides { info -> infoDialog = info }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PresetSelectorCard(
                    preset = state.preset,
                    state = state,
                    onPresetChange = onPresetChange,
                    onApplyCustomPreset = onApplyCustomPreset,
                    onDeleteCustomPreset = onDeleteCustomPreset,
                    onSaveCustomPreset = onSaveCustomPreset,
                )

                if (manual) {
                    TabRow(
                        selectedTabIndex = tab.ordinal,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        SettingsTab.entries.forEach { item ->
                            Tab(
                                selected = tab == item,
                                onClick = { tab = item },
                                text = { Text(item.label) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            when (tab) {
                                SettingsTab.ALGORITHM -> onResetAlgorithm()
                                SettingsTab.CORRECTION -> onResetCorrection()
                                SettingsTab.OUTPUT -> onResetOutput()
                            }
                        }) {
                            Text("Reset setelan tab ini")
                        }
                    }
                    when (tab) {
                        SettingsTab.ALGORITHM -> AlgorithmTab(state, onModeChange, onAlgorithmChange, onQualityChange, onKappaChange, onKappaPassesChange, onExposureNormalizeChange, onRemoveHotPixelsChange)
                        SettingsTab.CORRECTION -> CorrectionTab(state, onDarkSceneChange, onEnhanceStarColorChange, onStarColorStrengthChange, onFreezeGroundChange, onHorizonFractionChange, onAutoSkyMaskChange, onLprChange, onLprStrengthChange, onVignetteCorrectionChange, onVignetteStrengthChange, onSkyBrightnessChange, onWbTemperatureChange)
                        SettingsTab.OUTPUT -> OutputTab(state, onSaveTiffChange, onAutoBrightnessChange, onMergePixelsChange, onHdrChange, onUpscaleChange, onSharpenChange, onColorSpaceChange)
                    }
                } else {
                    PresetSummaryCard(preset = state.preset, state = state)
                }
            }
        }
    }

    infoDialog?.let { info ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            confirmButton = {
                TextButton(onClick = { infoDialog = null }) { Text("Tutup") }
            },
            title = { Text(info.title) },
            text = { Text(info.description, style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

@Composable
private fun PresetSelectorCard(
    preset: StackPreset,
    state: StackUiState,
    onPresetChange: (StackPreset) -> Unit,
    onApplyCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onSaveCustomPreset: (String) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    SectionCard(
        title = "Mode Pengaturan",
        description = "Pilih skenario — preset menerapkan setelan terbaik otomatis. Pilih Manual untuk meracik sendiri, atau simpan racikan Anda sebagai preset kustom.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetOption(
                    preset = StackPreset.GENERAL,
                    selected = preset == StackPreset.GENERAL,
                    onSelect = { onPresetChange(StackPreset.GENERAL) },
                    modifier = Modifier.weight(1f),
                )
                PresetOption(
                    preset = StackPreset.ASTRO,
                    selected = preset == StackPreset.ASTRO,
                    onSelect = { onPresetChange(StackPreset.ASTRO) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetOption(
                    preset = StackPreset.SEQUATOR,
                    selected = preset == StackPreset.SEQUATOR,
                    onSelect = { onPresetChange(StackPreset.SEQUATOR) },
                    modifier = Modifier.weight(1f),
                )
                PresetOption(
                    preset = StackPreset.DEEP_SKY,
                    selected = preset == StackPreset.DEEP_SKY,
                    onSelect = { onPresetChange(StackPreset.DEEP_SKY) },
                    modifier = Modifier.weight(1f),
                )
            }
            PresetOption(
                preset = StackPreset.MANUAL,
                selected = preset == StackPreset.MANUAL,
                onSelect = { onPresetChange(StackPreset.MANUAL) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.customPresets.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Preset kustom Anda",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                state.customPresets.forEach { custom ->
                    CustomPresetRow(
                        custom = custom,
                        selected = preset == StackPreset.CUSTOM && state.customPresetId == custom.id,
                        onSelect = { onApplyCustomPreset(custom.id) },
                        onDelete = { onDeleteCustomPreset(custom.id) },
                    )
                }
            }

            TextButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text("Simpan setelan saat ini sebagai preset kustom")
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Simpan preset kustom") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Beri nama untuk preset dari setelan yang sedang aktif. Preset akan tersimpan di perangkat dan bisa dipakai kapan saja.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Nama preset") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        onSaveCustomPreset(presetName)
                        presetName = ""
                        showSaveDialog = false
                    },
                ) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun CustomPresetRow(
    custom: CustomPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(border, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    custom.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${custom.mode.label} · ${custom.algorithm.label} · ${custom.quality.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = "Terpilih",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = "Hapus preset",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PresetOption(
    preset: StackPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = modifier.clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(border, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = "Terpilih",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetSummaryCard(preset: StackPreset, state: StackUiState) {
    val customName = if (preset == StackPreset.CUSTOM) {
        state.customPresets.firstOrNull { it.id == state.customPresetId }?.name
    } else {
        null
    }
    SectionCard(
        title = if (customName != null) "Preset Kustom aktif: $customName" else "Preset ${preset.label} aktif",
        description = "Setelan ini diterapkan otomatis. Pilih Manual untuk mengubah semuanya.",
    ) {
        Text(
            buildString {
                append("• Mode: ${state.mode.label} · Algoritma: ${state.algorithm.label}\n")
                append("• Kualitas: ${state.quality.label} · Ketajaman ${(state.sharpenStrength * 100).toInt()}%\n")
                append("• LPR ${(state.lprStrength * 100).toInt()}% · Vignette ${(state.vignetteStrength * 100).toInt()}% · Kecerahan langit ${(state.skyBrightness * 100).toInt()}%\n")
                append("• Hot pixels: ${if (state.removeHotPixels) "Aktif" else "Mati"} · Warna bintang: ${if (state.enhanceStarColor) "Aktif" else "Mati"}\n")
                if (state.darkScene) append("• Mode Gelap: Aktif\n")
                append("• Simpan TIFF 16-bit: ${if (state.saveTiff) "Aktif" else "Mati"}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlgorithmTab(
    state: StackUiState,
    onModeChange: (StackMode) -> Unit,
    onAlgorithmChange: (StackAlgorithm) -> Unit,
    onQualityChange: (StackQuality) -> Unit,
    onKappaChange: (Float) -> Unit,
    onKappaPassesChange: (Int) -> Unit,
    onExposureNormalizeChange: (Boolean) -> Unit,
    onRemoveHotPixelsChange: (Boolean) -> Unit,
) {
    SectionCard(
        title = "Algoritma & Mode",
        description = "Cara foto disejajarkan dan digabungkan menjadi satu hasil.",
    ) {
        SettingChips(
            title = "Mode",
            note = "Umum untuk objek statis; Bintang untuk langit malam.",
            options = StackMode.entries,
            selected = state.mode,
            label = { it.label },
            onSelect = onModeChange,
            info = SettingInfo(
                "Mode",
                "Umum/Makro memakai alignment sederhana — cocok untuk objek statis seperti serangga, teks, atau pemandangan. " +
                    "Bintang (Astro) mendeteksi bintang untuk menyelaraskan frame sehingga tahan terhadap rotasi langit antar frame, " +
                    "misalnya saat memotret tanpa tracking.",
            ),
        )
        SectionDivider()
        SettingChips(
            title = "Algoritma Stack",
            note = "Real = paling bersih; Lighten = bintang tegas; Trails = jejak bintang; Median = tahan outlier.",
            options = StackAlgorithm.entries,
            selected = state.algorithm,
            label = { it.label },
            onSelect = onAlgorithmChange,
            info = SettingInfo(
                "Algoritma Stack",
                "Real (kappa-sigma) menghitung rata-rata piksel yang konsisten dan membuang nilai yang menyimpang jauh — hasil paling bersih untuk hampir semua scene. " +
                    "Lighten mengambil piksel paling terang di tiap posisi — cocok untuk bintang redup, tapi latar lebih rentan noise. " +
                    "Trails (star trails) menumpuk piksel paling terang tanpa alignment — bintang membentuk jejak melengkung, dan tanah otomatis tetap diam selama kamera tidak bergeser. " +
                    "Median memakai nilai tengah — sangat tahan outlier ekstrem; berlaku untuk maksimal 16 frame, di atas itu otomatis memakai kappa-sigma.",
            ),
        )
        SectionDivider()
        SettingChips(
            title = "Kualitas Kerja",
            note = "HD seimbang; Full/Asli paling tajam tapi butuh RAM besar.",
            options = StackQuality.entries,
            selected = state.quality,
            label = { it.label },
            onSelect = onQualityChange,
            info = SettingInfo(
                "Kualitas Kerja",
                "Cepat (±1 MP) menghemat RAM dan waktu — cocok untuk preview atau perangkat lama. " +
                    "HD (±2 MP) seimbang untuk sebagian besar kasus. " +
                    "Full/Asli memakai resolusi asli sensor (hingga 4096 px) — paling tajam saat di-zoom, tapi lebih lambat dan butuh RAM besar (disarankan 8 GB).",
            ),
        )
        SectionDivider()
        SettingSlider(
            title = "Ketegasan kappa (κ)",
            note = "Batas pembuangan outlier; 2.0–3.0 umumnya ideal.",
            value = state.kappa,
            valueRange = 1.5f..4.0f,
            valueText = String.format(java.util.Locale.US, "κ %.1f", state.kappa),
            onValueChange = onKappaChange,
            info = SettingInfo(
                "Ketegasan kappa (κ)",
                "Kappa adalah ambang sigma-clip: piksel yang menyimpang lebih dari κ × standar deviasi dianggap outlier dan dibuang. " +
                    "Nilai kecil (2.0) lebih agresif membuang noise/ghost; nilai besar (3.0–4.0) lebih aman untuk bintang redup. " +
                    "Mulai dari 2.0, lalu naikkan bila ada bintang yang hilang.",
            ),
        )
        SectionDivider()
        SettingSlider(
            title = "Iterasi (passes)",
            note = "Jumlah ulang perhitungan sigma untuk hasil lebih stabil.",
            value = state.kappaPasses.toFloat(),
            valueRange = 1f..5f,
            valueText = "${state.kappaPasses} ×",
            onValueChange = { onKappaPassesChange(it.toInt()) },
            info = SettingInfo(
                "Iterasi (passes)",
                "Iterasi menentukan berapa kali perhitungan sigma-clip diulang untuk mengasah nilai rata-rata. " +
                    "1–2 iterasi cukup untuk sebagian besar kasus; lebih banyak memperlambat proses dengan peningkatan kualitas yang kecil.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Normalisasi eksposur",
            note = "Setarakan kecerahan tiap frame sebelum digabung.",
            checked = state.exposureNormalize,
            onCheckedChange = onExposureNormalizeChange,
            info = SettingInfo(
                "Normalisasi eksposur",
                "Menyamakan tingkat kecerahan tiap frame ke level referensi sebelum digabung — mencegah flicker atau gradasi terang-gelap " +
                    "bila eksposur antar frame sedikit berbeda (misal langit berubah). Disarankan selalu aktif.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Hapus hot pixels",
            note = "Buang bintik terang statis dari sensor.",
            checked = state.removeHotPixels,
            onCheckedChange = onRemoveHotPixelsChange,
            info = SettingInfo(
                "Hapus hot pixels",
                "Sensor kamera bisa memiliki piksel panas yang muncul sebagai bintik terang statis, terutama di ISO tinggi dan eksposur panjang. " +
                    "Opsi ini mendeteksi dan menghapusnya setelah stacking. Biarkan aktif untuk langit malam.",
            ),
        )
    }
}

@Composable
private fun CorrectionTab(
    state: StackUiState,
    onDarkSceneChange: (Boolean) -> Unit,
    onEnhanceStarColorChange: (Boolean) -> Unit,
    onStarColorStrengthChange: (Float) -> Unit,
    onFreezeGroundChange: (Boolean) -> Unit,
    onHorizonFractionChange: (Float) -> Unit,
    onAutoSkyMaskChange: (Boolean) -> Unit,
    onLprChange: (Boolean) -> Unit,
    onLprStrengthChange: (Float) -> Unit,
    onVignetteCorrectionChange: (Boolean) -> Unit,
    onVignetteStrengthChange: (Float) -> Unit,
    onSkyBrightnessChange: (Float) -> Unit,
    onWbTemperatureChange: (Int) -> Unit,
) {
    SectionCard(
        title = "Mode Gelap (Deep-sky)",
        description = "Satu tombol untuk scene langit gelap gulita penuh noise.",
    ) {
        SettingToggle(
            title = "Aktifkan Mode Gelap",
            note = "Angkat objek samar dari langit gelap penuh noise.",
            checked = state.darkScene,
            onCheckedChange = onDarkSceneChange,
            info = SettingInfo(
                "Mode Gelap (Deep-sky)",
                "Saat aktif, langit otomatis dicerahkan (gamma), reduksi polusi cahaya diperkuat, dan normalisasi eksposur dipaksa menyala " +
                    "agar objek samar di tengah noise lebih terlihat. Tetap butuh minimal 2–4 frame yang diambil berturut-turut (tripod) — " +
                    "stacking tidak bisa memunculkan objek yang sama sekali tidak terekam.",
            ),
        )
    }

    SectionCard(
        title = "White Balance",
        description = "Keseimbangan warna hasil akhir \u2014 default 6500 K (netral).",
    ) {
        SettingSlider(
            title = "Keseimbangan warna",
            note = "6500 K = netral; geser kanan = lebih hangat (kuning), kiri = lebih dingin (biru).",
            value = state.wbTemperatureK.toFloat(),
            valueRange = 2700f..10000f,
            valueText = "${state.wbTemperatureK} K",
            onValueChange = { onWbTemperatureChange(it.toInt()) },
            info = SettingInfo(
                "Keseimbangan warna (tint)",
                "Menggeser keseimbangan warna hasil akhir. Nilai kelvin di sini adalah skala kontrol " +
                    "(seperti slider Temp di editor foto), bukan suhu fisik cahaya sumber \u2014 jadi bukan " +
                    "konvensi mired fotografi. 6500 K (default) tidak mengubah warna. " +
                    "Geser kanan (mis. 7500\u20139000 K) untuk hasil lebih hangat/kuning \u2014 sering dipakai foto astro agar nebula tampak lebih hangat. " +
                    "Geser kiri (mis. 4000\u20135000 K) untuk hasil lebih dingin/biru.",
            ),
        )
    }

    SectionCard(
        title = "Koreksi Gambar",
        description = "Perbaikan tampilan setelah penggabungan selesai.",
    ) {
        SettingToggle(
            title = "Perkuat warna bintang",
            note = "Tajamkan warna bintang setelah stacking.",
            checked = state.enhanceStarColor,
            onCheckedChange = onEnhanceStarColorChange,
            info = SettingInfo(
                "Perkuat warna bintang",
                "Menguatkan saturasi dan kontras warna bintang yang biasanya pudar setelah rata-rata banyak frame. " +
                    "Tanpa ini, bintang bisa terlihat putih seragam.",
            ),
        )
        if (state.enhanceStarColor) {
            SettingSlider(
                title = "Kekuatan warna",
                note = "50% biasanya cukup; terlalu tinggi bisa terlihat tidak natural.",
                value = state.starColorStrength,
                valueRange = 0.05f..1f,
                valueText = "${(state.starColorStrength * 100).toInt()}%",
                onValueChange = onStarColorStrengthChange,
                info = SettingInfo(
                    "Kekuatan warna bintang",
                    "Semakin tinggi, warna bintang makin mencolok. Terlalu tinggi bisa membuat bintang terlihat meledak (bloom) yang tidak natural.",
                ),
            )
        }
        SectionDivider()
        SettingToggle(
            title = "Kunci tanah (freeze ground)",
            note = "Tanah tetap diam, langit mengikuti rotasi bintang.",
            checked = state.freezeGround,
            onCheckedChange = onFreezeGroundChange,
            info = SettingInfo(
                "Kunci tanah (freeze ground)",
                "Pada foto astro, langit bergeser antar frame karena rotasi bumi. Freeze ground menggabungkan area di bawah horizon secara terpisah " +
                    "sehingga tanah tidak bergeser atau menjadi ghost saat langit di-align. Aktifkan bila ada objek tanah/foreground di foto.",
            ),
        )
        if (state.freezeGround) {
            SettingSlider(
                title = "Posisi horizon",
                note = "Batas langit↔tanah dalam frame; 50% = tengah.",
                value = state.horizonFraction,
                valueRange = 0.05f..0.95f,
                valueText = "${(state.horizonFraction * 100).toInt()}%",
                onValueChange = onHorizonFractionChange,
                info = SettingInfo(
                    "Posisi horizon",
                    "Titik batas antara langit dan tanah, dalam persen tinggi frame. 50% = tengah. " +
                        "Sesuaikan bila horizon di foto berada di atas atau di bawah tengah.",
                ),
            )
        }
        SectionDivider()
        SettingToggle(
            title = "Deteksi langit otomatis",
            note = "Pisahkan langit & tanah otomatis (masker per-piksel).",
            checked = state.autoSkyMask,
            onCheckedChange = onAutoSkyMaskChange,
            info = SettingInfo(
                "Deteksi langit otomatis",
                "Menganalisis tekstur frame acuan: langit halus, tanah/pohon kasar — lalu membuat masker per-piksel. " +
                    "Saat aktif, langit di-align dengan rotasi bintang dan tanah dengan alignment terpisah (tidak hanya garis horizon lurus). " +
                    "Matikan untuk memakai slider Posisi horizon manual.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Kurangi polusi cahaya langit",
            note = "Hilangkan glow langit dari polusi kota.",
            checked = state.lightPollutionReduction,
            onCheckedChange = onLprChange,
            info = SettingInfo(
                "Kurangi polusi cahaya langit",
                "Menghilangkan gradien/glow latar akibat polusi cahaya — langit lebih gelap dan bintang lebih jelas. " +
                    "Di scene biasa tanpa polusi bisa dimatikan agar langit tetap natural.",
            ),
        )
        if (state.lightPollutionReduction) {
            SettingSlider(
                title = "Kekuatan reduksi",
                note = "0 = mati, 100 = langit hitam total.",
                value = state.lprStrength,
                valueRange = 0.05f..1f,
                valueText = "${(state.lprStrength * 100).toInt()}%",
                onValueChange = onLprStrengthChange,
                info = SettingInfo(
                    "Kekuatan reduksi polusi",
                    "0 = mati, 100 = langit menjadi hitam total. Mulai dari 60% untuk langit yang natural.",
                ),
            )
        }
        SectionDivider()
        SettingToggle(
            title = "Koreksi vignette/gradien",
            note = "Ratakan pinggiran gelap lensa atau gradasi latar.",
            checked = state.vignetteCorrection,
            onCheckedChange = onVignetteCorrectionChange,
            info = SettingInfo(
                "Koreksi vignette/gradien",
                "Vignette adalah pinggiran foto yang lebih gelap akibat sifat lensa. Opsi ini menyetarakan kecerahan pinggiran dengan tengah, " +
                    "dan juga meratakan gradasi latar yang tidak rata.",
            ),
        )
        if (state.vignetteCorrection) {
            SettingSlider(
                title = "Kekuatan vignette",
                note = "35% biasanya cukup.",
                value = state.vignetteStrength,
                valueRange = 0.05f..1f,
                valueText = "${(state.vignetteStrength * 100).toInt()}%",
                onValueChange = onVignetteStrengthChange,
                info = SettingInfo(
                    "Kekuatan vignette",
                    "35% biasanya cukup. Naikkan bila vignette masih terlihat, turunkan bila hasil terlihat aneh di sudut.",
                ),
            )
        }
        SectionDivider()
        SettingSlider(
            title = "Kecerahan langit (gamma)",
            note = "Mencerahkan langit/nebula redup setelah stack.",
            value = state.skyBrightness,
            valueRange = 0f..1f,
            valueText = "${(state.skyBrightness * 100).toInt()}%",
            onValueChange = onSkyBrightnessChange,
            info = SettingInfo(
                "Kecerahan langit (gamma)",
                "Menaikkan kecerahan area langit secara aman (highlight tetap terlindungi). 0 = tidak diubah; 100 = langit sangat terang. " +
                    "Berguna untuk mengangkat nebula atau deep-sky yang redup.",
            ),
        )
    }
}

@Composable
private fun OutputTab(
    state: StackUiState,
    onSaveTiffChange: (Boolean) -> Unit,
    onAutoBrightnessChange: (Boolean) -> Unit,
    onMergePixelsChange: (Boolean) -> Unit,
    onHdrChange: (Boolean) -> Unit,
    onUpscaleChange: (Boolean) -> Unit,
    onSharpenChange: (Float) -> Unit,
    onColorSpaceChange: (OutputColorSpace) -> Unit,
) {
    SectionCard(
        title = "Output & Simpan",
        description = "Format hasil akhir yang disimpan ke galeri.",
    ) {
        SettingToggle(
            title = "Simpan master TIFF 16-bit",
            note = "Selain JPG, simpan .tif 16-bit tanpa kompresi.",
            checked = state.saveTiff,
            onCheckedChange = onSaveTiffChange,
            info = SettingInfo(
                "Simpan master TIFF 16-bit",
                "File TIFF 16-bit menyimpan data sensor penuh tanpa kompresi — ideal untuk edit lanjutan di PC (Photoshop, GIMP, dll). " +
                    "Ukurannya jauh lebih besar daripada JPG.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Detail boost (2x upscale)",
            note = if (state.quality == StackQuality.FULL) {
                "Otomatis mati di kualitas Full/Asli untuk menghemat memori."
            } else {
                "Perbesar ukuran hasil 2× dengan interpolasi."
            },
            checked = state.upscale,
            onCheckedChange = onUpscaleChange,
            enabled = state.quality != StackQuality.FULL,
            info = SettingInfo(
                "Detail boost (2x upscale)",
                "Memperbesar hasil stacking 2× (interpolasi) agar tampak lebih besar dan nyaman dilihat. " +
                    "Di kualitas Full/Asli otomatis dimatikan karena hasilnya sudah sangat besar dan memori perangkat terbatas.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Kecerahan otomatis",
            note = "Terangkan otomatis bila hasil terlalu gelap.",
            checked = state.autoBrightness,
            onCheckedChange = onAutoBrightnessChange,
            info = SettingInfo(
                "Kecerahan otomatis",
                "Mengukur kecerahan rata-rata hasil akhir; bila terlalu gelap (misal deep-sky), langit otomatis dicerahkan " +
                    "ke tingkat yang nyaman tanpa menyentuh slider manual. Hanya mencerahkan, tidak pernah menggelapkan.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Merge piksel 2×2",
            note = "Kurangi resolusi setengah — noise berkurang drastis, file lebih kecil.",
            checked = state.mergePixels,
            onCheckedChange = onMergePixelsChange,
            info = SettingInfo(
                "Merge piksel 2×2",
                "Merata-ratakan tiap blok 2×2 piksel menjadi satu — resolusi hasil jadi setengahnya, tapi noise berkurang drastis " +
                    "dan file jauh lebih kecil (teknik umum di fotografi astro). Master TIFF 16-bit tetap disimpan dalam resolusi penuh.",
            ),
        )
        SectionDivider()
        SettingToggle(
            title = "Komposisi HDR",
            note = "Kompres rentang dinamis — langit cerah & objek redup sama-sama terlihat.",
            checked = state.hdr,
            onCheckedChange = onHdrChange,
            info = SettingInfo(
                "Komposisi HDR",
                "Menerapkan tone mapping (Reinhard) pada hasil akhir: rentang dinamis dikompresi sehingga langit yang terlalu terang " +
                    "dan objek yang sangat redup bisa tampil bersama — ciri khas mode HDR ala Sequator. Hasil cenderung lebih gelap " +
                    "dan kontras lebih rendah, cocok untuk diedit lanjut.",
            ),
        )
        SectionDivider()
        SettingSlider(
            title = "Ketajaman",
            note = "Kuatkan tepi setelah stacking; 0 = natural.",
            value = state.sharpenStrength,
            valueRange = 0f..1f,
            valueText = "${(state.sharpenStrength * 100).toInt()}%",
            onValueChange = onSharpenChange,
            info = SettingInfo(
                "Ketajaman",
                "Menguatkan kontras tepi sehingga foto tampak lebih tajam. 0 = natural; 100 = sangat tajam, bisa menimbulkan artefak (halo/ringing) " +
                    "bila berlebihan. Mulai dari 60%.",
            ),
        )
    }

    SectionCard(
        title = "Ruang Warna",
        description = "Gamut warna hasil akhir (JPEG & TIFF).",
    ) {
        SettingChips(
            title = "Ruang warna",
            note = "sRGB = standar universal; Adobe RGB = gamut lebih luas untuk cetak; Display P3 = gamut luas modern.",
            options = OutputColorSpace.entries.toList(),
            selected = state.colorSpace,
            label = { it.label },
            onSelect = onColorSpaceChange,
            info = SettingInfo(
                "Ruang warna",
                "Mengonversi warna hasil ke gamut tujuan. sRGB konsisten di semua layar; Adobe RGB memberi merah\u2013hijau lebih kaya untuk cetak; " +
                    "Display P3 akurat di layar flagship modern. Perangkat tanpa profil warna akan menampilkan sRGB secara virtual.",
            ),
        )
    }
}

@Composable
private fun <T> SettingChips(
    title: String,
    note: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    info: SettingInfo? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            InfoButton(info)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { item ->
                FilterChip(
                    selected = selected == item,
                    onClick = { onSelect(item) },
                    label = { Text(label(item)) },
                )
            }
        }
        SettingNote(note)
    }
}

@Composable
private fun SettingToggle(
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    info: SettingInfo? = null,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
            InfoButton(info)
            Spacer(Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        SettingNote(note, dimmed = !enabled)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    note: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    info: SettingInfo? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            InfoButton(info)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
        SettingNote(note)
    }
}

@Composable
private fun InfoButton(info: SettingInfo?) {
    if (info == null) return
    val onInfo = LocalShowInfo.current
    IconButton(
        onClick = { onInfo(info) },
        modifier = Modifier
            .width(28.dp)
            .height(28.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = "Info ${info.title}",
            modifier = Modifier.width(16.dp).height(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingNote(note: String, dimmed: Boolean = false) {
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = if (dimmed) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionCard(
    title: String,
    description: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
