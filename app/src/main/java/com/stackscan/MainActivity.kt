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

package com.stackscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.stackscan.processing.ImageStacker
import com.stackscan.ui.StackScreen
import com.stackscan.ui.StackViewModel
import com.stackscan.ui.StackScanTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val viewModel: StackViewModel by viewModels()

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageStacker.MAX_PICKER_IMAGES)) { uris ->
            if (uris.isNotEmpty()) viewModel.onImagesPicked(applicationContext, uris)
        }

    private val pickDarkFrames =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris ->
            if (uris.isNotEmpty()) viewModel.onDarkFramesPicked(applicationContext, uris)
        }

    private val pickFlatFrames =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris ->
            if (uris.isNotEmpty()) viewModel.onFlatFramesPicked(applicationContext, uris)
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* hasil akan muncul sebagai error saat simpan bila ditolak */ }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progres stacking tetap berjalan walau notifikasi ditolak */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "Gagal memuat OpenCV, proses stacking tidak dapat berjalan.")
        }
        // Android 7-9 butuh izin penyimpanan untuk menyimpan hasil ke galeri
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Android 13+ butuh izin untuk menampilkan notifikasi progres stacking.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            StackScanTheme {
                Surface(Modifier.fillMaxSize()) {
                    StackScreen(
                        viewModel = viewModel,
                        onPickImages = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickDarkFrames = {
                            pickDarkFrames.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickFlatFrames = {
                            pickFlatFrames.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "StackScan"
    }
}
