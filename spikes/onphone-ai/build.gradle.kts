plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Spike A6: does on-phone AI work on our test phone, and how fast, how big, how
// accurate? Checks built-in Gemini Nano (ML Kit GenAI) and runs a downloaded
// Gemma 4 E2B model (LiteRT-LM) on the reminder phrase sets in split mode, using
// the same Resolver and scorer as the laptop eval. Sideloaded only, never shipped.
android {
    namespace = "com.hapuya.spike.onphoneai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hapuya.spike.onphoneai"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-test"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Two copies of jackson's licence files; keep one.
        resources.pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

// The phrase sets live outside the repo (05_Testing); copy them into the APK's
// assets at build time, under phrases/.
abstract class CopyPhraseSets : DefaultTask() {
    @get:InputFiles
    abstract val csvFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dest = outputDir.get().asFile.resolve("phrases").apply { deleteRecursively(); mkdirs() }
        csvFiles.forEach { it.copyTo(dest.resolve(it.name), overwrite = true) }
    }
}

val copyPhraseSets = tasks.register<CopyPhraseSets>("copyPhraseSets") {
    csvFiles.from(rootProject.fileTree("../05_Testing/testsets") { include("reminder-phrases-*.csv") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyPhraseSets, CopyPhraseSets::outputDir)
    }
}

dependencies {
    implementation(project(":tools:intent-core"))
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
