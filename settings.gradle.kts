// Same as the repository: a consuming build that uses it via `includeBuild` sees it under this name.
// Type-safe project accessors are off on purpose: they would clash with the `:ai-engine` module.
rootProject.name = "ai-engine"

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
