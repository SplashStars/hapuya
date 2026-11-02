plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Spike A5: exact-alarm reliability per device. Sideloaded only, never shipped.
android {
    namespace = "com.hapuya.spike.alarms"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hapuya.spike.alarms"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
