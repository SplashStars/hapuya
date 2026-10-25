plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Confirm before the first Play upload: applicationId cannot change afterwards.
    namespace = "com.hapuya.android"
    // Compose 1.12 needs compileSdk 37. targetSdk stays 36 until we opt in to
    // Android 17 behaviour (incl. the READ_CONTACTS justification rule).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hapuya.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Backend URLs only; provider keys never live in the app.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "BACKEND_URL", "\"https://dev.api.hapuya.invalid\"")
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "BACKEND_URL", "\"https://staging.api.hapuya.invalid\"")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "BACKEND_URL", "\"https://api.hapuya.invalid\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
