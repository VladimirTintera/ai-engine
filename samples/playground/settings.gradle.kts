// Playground: a small app to download on-device models and try a conversation with them.
//
// A SEPARATE Gradle build, not a module of the library build: apps (Plume, grocery) include the
// library via `includeBuild`, and a sample module there would load the Compose plugin into their
// builds and run in the library's CI. Here the library is included exactly the way an app includes
// it, so the sample is also the reference wiring (README, "Using it in an app").
rootProject.name = "ai-engine-playground"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        // AGP, Kotlin and the library's dependencies: one Gradle build cannot load two versions of
        // AGP, so the sample takes them from the library's catalog instead of pinning its own.
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
        // What only the sample uses (Compose Multiplatform, Ktor…).
        create("playground") {
            from(files("gradle/playground.versions.toml"))
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The library: Gradle substitutes `eu.tintera.ai:<module>` with its projects.
includeBuild("../..")

// Shared Compose UI and logic (Android library, desktop app, iOS framework `ComposeApp`).
include(":composeApp")
// Android app; AGP 9 does not allow the application plugin in a Kotlin Multiplatform module.
include(":androidApp")
// Model runtimes as Play Feature Delivery modules (`PlayLiteRtRuntime`), no code of their own.
include(":litertFeature")
include(":llamacppFeature")
