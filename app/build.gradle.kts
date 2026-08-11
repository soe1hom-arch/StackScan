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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.stackscan"
    compileSdk = 35

    signingConfigs {
        create("release") {
            // Kredensial dibaca dari env var (dianjurkan, mis. di CI) atau
            // gradle.properties lokal. JANGAN commit password ke repo.
            val keystore = file("../stackscan-release.keystore")
            val storePass = System.getenv("STACKSCAN_STORE_PASSWORD")
                ?: (project.findProperty("STACKSCAN_STORE_PASSWORD") as String?)
            val aliasName = System.getenv("STACKSCAN_KEY_ALIAS")
                ?: (project.findProperty("STACKSCAN_KEY_ALIAS") as String?)
            val keyPass = System.getenv("STACKSCAN_KEY_PASSWORD")
                ?: (project.findProperty("STACKSCAN_KEY_PASSWORD") as String?)
            if (keystore.exists() && storePass != null && aliasName != null && keyPass != null) {
                storeFile = keystore
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    defaultConfig {
        applicationId = "com.stackscan"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "9.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.opencv:opencv:4.13.0")


    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
