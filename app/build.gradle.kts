import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nova.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.iptv"
        minSdk = 24
        targetSdk = 35
        // The build number from GitHub is part of the version: "2.0 (build 25)".
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = if (build != null) "2.0 (build $build)" else "2.0"
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
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
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
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-database:$media3")
    // Extra audio decoders (DTS, AC3, E-AC3, TrueHD, MP2, …) built on FFmpeg, prebuilt by the Jellyfin project.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
