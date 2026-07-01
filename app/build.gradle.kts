import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.photopreview"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.photopreview"
        minSdk = 23
        targetSdk = 35
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersion") as String?) ?: "dev"
    }

    signingConfigs {
        // Private release key — NEVER committed. Sources in priority order:
        //   1) CI:    env KEYSTORE_FILE + KEYSTORE_PASSWORD (keystore restored from a GitHub Secret)
        //   2) local: keystore.properties at repo root (gitignored)
        //   3) absent: Gradle's auto-generated debug key (casual clones still build, but such APKs
        //              won't install as an OTA update — expected).
        // Every build that has the key signs identically, so OTA updates install in place.
        getByName("debug") {
            val ksProps = Properties()
            val ksFile = rootProject.file("keystore.properties")
            if (ksFile.exists()) ksFile.inputStream().use { ksProps.load(it) }
            val ksPath = System.getenv("KEYSTORE_FILE") ?: ksProps.getProperty("storeFile")
            val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: ksProps.getProperty("storePassword")
            val ks = ksPath?.let { file(it) }
            if (ks != null && ks.exists() && !ksPass.isNullOrEmpty()) {
                storeFile = ks
                storePassword = ksPass
                keyAlias = System.getenv("KEY_ALIAS") ?: ksProps.getProperty("keyAlias") ?: "release"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ksProps.getProperty("keyPassword") ?: ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    val cameraX = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR code: generation (zxing core) + scanning (embedded)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // On-device face detection (ML Kit) for face-follow tracking.
    implementation("com.google.mlkit:face-detection:16.1.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
