plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The version comes from the repository's VERSION file, the same one the
 * desktop installer and the app's own footer read. One file to bump, and the
 * two apps can never claim different versions of the same release.
 */
val appVersion: String =
    rootProject.file("../VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "0.0.0"

/** versionCode must be an increasing integer: 1.2.3 -> 10203. */
val appVersionCode: Int = appVersion.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    major * 10000 + minor * 100 + patch
}.coerceAtLeast(1)

android {
    namespace = "com.microtube.hexr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.microtube.hexr.tester"
        // Android 8.0. BLE works further back, but the runtime-permission and
        // GATT behaviour below this is different enough to need its own testing,
        // and no HEXR customer is on it.
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Left unminified deliberately. This is an internal QA tool, the APK
            // size is irrelevant, and a stack trace from a technician's phone is
            // worth more than a smaller download.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
