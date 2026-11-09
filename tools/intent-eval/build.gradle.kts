import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// PHASE2_PLAN A2: runs the reminder phrase set through candidate AI providers and
// scores accuracy, latency and cost. Laptop/CI tool; provider keys come from the
// environment and are never written to disk.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.hapuya.tools.intenteval.MainKt")
}

dependencies {
    implementation(project(":tools:intent-core"))
    implementation(libs.anthropic.java)
    testImplementation(libs.kotlin.test.junit)
}

tasks.named<JavaExec>("run") {
    // Relative dataset/output paths resolve from the repo root (04_Build).
    workingDir = rootDir
    // Forward TLS trust settings (e.g. a local truststore for HTTPS-scanning
    // antivirus) from ~/.gradle/gradle.properties to the forked JVM.
    systemProperties(
        System.getProperties().stringPropertyNames()
            .filter { it.startsWith("javax.net.ssl.") }
            .associateWith { System.getProperty(it) },
    )
}
