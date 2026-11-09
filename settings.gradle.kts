pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Hapuya"
include(":app")

// Throwaway M2 experiments (PHASE2_PLAN A4, A5, A6). Never depended on by :app.
include(":spikes:alarms", ":spikes:notify", ":spikes:onphone-ai")

// Dev tools that run on the laptop or CI, never on a phone.
include(":tools:intent-core", ":tools:intent-eval")
