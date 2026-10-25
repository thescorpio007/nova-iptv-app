import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// "-Plegacy=true": the special build for Android 5.0 and 6 (Galaxy Tab 4 …); the normal build needs Android 7.0.
val legacy = (project.findProperty("legacy") as String?) == "true"

android {
    namespace = "com.nova.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.iptv"
        minSdk = if (legacy) 21 else 24
        targetSdk = 35
        // Version 3.0: build numbers start again at 1 (GitHub's run number − 94). Android only installs a higher
        // internal number, so versionCode = 300000 + build (above every 2.0 build) — updates install over 2.0.
        val build = System.getenv("NOVA_BUILD")?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { it - 94 }
        versionCode = 300000 + (build ?: 0)
        versionName = if (build != null) "3.0 (build $build)" else "3.0"
        // Default TMDB key for the age filter: added at build time from the TMDB_KEY secret (never in the code).
        val tmdb = (System.getenv("TMDB_KEY") ?: "").trim().filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        buildConfigField("String", "TMDB_KEY", "\"$tmdb\"")
    }

    signingConfigs {
        create("release") {
            // Fixed key so every build can install over the previous one. The key and its passwords are never in
            // the public code in clear: keystore-enc/ holds them encrypted, and GitHub opens them with the hidden
            // secret NOVA_KEY_PASS into keystore/ (the private copy keeps keystore/ as it is).
            val local = Properties().apply {
                val f = rootProject.file("keystore/signing.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file("../keystore/nova-release.jks")
            storePassword = System.getenv("NOVA_STORE_PASSWORD") ?: local.getProperty("storePassword")
            keyAlias = "nova"
            keyPassword = System.getenv("NOVA_KEY_PASSWORD") ?: local.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Newer Java functions (dates, collections…) made available on Android 5.
        isCoreLibraryDesugaringEnabled = legacy
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        if (legacy) {
            // The Android 5 build: only check for functions Android 5 doesn't have, and stop on any.
            checkOnly += "NewApi"
            abortOnError = true
            textReport = true
            textOutput = file("lint-newapi.txt")
        } else {
            abortOnError = false
        }
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-database:$media3")
    // Extra audio decoders (DTS, AC3, E-AC3, TrueHD, MP2, …) built on FFmpeg, prebuilt by the Jellyfin project.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
