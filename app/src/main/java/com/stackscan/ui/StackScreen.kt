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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stackscan.processing.BitmapLoader
import com.stackscan.processing.ImageStacker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StackScreen(
    viewModel: StackViewModel,
    onPickImages: () -> Unit,
    onPickDarkFrames: () -> Unit,
    onPickFlatFrames: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (state.settingsOpen) {
        SettingsScreen(
            onBack = viewModel::closeSettings,
            state = state,
            onModeChange = viewModel::onModeChange,
            onAlgorithmChange = viewModel::onAlgorithmChange,
            onQualityChange = viewModel::onQualityChange,
            onUpscaleChange = viewModel::onUpscaleChange,
            onSharpenChange = viewModel::onSharpenStrengthChange,
            onVignetteCorrectionChange = viewModel::onVignetteCorrectionChange,
            onVignetteStrengthChange = viewModel::onVignetteStrengthChange,
            onLprChange = viewModel::onLprChange,
            onLprStrengthChange = viewModel::onLprStrengthChange,
            onSkyBrightnessChange = viewModel::onSkyBrightnessChange,
            onSaveTiffChange = viewModel::onSaveTiffChange,
            onAutoBrightnessChange = viewModel::onAutoBrightnessChange,
            onMergePixelsChange = viewModel::onMergePixelsChange,
            onHdrChange = viewModel::onHdrChange,
            onResetAlgorithm = viewModel::resetAlgorithmSettings,
            onResetCorrection = viewModel::resetCorrectionSettings,
            onResetOutput = viewModel::resetOutputSettings,
            onPresetChange = viewModel::onPresetChange,
            onApplyCustomPreset = viewModel::applyCustomPreset,
            onDeleteCustomPreset = viewModel::deleteCustomPreset,
            onSaveCustomPreset = viewModel::saveCurrentAsPreset,
            onKappaChange = viewModel::onKappaChange,
            onKappaPassesChange = viewModel::onKappaPassesChange,
            onExposureNormalizeChange = viewModel::onExposureNormalizeChange,
            onRemoveHotPixelsChange = viewModel::onRemoveHotPixelsChange,
            onEnhanceStarColorChange = viewModel::onEnhanceStarColorChange,
            onStarColorStrengthChange = viewModel::onStarColorStrengthChange,
            onFreezeGroundChange = viewModel::onFreezeGroundChange,
            onHorizonFractionChange = viewModel::onHorizonFractionChange,
            onAutoSkyMaskChange = viewModel::onAutoSkyMaskChange,
            onDarkSceneChange = viewModel::onDarkSceneChange,
            onWbTemperatureChange = viewModel::onWbTemperatureChange,
            onColorSpaceChange = viewModel::onColorSpaceChange,
        )
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    if (showGuide) {
        GuideDialog(onDismiss = { showGuide = false })
    }
    if (showLicense) {
        AboutDialog(onDismiss = { showLicense = false })
    }
    if (showHistory) {
        HistoryDialog(
            entries = state.history,
            onOpen = { entry ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(entry.uri), "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    // Viewer eksternal tidak tersedia — biarkan dialog tetap terbuka.
                }
            },
            onRemove = viewModel::removeHistoryEntry,
            onClear = viewModel::clearHistory,
            onDismiss = { showHistory = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("StackScan", fontWeight = FontWeight.Bold)
                        Text(
                            "Stacking foto: serangga, makro, astro",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::openSettings) {
                        Text("Setelan")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu lainnya")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Riwayat hasil") },
                                onClick = {
                                    menuOpen = false
                                    showHistory = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Panduan singkat") },
                                onClick = {
                                    menuOpen = false
                                    showGuide = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Tentang & lisensi") },
                                onClick = {
                                    menuOpen = false
                                    showLicense = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PickSection(
                hasPhotos = state.selectedUris.isNotEmpty(),
                onPickImages = onPickImages,
            )

            if (state.selectedUris.isNotEmpty()) {
                PhotoSection(
                    uris = state.selectedUris,
                    onRemove = viewModel::removePhoto,
                    onClear = viewModel::clearPhotos,
                )
                DarkFramesCard(
                    count = state.darkFrameUris.size,
                    onPick = onPickDarkFrames,
                    onClear = viewModel::clearDarkFrames,
                )
                FlatFramesCard(
                    count = state.flatFrameUris.size,
                    onPick = onPickFlatFrames,
                    onClear = viewModel::clearFlatFrames,
                )
            } else {
                EmptyStateCard()
            }

            state.warning?.let { warning -> WarningCard(warning) }

            SettingsSummaryCard(
                state = state,
                onOpenSettings = viewModel::openSettings,
            )

            Button(
                onClick = { viewModel.startStacking(context) },
                enabled = state.selectedUris.size >= 2 && !state.isProcessing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    if (state.isProcessing) {
                        "Memproses..."
                    } else {
                        "Stack & Simpan"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.isProcessing) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.progressLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.cancelStacking(context) }) {
                        Text("Batalkan")
                    }
                }
                Text(
                    "Proses berjalan di latar belakang — boleh keluar dari aplikasi; " +
                        "notifikasi memberi tahu saat selesai.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { error -> ErrorCard(error, viewModel::dismissError) }

            state.resultUri?.let { uri ->
                ResultCard(
                    uri = uri,
                    usedFrames = state.usedFrames,
                    isAlignOnly = state.algorithm == StackAlgorithm.ALIGN,
                    width = state.resultWidth,
                    height = state.resultHeight,
                    sizeBytes = state.resultSizeBytes,
                    processingMillis = state.processingMillis,
                    tiffUri = state.tiffUri,
                    onRestart = viewModel::clearPhotos,
                )
            }

        }
    }
}

@Composable
private fun PickSection(hasPhotos: Boolean, onPickImages: () -> Unit) {
    OutlinedButton(onClick = onPickImages, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(if (hasPhotos) "Tambah Foto" else "Pilih Foto")
    }
}

@Composable
private fun PhotoSection(uris: List<Uri>, onRemove: (Int) -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        "${uris.size} foto dipilih",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (uris.size > ImageStacker.MAX_FRAMES) {
                        Text(
                            "Mode Pro: ${uris.size} frame diproses streaming (lebih lama, lebih bersih)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Bersihkan") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(uris) { index, uri ->
                    ThumbnailItem(index = index, uri = uri, onRemove = { onRemove(index) })
                }
            }
        }
    }
}

@Composable
private fun DarkFramesCard(count: Int, onPick: () -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Dark frames (opsional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (count > 0) {
                        "$count foto gelap dipilih — noise sensor dikurangi sebelum stacking."
                    } else {
                        "Foto gelap (lensa tertutup) untuk mengurangi noise sensor — disarankan untuk astro."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (count > 0) {
                TextButton(onClick = onClear) { Text("Hapus") }
            }
            TextButton(onClick = onPick) { Text(if (count > 0) "Tambah" else "Pilih") }
        }
    }
}

@Composable
private fun FlatFramesCard(count: Int, onPick: () -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Flat frames (opsional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (count > 0) {
                        "$count foto flat dipilih — vignette lensa dikoreksi sebelum stacking."
                    } else {
                        "Foto flat (bidik permukaan terang merata) untuk koreksi vignette lensa — disarankan untuk astro."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (count > 0) {
                TextButton(onClick = onClear) { Text("Hapus") }
            }
            TextButton(onClick = onPick) { Text(if (count > 0) "Tambah" else "Pilih") }
        }
    }
}

@Composable
private fun ThumbnailItem(index: Int, uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.Default) {
            try {
                BitmapLoader.loadBitmap(context, uri, 192).asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Foto ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Hapus foto ${index + 1}",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Text(
                "Belum ada foto. Pilih minimal 2 foto dari scene yang sama — 17+ foto otomatis Mode Pro streaming (tanpa batas).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun WarningCard(warning: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(warning, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    }
}

@Composable
private fun ResultCard(
    uri: Uri,
    usedFrames: Int,
    isAlignOnly: Boolean,
    width: Int,
    height: Int,
    sizeBytes: Long,
    processingMillis: Long,
    tiffUri: Uri?,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.Default) {
            try {
                BitmapLoader.loadBitmap(context, uri, 1600).asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Hasil Stack",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (isAlignOnly) {
                            "Selesai — $usedFrames frame terselaraskan & disimpan"
                        } else {
                            "Selesai — $usedFrames foto digunakan"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Hasil stacking",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Resolusi ${width}x${height} · ${formatSize(sizeBytes)} · ${formatTime(processingMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (tiffUri != null) {
                        "Tersimpan di Pictures/StackScan (JPG + TIFF 16-bit)"
                    } else {
                        "Tersimpan di Pictures/StackScan"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Bagikan hasil"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Bagikan")
                }
                if (tiffUri != null && tiffUri.scheme == "content") {
                    Button(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/tiff"
                            putExtra(Intent.EXTRA_STREAM, tiffUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Bagikan TIFF 16-bit"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Bagikan TIFF")
                    }
                }
                OutlinedButton(onClick = onRestart) {
                    Text("Mulai Lagi")
                }
            }
        }
    }
}

@Composable
private fun SettingsSummaryCard(state: StackUiState, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.preset) {
                            StackPreset.MANUAL -> "Setelan Manual"
                            StackPreset.CUSTOM -> "Setelan Kustom"
                            else -> "Setelan: Preset"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.preset == StackPreset.MANUAL) {
                            "${state.mode.label} · ${state.algorithm.label} · ${state.quality.label}" +
                                " · Ketajaman ${(state.sharpenStrength * 100).toInt()}%" +
                                " · LPR ${if (state.lightPollutionReduction) "Aktif" else "Mati"}" +
                                " · TIFF ${if (state.saveTiff) "Aktif" else "Mati"}"
                        } else {
                            val customSuffix = if (state.preset == StackPreset.CUSTOM) {
                                state.customPresets.firstOrNull { it.id == state.customPresetId }?.name
                                    ?.let { " · $it" } ?: ""
                            } else {
                                ""
                            }
                            "Preset: ${state.preset.label}$customSuffix · ${state.algorithm.label}" +
                                " · ${state.quality.label} · TIFF ${if (state.saveTiff) "Aktif" else "Mati"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onOpenSettings) { Text("Ubah") }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }
    val notices by produceState<String?>(null) {
        value = withContext(Dispatchers.Default) {
            try {
                context.assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                null
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
        title = { Text("Tentang StackScan") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "StackScan menggabungkan banyak foto menjadi satu hasil yang lebih bersih — " +
                        "untuk objek sehari-hari hingga langit malam.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Versi $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "© 2026 soe1hom-arch — dilisensikan di bawah Apache License 2.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Pengembang",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "soe1hom-arch — proyek open-source. Laporan masalah, saran, dan kode " +
                        "sumber tersedia di GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/soe1hom-arch/StackScan")),
                            )
                        } catch (t: Throwable) {
                            // Browser tidak tersedia — abaikan.
                        }
                    },
                ) {
                    Text("github.com/soe1hom-arch/StackScan")
                }
                Text(
                    "Dibangun dengan OpenCV, LibRaw (decode RAW/DNG), Kotlin & Jetpack Compose. " +
                        "Metode stacking adalah teknik standar astronomi/fotografi yang terdokumentasi publik. " +
                        "StackScan tidak berafiliasi dengan lembaga mana pun. " +
                        "Semua pemrosesan 100% offline di perangkat — tidak ada data yang dikirim atau dikumpulkan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Lisensi Pihak Ketiga",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    notices ?: "Teks lisensi tidak ditemukan.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
        title = { Text("Panduan singkat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "1. Ambil beberapa foto tanpa menggeser posisi (burst) — makin banyak foto, makin bersih hasilnya.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "2. Pilih foto-foto itu, lalu tekan Stack & Simpan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "3. Untuk bintang: pakai tripod, preset Astro, dan foto RAW/DNG dari galeri.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "4. Stacking berjalan di latar belakang — boleh keluar aplikasi; " +
                        "notifikasi memberi tahu saat selesai.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "-"
    return if (bytes >= 1024 * 1024) {
        String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024f)
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "-"
    val seconds = millis / 1000f
    return if (seconds < 60) {
        String.format(Locale.US, "%.1f dtk", seconds)
    } else {
        String.format(Locale.US, "%.1f mnt", seconds / 60f)
    }
}

@Composable
private fun HistoryDialog(
    entries: List<StackHistoryEntry>,
    onOpen: (StackHistoryEntry) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Riwayat Hasil") },
        text = {
            if (entries.isEmpty()) {
                Text("Belum ada hasil stacking. Setiap hasil yang berhasil tersimpan otomatis di sini \u2014 termasuk frame Align only.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(entries) { index, entry ->
                        HistoryRow(entry = entry, onClick = { onOpen(entry) }, onRemove = { onRemove(index) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
        dismissButton = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Bersihkan semua") }
            }
        },
    )
}

@Composable
private fun HistoryRow(entry: StackHistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, entry.uri) {
        value = withContext(Dispatchers.Default) {
            try {
                BitmapLoader.loadBitmap(context, Uri.parse(entry.uri), 96).asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Thumbnail hasil",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${entry.algorithm} \u00b7 ${entry.mode}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "${entry.usedFrames} frame \u00b7 ${formatHistoryDate(entry.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${entry.width}\u00d7${entry.height} \u00b7 ${formatHistorySize(entry.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Hapus dari riwayat",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatHistoryDate(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatHistorySize(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000f)
    else -> "$bytes B"
}

