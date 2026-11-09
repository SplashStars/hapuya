import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The parts of the A2 intent eval that run anywhere: phrase set and model types,
// split-mode prompt and schema, the Resolver (time words -> dates, policy) and the
// scorer. Used by :tools:intent-eval on the laptop and by :spikes:onphone-ai on the
// phone. M3 moves the Resolver and prompt into the app's core:ai module.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.jackson.databind)
}
