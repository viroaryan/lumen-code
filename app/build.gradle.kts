// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing. The signing key is never part of this repository. A release build looks for a
// keystore.properties (storePassword, keyAlias, keyPassword, optional storeFile) in, in order:
//   1. the path in the LUMEN_KEYSTORE_PROPERTIES environment variable,
//   2. ~/.lumen-keys/keystore.properties,
//   3. ../update/keys/keystore.properties next to the project.
// Without one, release builds are signed with the debug key: fine for trying your own build, but
// it can't update an official Lumen install (Android requires the same key for updates).
val keyFile: File? = listOfNotNull(
    System.getenv("LUMEN_KEYSTORE_PROPERTIES"),
    System.getProperty("user.home") + "/.lumen-keys/keystore.properties",
    rootDir.parentFile.path + "/update/keys/keystore.properties",
).map { File(it) }.firstOrNull { it.exists() }
val keyProps = Properties().apply { keyFile?.inputStream()?.use { stream -> load(stream) } }
/** The .jks named in the properties, or the one sitting next to the properties file. */
val keyStore: File? = keyProps.getProperty("storeFile")?.let { File(it) }?.takeIf { it.exists() }
    ?: keyFile?.parentFile?.let { File(it, "lumen-release.jks") }?.takeIf { it.exists() }

android {
    namespace = "app.lumen"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lumen"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        if (keyStore != null) create("release") {
            storeFile = keyStore
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably smoother and the APK smaller.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    // Lint over every dependency added minutes to each release build; the code is checked by debug builds.
    lint {
        checkReleaseBuilds = false
        // Media3's @UnstableApi is already opted into for the whole module through the Kotlin compiler
        // (see kotlin.compilerOptions.optIn below); lint doesn't read that flag and would flag every use.
        disable += "UnsafeOptInUsageError"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.media3.common.util.UnstableApi",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.10.1")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
