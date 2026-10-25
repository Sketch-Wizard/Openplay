plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.openplay.player"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.openplay.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.7.0"
    }
    signingConfigs {
        create("shared") {
            // Test key kept in the repo on purpose so every build is signed the same way
            // and updates install over older builds. Make a private key before any store release.
            storeFile = file("../openplay.keystore")
            storeType = "pkcs12"
            storePassword = "openplay123"
            keyAlias = "openplay"
            keyPassword = "openplay123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val media3 = "1.7.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // FFmpeg software decoders (AC3, E-AC3, DTS, TrueHD, HEVC, ...). GPL-3.0.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
}
