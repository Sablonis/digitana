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

rootProject.name = "Dienstplan"

// :core  – reines Kotlin/JVM: Krypto, CRDT, Nostr, Sync-Engine, Repositories (ohne Android-SDK testbar)
// :app   – Android: Keystore, Compose-Oberfläche, ViewModels
include(":core", ":app")
