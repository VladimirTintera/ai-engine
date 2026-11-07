// NOT "ai-engine": type-safe project accessors name the root project accessor after it, which would
// clash with the `:ai-engine` module (`getAiEngine()` twice). This build does not enable them, but
// feature previews apply to the whole build tree, so a consuming app that enables
// TYPESAFE_PROJECT_ACCESSORS (Plume, grocery) turns them on here too when it uses this repository
// via `includeBuild`. The included build is still called after its directory (`ai-engine`).
rootProject.name = "ai-engine-root"

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
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Interface of an in-process model runtime, no dependencies.
include(":ai-engine")
// On-device model providers (Gemini Nano via ML Kit GenAI, downloaded models, Apple Foundation Models).
include(":ai-core")
// Runtime implementations: LiteRT-LM and llama.cpp (JVM code + native library per platform).
include(":ai-litert")
include(":ai-llamacpp")
include(":ai-llamacpp-android")
include(":ai-llamacpp-desktop")
