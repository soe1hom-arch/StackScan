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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StackScanDarkColors = darkColorScheme(
    primary = Color(0xFF6EC6FF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF12415C),
    onPrimaryContainer = Color(0xFFCBE6FF),
    secondary = Color(0xFF64D2C9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF124E48),
    onSecondaryContainer = Color(0xFFB0F1E9),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF4A2A00),
    tertiaryContainer = Color(0xFF6B4200),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE2E6EC),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE2E6EC),
    surfaceVariant = Color(0xFF1B2027),
    onSurfaceVariant = Color(0xFFB0B7C2),
    outline = Color(0xFF3A404A),
    outlineVariant = Color(0xFF262C34),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF5C1515),
    onErrorContainer = Color(0xFFFFDAD6),
)

val ColorScheme.starAccent: Color
    get() = Color(0xFF9FD0FF)

@Composable
fun StackScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StackScanDarkColors,
        content = content,
    )
}
